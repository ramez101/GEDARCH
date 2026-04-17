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
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import jakarta.persistence.Query;

@Named("suiviDossiersBean")
@ViewScoped
public class SuiviDossiersBean implements Serializable {

    private static final long serialVersionUID = 1L;

    private static EntityManagerFactory emf;

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
            addWarn("Impossible d'envoyer le rappel: emetteur introuvable.");
            return;
        }

        addInfo("Rappel envoye a l'utilisateur " + row.getEmetteur() + " pour le dossier PIN " + row.getPin() + ".");
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

    private String resolveSessionFiliale() {
        return loginBean == null ? "" : loginBean.getCurrentFilialeCode();
    }

    private String resolveSessionLegacyFiliale() {
        return loginBean == null ? "" : loginBean.getCurrentFilialeId();
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
}
