package com.btk.bean;

import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import com.btk.util.DemandeFilialeUtil;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import jakarta.persistence.Query;
import jakarta.transaction.HeuristicMixedException;
import jakarta.transaction.HeuristicRollbackException;
import jakarta.transaction.NotSupportedException;
import jakarta.transaction.RollbackException;
import jakarta.transaction.SystemException;
import jakarta.transaction.UserTransaction;

@Named("suiviDossiersBean")
@ViewScoped
public class SuiviDossiersBean implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final int REQUEST_BLOCK_DELAY_DAYS = 15;
    private static final String USER_UNBLOCKED_STATUS = "0";

    private static EntityManagerFactory emf;

    @Resource
    private UserTransaction utx;

    @Inject
    private LoginBean loginBean;

    private List<SuiviDossierRow> dossiers = Collections.emptyList();
    private List<ChargeOption> consultationOptions = Collections.emptyList();
    private String selectedCharge;

    @PostConstruct
    public void init() {
        reload();
    }

    public void reload() {
        EntityManager em = getEMF().createEntityManager();
        try {
            consultationOptions = loadConsultationOptions(em);
            ChargeOption selectedOption = findSelectedCharge();
            if (selectedCharge != null && !selectedCharge.isBlank() && selectedOption == null) {
                selectedCharge = null;
            }
            String filiale = resolveSessionFiliale();
            String legacyFiliale = resolveSessionLegacyFiliale();
            String filialePredicate = DemandeFilialeUtil.buildPredicate(em, "dd", filiale, legacyFiliale);

            StringBuilder sql = new StringBuilder(
                    "SELECT ID_DEMANDE, PIN, BOITE, EMETTEUR, RECEPTEUR, DATE_ENVOI, DATE_APPROUVE, DATE_RESTITUTION " +
                    "FROM DEMANDE_DOSSIER dd " +
                    "WHERE " + filialePredicate);

            if (selectedOption != null) {
                sql.append(" AND UPPER(TRIM(EMETTEUR)) IN (UPPER(TRIM(:selectedUnix)), UPPER(TRIM(:selectedCuti)))");
            }
            sql.append(" ORDER BY DATE_ENVOI DESC");

            Query query = em.createNativeQuery(sql.toString());
            DemandeFilialeUtil.bindParameters(query, filiale, legacyFiliale);
            if (selectedOption != null) {
                query.setParameter("selectedUnix", selectedOption.getUnix());
                query.setParameter("selectedCuti", selectedOption.getCuti());
            }

            @SuppressWarnings("unchecked")
            List<Object[]> rows = query.getResultList();

            List<SuiviDossierRow> loaded = new ArrayList<>();
            for (Object[] row : rows) {
                Long idDemande = row[0] instanceof Number ? ((Number) row[0]).longValue() : null;
                String pin = toStringValue(row[1]);
                String boite = toStringValue(row[2]);
                String emetteur = toStringValue(row[3]);
                String recepteur = toStringValue(row[4]);
                Date dateEnvoi = toDateValue(row[5]);
                Date dateApprouve = toDateValue(row[6]);
                Date dateRestitution = toDateValue(row[7]);
                String statut = resolveStatut(dateApprouve, dateRestitution);

                loaded.add(new SuiviDossierRow(
                        idDemande, pin, boite, emetteur, recepteur, dateEnvoi, dateApprouve, dateRestitution, statut
                ));
            }
            dossiers = loaded;
        } catch (RuntimeException e) {
            dossiers = Collections.emptyList();
            addError("Erreur chargement suivi dossiers : " + e.getMessage());
        } finally {
            em.close();
        }
    }

    public void onChargeChange() {
        reload();
    }

    public void envoyerRappel(SuiviDossierRow row) {
        if (row == null || row.getIdDemande() == null) {
            return;
        }
        if (!row.isRappelable()) {
            addWarn("Rappel indisponible pour ce dossier.");
            return;
        }

        String adminSender = "";
        if (loginBean != null && loginBean.getUtilisateur() != null) {
            adminSender = normalize(loginBean.getUtilisateur().getUnix());
            if (adminSender.isBlank()) {
                adminSender = normalize(loginBean.getUtilisateur().getCuti());
            }
        }

        RappelNotificationStore.RappelNotification sent = RappelNotificationStore.publishReminder(
                row.getIdDemande(),
                row.getPin(),
                row.getBoite(),
                row.getEmetteur(),
                row.getRecepteur(),
                adminSender,
                resolveSessionFiliale()
        );

        if (sent == null) {
            addWarn("Impossible d'envoyer le rappel : émetteur introuvable.");
            return;
        }

        addInfo("Rappel envoyé à l'utilisateur " + row.getEmetteur() + " pour le dossier PIN " + row.getPin() + ".");
    }

    public void restituer(SuiviDossierRow row) {
        if (row == null || row.getIdDemande() == null) {
            return;
        }
        if (!row.isRestituable()) {
            addWarn("Cette demande n'est pas eligible a la restitution.");
            return;
        }

        CurrentUserIdentity identity = resolveCurrentUserIdentity();
        if (!identity.hasIdentity()) {
            addError("Utilisateur recepteur introuvable.");
            return;
        }
        if (!identity.matchesReceiver(row.getRecepteur())) {
            addWarn("La restitution doit etre effectuee par le recepteur dans Suivi dossiers.");
            return;
        }

        EntityManager em = getEMF().createEntityManager();
        boolean txStarted = false;
        try {
            utx.begin();
            txStarted = true;
            em.joinTransaction();

            String filiale = resolveSessionFiliale();
            String legacyFiliale = resolveSessionLegacyFiliale();
            String filialePredicate = DemandeFilialeUtil.buildPredicate(em, "DEMANDE_DOSSIER", filiale, legacyFiliale);

            Query updateQuery = em.createNativeQuery(
                            "UPDATE DEMANDE_DOSSIER " +
                                    "SET DATE_RESTITUTION = SYSDATE " +
                                    "WHERE ID_DEMANDE = :id " +
                                    "AND DATE_APPROUVE IS NOT NULL " +
                                    "AND DATE_RESTITUTION IS NULL " +
                                    "AND UPPER(TRIM(RECEPTEUR)) IN (UPPER(TRIM(:receiverLib)), UPPER(TRIM(:receiverUnix)), UPPER(TRIM(:receiverCuti))) " +
                                    "AND " + filialePredicate)
                    .setParameter("id", row.getIdDemande())
                    .setParameter("receiverLib", identity.lib())
                    .setParameter("receiverUnix", identity.unix())
                    .setParameter("receiverCuti", identity.cuti());
            DemandeFilialeUtil.bindParameters(updateQuery, filiale, legacyFiliale);

            int updated = updateQuery.executeUpdate();
            if (updated == 0) {
                if (txStarted) {
                    try { utx.rollback(); } catch (Exception ignored) {}
                }
                addWarn("Demande deja restituee, non approuvee, ou recepteur invalide.");
                reload();
                return;
            }

            if (hasStatutColumn(em) && !hasApprovedNonRestitutedDossierForUser(em, row.getEmetteur())) {
                updateUserBlockStatus(em, row.getEmetteur(), USER_UNBLOCKED_STATUS);
            }

            utx.commit();
            txStarted = false;
            addInfo("Restitution enregistree avec succes.");
            reload();
        } catch (NotSupportedException | SystemException | RollbackException
                 | HeuristicMixedException | HeuristicRollbackException e) {
            if (txStarted) {
                try { utx.rollback(); } catch (Exception ignored) {}
            }
            addError("Erreur restitution : " + e.getMessage());
        } catch (RuntimeException e) {
            if (txStarted) {
                try { utx.rollback(); } catch (Exception ignored) {}
            }
            addError("Erreur restitution : " + e.getMessage());
        } finally {
            em.close();
        }
    }

    public boolean canCurrentUserRestituer(SuiviDossierRow row) {
        if (row == null || !row.isRestituable()) {
            return false;
        }
        CurrentUserIdentity identity = resolveCurrentUserIdentity();
        return identity.hasIdentity() && identity.matchesReceiver(row.getRecepteur());
    }

    public void accorderExceptionBlocage(SuiviDossierRow row) {
        if (row == null || row.getIdDemande() == null) {
            return;
        }
        if (loginBean == null || !loginBean.isAdminRole()) {
            addWarn("Seul un admin peut accorder un deblocage exceptionnel.");
            return;
        }
        if (!isExceptionGrantable(row)) {
            addWarn("Deblocage exceptionnel indisponible pour ce dossier.");
            return;
        }

        String emetteur = normalize(row.getEmetteur());
        if (emetteur.isBlank()) {
            addWarn("Demandeur introuvable pour ce dossier.");
            return;
        }

        EntityManager em = getEMF().createEntityManager();
        boolean txStarted = false;
        try {
            if (!hasStatutColumn(em)) {
                addWarn("Colonne STATUT absente dans ARCH_UTILISATEURS.");
                return;
            }

            utx.begin();
            txStarted = true;
            em.joinTransaction();

            int updated = updateUserBlockStatus(em, emetteur, USER_UNBLOCKED_STATUS);
            if (updated <= 0) {
                if (txStarted) {
                    try { utx.rollback(); } catch (Exception ignored) {}
                }
                addWarn("Impossible de mettre STATUT=0 pour cet utilisateur.");
                return;
            }

            RequestBlockExceptionStore.BlockExceptionGrant grant = RequestBlockExceptionStore.grantOneShot(
                    resolveSessionFiliale(),
                    emetteur,
                    resolveCurrentAdminIdentity(),
                    row.getIdDemande(),
                    row.getPin(),
                    row.getBoite()
            );
            if (grant == null) {
                if (txStarted) {
                    try { utx.rollback(); } catch (Exception ignored) {}
                }
                addWarn("Impossible d'accorder l'exception de deblocage.");
                return;
            }

            utx.commit();
            txStarted = false;
            addInfo("Deblocage exceptionnel accorde a " + emetteur + " pour une seule nouvelle demande.");
        } catch (NotSupportedException | SystemException | RollbackException
                 | HeuristicMixedException | HeuristicRollbackException e) {
            if (txStarted) {
                try { utx.rollback(); } catch (Exception ignored) {}
            }
            addError("Erreur deblocage exceptionnel : " + e.getMessage());
        } catch (RuntimeException e) {
            if (txStarted) {
                try { utx.rollback(); } catch (Exception ignored) {}
            }
            addError("Erreur deblocage exceptionnel : " + e.getMessage());
        } finally {
            em.close();
        }
    }

    public boolean isExceptionGrantable(SuiviDossierRow row) {
        if (row == null || !row.isRestituable()) {
            return false;
        }
        Long duree = row.getDureeJours();
        return duree != null && duree >= REQUEST_BLOCK_DELAY_DAYS;
    }

    public boolean hasActiveException(SuiviDossierRow row) {
        if (row == null) {
            return false;
        }
        return RequestBlockExceptionStore.hasActiveGrant(resolveSessionFiliale(), row.getEmetteur());
    }

    public long getTotalCount() {
        return dossiers == null ? 0L : dossiers.size();
    }

    public long getPendingCount() {
        return countByStatus("EN ATTENTE");
    }

    public long getApprovedCount() {
        return countByStatus("APPROUVEE");
    }

    public long getRefusedCount() {
        return countByStatus("REFUSEE");
    }

    public long getReturnedCount() {
        return countByStatus("RESTITUEE");
    }

    private long countByStatus(String status) {
        if (dossiers == null || dossiers.isEmpty()) {
            return 0L;
        }
        long count = 0L;
        for (SuiviDossierRow row : dossiers) {
            if (row != null && status.equals(row.getStatut())) {
                count++;
            }
        }
        return count;
    }

    private String resolveStatut(Date dateApprouve, Date dateRestitution) {
        if (dateApprouve == null && dateRestitution == null) {
            return "EN ATTENTE";
        }
        if (dateApprouve != null && dateRestitution == null) {
            return "APPROUVEE";
        }
        if (dateApprouve == null) {
            return "REFUSEE";
        }
        return "RESTITUEE";
    }

    private List<ChargeOption> loadConsultationOptions(EntityManager em) {
        String filiale = resolveSessionFiliale();
        String legacyFiliale = resolveSessionLegacyFiliale();

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                        "SELECT CUTI, UNIX, LIB " +
                        "FROM ARCH_UTILISATEURS " +
                        "WHERE UPPER(TRIM(ROLE)) = 'CONSULTATION' " +
                        "AND (LOWER(TRIM(PUTI)) = :sessionFiliale OR LOWER(TRIM(PUTI)) = :sessionLegacyFiliale) " +
                        "ORDER BY UPPER(TRIM(NVL(LIB, UNIX))), UPPER(TRIM(CUTI))")
                .setParameter("sessionFiliale", filiale)
                .setParameter("sessionLegacyFiliale", legacyFiliale)
                .getResultList();

        List<ChargeOption> options = new ArrayList<>();
        for (Object[] row : rows) {
            String cuti = normalize(toStringValue(row[0]));
            String unix = normalize(toStringValue(row[1]));
            String lib = normalize(toStringValue(row[2]));
            String value = !cuti.isBlank() ? cuti : unix;

            if (value.isBlank()) {
                continue;
            }

            options.add(new ChargeOption(value, cuti, unix, buildChargeLabel(lib, unix, cuti)));
        }
        return options;
    }

    private String buildChargeLabel(String lib, String unix, String cuti) {
        String displayName = !lib.isBlank() ? lib : (!unix.isBlank() ? unix : cuti);
        String account = !unix.isBlank() ? unix : cuti;
        if (account.isBlank() || displayName.equalsIgnoreCase(account)) {
            return displayName;
        }
        return displayName + " (" + account + ")";
    }

    private ChargeOption findSelectedCharge() {
        if (selectedCharge == null || selectedCharge.isBlank() || consultationOptions == null) {
            return null;
        }
        for (ChargeOption option : consultationOptions) {
            if (option != null && selectedCharge.equals(option.getValue())) {
                return option;
            }
        }
        return null;
    }

    private Date toDateValue(Object value) {
        if (value instanceof Date) {
            return (Date) value;
        }
        if (value instanceof java.sql.Timestamp) {
            return new Date(((java.sql.Timestamp) value).getTime());
        }
        return null;
    }

    private String toStringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean hasStatutColumn(EntityManager em) {
        Number count = (Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM USER_TAB_COLUMNS " +
                                "WHERE TABLE_NAME = 'ARCH_UTILISATEURS' " +
                                "AND COLUMN_NAME = 'STATUT'")
                .getSingleResult();
        return count != null && count.longValue() > 0L;
    }

    private int updateUserBlockStatus(EntityManager em, String userIdentifier, String statusValue) {
        String cleanUser = normalize(userIdentifier);
        if (cleanUser.isBlank()) {
            return 0;
        }

        String filiale = resolveSessionFiliale();
        String legacyFiliale = resolveSessionLegacyFiliale();
        return em.createNativeQuery(
                        "UPDATE ARCH_UTILISATEURS " +
                                "SET STATUT = :status " +
                                "WHERE (UPPER(TRIM(CUTI)) = UPPER(TRIM(:userId)) " +
                                "OR UPPER(TRIM(UNIX)) = UPPER(TRIM(:userId))) " +
                                "AND (LOWER(TRIM(PUTI)) = :sessionFiliale OR LOWER(TRIM(PUTI)) = :sessionLegacyFiliale)")
                .setParameter("status", statusValue)
                .setParameter("userId", cleanUser)
                .setParameter("sessionFiliale", filiale)
                .setParameter("sessionLegacyFiliale", legacyFiliale)
                .executeUpdate();
    }

    private boolean hasApprovedNonRestitutedDossierForUser(EntityManager em, String userIdentifier) {
        String cleanUser = normalize(userIdentifier);
        if (cleanUser.isBlank()) {
            return false;
        }

        String filiale = resolveSessionFiliale();
        String legacyFiliale = resolveSessionLegacyFiliale();
        String filialePredicate = DemandeFilialeUtil.buildPredicate(em, "dd", filiale, legacyFiliale);

        Query countQuery = em.createNativeQuery(
                        "SELECT COUNT(*) " +
                                "FROM DEMANDE_DOSSIER dd " +
                                "WHERE " + filialePredicate + " " +
                                "AND UPPER(TRIM(dd.EMETTEUR)) IN (UPPER(TRIM(:userIdUnix)), UPPER(TRIM(:userIdCuti))) " +
                                "AND dd.DATE_APPROUVE IS NOT NULL " +
                                "AND dd.DATE_RESTITUTION IS NULL")
                .setParameter("userIdUnix", cleanUser)
                .setParameter("userIdCuti", cleanUser);
        DemandeFilialeUtil.bindParameters(countQuery, filiale, legacyFiliale);

        Number count = (Number) countQuery.getSingleResult();
        return count != null && count.longValue() > 0L;
    }

    private String resolveSessionFiliale() {
        return loginBean == null ? "" : loginBean.getCurrentFilialeCode();
    }

    private String resolveSessionLegacyFiliale() {
        return loginBean == null ? "" : loginBean.getCurrentFilialeId();
    }

    private String resolveCurrentAdminIdentity() {
        if (loginBean != null && loginBean.getUtilisateur() != null) {
            String unix = normalize(loginBean.getUtilisateur().getUnix());
            if (!unix.isBlank()) {
                return unix;
            }
            String cuti = normalize(loginBean.getUtilisateur().getCuti());
            if (!cuti.isBlank()) {
                return cuti;
            }
            String lib = normalize(loginBean.getUtilisateur().getLib());
            if (!lib.isBlank()) {
                return lib;
            }
        }
        return "admin";
    }

    private CurrentUserIdentity resolveCurrentUserIdentity() {
        String lib = "";
        String unix = "";
        String cuti = "";
        if (loginBean != null && loginBean.getUtilisateur() != null) {
            lib = normalize(loginBean.getUtilisateur().getLib());
            unix = normalize(loginBean.getUtilisateur().getUnix());
            cuti = normalize(loginBean.getUtilisateur().getCuti());
        }
        return new CurrentUserIdentity(lib, unix, cuti);
    }

    private void addInfo(String message) {
        FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO, message, null));
    }

    private void addWarn(String message) {
        FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_WARN, message, null));
    }

    private void addError(String message) {
        FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, message, null));
    }

    private static synchronized EntityManagerFactory getEMF() {
        if (emf == null || !emf.isOpen()) {
            emf = Persistence.createEntityManagerFactory("btk");
        }
        return emf;
    }

    public List<SuiviDossierRow> getDossiers() {
        return dossiers;
    }

    public List<ChargeOption> getConsultationOptions() {
        return consultationOptions;
    }

    public String getSelectedCharge() {
        return selectedCharge;
    }

    public void setSelectedCharge(String selectedCharge) {
        this.selectedCharge = selectedCharge;
    }

    public static class SuiviDossierRow implements Serializable {
        private static final long serialVersionUID = 1L;

        private final Long idDemande;
        private final String pin;
        private final String boite;
        private final String emetteur;
        private final String recepteur;
        private final Date dateEnvoi;
        private final Date dateApprouve;
        private final Date dateRestitution;
        private final String statut;

        SuiviDossierRow(Long idDemande, String pin, String boite, String emetteur, String recepteur,
                        Date dateEnvoi, Date dateApprouve, Date dateRestitution, String statut) {
            this.idDemande = idDemande;
            this.pin = pin;
            this.boite = boite;
            this.emetteur = emetteur;
            this.recepteur = recepteur;
            this.dateEnvoi = dateEnvoi;
            this.dateApprouve = dateApprouve;
            this.dateRestitution = dateRestitution;
            this.statut = statut;
        }

        public Long getIdDemande() {
            return idDemande;
        }

        public String getPin() {
            return pin;
        }

        public String getBoite() {
            return boite;
        }

        public String getEmetteur() {
            return emetteur;
        }

        public String getRecepteur() {
            return recepteur;
        }

        public Date getDateEnvoi() {
            return dateEnvoi;
        }

        public Date getDateApprouve() {
            return dateApprouve;
        }

        public Date getDateRestitution() {
            return dateRestitution;
        }

        public String getStatut() {
            return statut;
        }

        public Long getDureeJours() {
            if (dateApprouve == null) {
                return null;
            }

            LocalDate startDate = toLocalDate(dateApprouve);
            LocalDate endDate = dateRestitution != null ? toLocalDate(dateRestitution) : LocalDate.now();
            long days = ChronoUnit.DAYS.between(startDate, endDate);
            return Math.max(days, 0L);
        }

        public String getDureeLabel() {
            Long jours = getDureeJours();
            if (jours == null) {
                return "-";
            }
            return jours + (jours == 1L ? " jour" : " jours");
        }

        public String getDureeStyleClass() {
            Long jours = getDureeJours();
            if (jours == null) {
                return "duree-badge duree-neutral";
            }
            if (jours <= 6L) {
                return "duree-badge duree-yellow";
            }
            if (jours <= 9L) {
                return "duree-badge duree-orange";
            }
            return "duree-badge duree-red";
        }

        public boolean isRappelable() {
            return dateApprouve != null && dateRestitution == null;
        }

        public boolean isRestituable() {
            return dateApprouve != null && dateRestitution == null;
        }

        private LocalDate toLocalDate(Date value) {
            return Instant.ofEpochMilli(value.getTime())
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate();
        }
    }

    public static class ChargeOption implements Serializable {
        private static final long serialVersionUID = 1L;

        private final String value;
        private final String cuti;
        private final String unix;
        private final String label;

        ChargeOption(String value, String cuti, String unix, String label) {
            this.value = value;
            this.cuti = cuti == null ? "" : cuti;
            this.unix = unix == null ? "" : unix;
            this.label = label == null ? "" : label;
        }

        public String getValue() {
            return value;
        }

        public String getCuti() {
            return cuti;
        }

        public String getUnix() {
            return unix;
        }

        public String getLabel() {
            return label;
        }
    }

    private static final class CurrentUserIdentity {
        private final String lib;
        private final String unix;
        private final String cuti;

        private CurrentUserIdentity(String lib, String unix, String cuti) {
            this.lib = lib == null ? "" : lib;
            this.unix = unix == null ? "" : unix;
            this.cuti = cuti == null ? "" : cuti;
        }

        private String lib() {
            return lib;
        }

        private String unix() {
            return unix;
        }

        private String cuti() {
            return cuti;
        }

        private boolean hasIdentity() {
            return !lib.isBlank() || !unix.isBlank() || !cuti.isBlank();
        }

        private boolean matchesReceiver(String receiver) {
            if (receiver == null || receiver.isBlank()) {
                return false;
            }
            String normalizedReceiver = receiver.trim();
            return normalizedReceiver.equalsIgnoreCase(lib)
                    || normalizedReceiver.equalsIgnoreCase(unix)
                    || normalizedReceiver.equalsIgnoreCase(cuti);
        }
    }
}
