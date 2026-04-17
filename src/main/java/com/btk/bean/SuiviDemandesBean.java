package com.btk.bean;

import java.io.Serializable;
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

@Named("suiviDemandesBean")
@ViewScoped
public class SuiviDemandesBean implements Serializable {

    private static final long serialVersionUID = 1L;

    private static EntityManagerFactory emf;

    @Resource
    private UserTransaction utx;

    @Inject
    private LoginBean loginBean;

    private List<SuiviDemandeRow> demandes = Collections.emptyList();

    @PostConstruct
    public void init() {
        reload();
    }

    public void reload() {
        if (loginBean == null || loginBean.getUtilisateur() == null) {
            demandes = Collections.emptyList();
            return;
        }

        String unix = normalize(loginBean.getUtilisateur().getUnix());
        String cuti = normalize(loginBean.getUtilisateur().getCuti());
        if (unix.isBlank() && cuti.isBlank()) {
            demandes = Collections.emptyList();
            return;
        }

        EntityManager em = getEMF().createEntityManager();
        try {
            String filiale = resolveSessionFiliale();
            String legacyFiliale = resolveSessionLegacyFiliale();
            String filialePredicate = DemandeFilialeUtil.buildPredicate(em, "dd", filiale, legacyFiliale);

            Query query = em.createNativeQuery(
                            "SELECT ID_DEMANDE, PIN, BOITE, RECEPTEUR, DATE_ENVOI, DATE_APPROUVE, DATE_RESTITUTION " +
                                    "FROM DEMANDE_DOSSIER dd " +
                                    "WHERE " + filialePredicate + " " +
                                    "AND UPPER(TRIM(EMETTEUR)) IN (UPPER(TRIM(:emetteurUnix)), UPPER(TRIM(:emetteurCuti))) " +
                                    "ORDER BY DATE_ENVOI DESC");
            DemandeFilialeUtil.bindParameters(query, filiale, legacyFiliale);

            @SuppressWarnings("unchecked")
            List<Object[]> rows = query
                    .setParameter("emetteurUnix", unix)
                    .setParameter("emetteurCuti", cuti)
                    .getResultList();

            List<SuiviDemandeRow> loaded = new ArrayList<>();
            for (Object[] row : rows) {
                Long idDemande = row[0] instanceof Number ? ((Number) row[0]).longValue() : null;
                String pin = toStringValue(row[1]);
                String boite = toStringValue(row[2]);
                String recepteur = toStringValue(row[3]);
                Date dateEnvoi = toDateValue(row[4]);
                Date dateApprouve = toDateValue(row[5]);
                Date dateRestitution = toDateValue(row[6]);

                String statut;
                if (dateRestitution != null) {
                    statut = "RESTITUEE";
                } else if (dateApprouve != null) {
                    statut = "APPROUVEE";
                } else {
                    statut = "EN ATTENTE";
                }

                loaded.add(new SuiviDemandeRow(
                        idDemande, pin, boite, recepteur, dateEnvoi, dateApprouve, dateRestitution, statut
                ));
            }
            demandes = loaded;
        } catch (RuntimeException e) {
            demandes = Collections.emptyList();
            addError("Erreur chargement suivi demandes : " + e.getMessage());
        } finally {
            em.close();
        }
    }

    public void restituer(SuiviDemandeRow row) {
        if (row == null || row.getIdDemande() == null) {
            return;
        }
        if (!row.isRestituable()) {
            addWarn("Cette demande n'est pas eligible a la restitution.");
            return;
        }

        String unix = normalize(loginBean == null || loginBean.getUtilisateur() == null
                ? null : loginBean.getUtilisateur().getUnix());
        String cuti = normalize(loginBean == null || loginBean.getUtilisateur() == null
                ? null : loginBean.getUtilisateur().getCuti());
        if (unix.isBlank() && cuti.isBlank()) {
            addError("Utilisateur emetteur introuvable.");
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
                                    "AND UPPER(TRIM(EMETTEUR)) IN (UPPER(TRIM(:emetteurUnix)), UPPER(TRIM(:emetteurCuti))) " +
                                    "AND " + filialePredicate)
                    .setParameter("id", row.getIdDemande())
                    .setParameter("emetteurUnix", unix)
                    .setParameter("emetteurCuti", cuti);
            DemandeFilialeUtil.bindParameters(updateQuery, filiale, legacyFiliale);

            int updated = updateQuery.executeUpdate();

            if (updated == 0) {
                if (txStarted) {
                    try { utx.rollback(); } catch (Exception ignored) {}
                }
                addWarn("Demande deja restituee, non approuvee, ou introuvable.");
                reload();
                return;
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

    public long getRestituableCount() {
        if (demandes == null || demandes.isEmpty()) {
            return 0L;
        }
        long count = 0L;
        for (SuiviDemandeRow row : demandes) {
            if (row != null && row.isRestituable()) {
                count++;
            }
        }
        return count;
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

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private String resolveSessionFiliale() {
        return loginBean == null ? "" : loginBean.getCurrentFilialeCode();
    }

    private String resolveSessionLegacyFiliale() {
        return loginBean == null ? "" : loginBean.getCurrentFilialeId();
    }

    private String toStringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
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

    public List<SuiviDemandeRow> getDemandes() {
        return demandes;
    }

    public static class SuiviDemandeRow implements Serializable {
        private static final long serialVersionUID = 1L;

        private final Long idDemande;
        private final String pin;
        private final String boite;
        private final String recepteur;
        private final Date dateEnvoi;
        private final Date dateApprouve;
        private final Date dateRestitution;
        private final String statut;

        SuiviDemandeRow(Long idDemande, String pin, String boite, String recepteur,
                        Date dateEnvoi, Date dateApprouve, Date dateRestitution, String statut) {
            this.idDemande = idDemande;
            this.pin = pin;
            this.boite = boite;
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

        public boolean isRestituable() {
            return dateApprouve != null && dateRestitution == null;
        }
    }
}
