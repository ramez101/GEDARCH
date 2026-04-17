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

@Named("listeDemandesBean")
@ViewScoped
public class ListeDemandesBean implements Serializable {

    private static final long serialVersionUID = 1L;
    private static EntityManagerFactory emf;

    @Resource
    private UserTransaction utx;

    @Inject
    private LoginBean loginBean;

    private List<DemandeRow> demandes = Collections.emptyList();

    @PostConstruct
    public void init() {
        reload();
    }

    public void reload() {
        if (loginBean == null || !loginBean.isAdminRole()) {
            demandes = Collections.emptyList();
            return;
        }

        EntityManager em = getEMF().createEntityManager();
        try {
            String filiale = resolveSessionFiliale();
            String legacyFiliale = resolveSessionLegacyFiliale();
            String filialePredicate = DemandeFilialeUtil.buildPredicate(em, "dd", filiale, legacyFiliale);

            Query query = em.createNativeQuery(
                    "SELECT ID_DEMANDE, PIN, BOITE, EMETTEUR, RECEPTEUR, DATE_ENVOI, DATE_APPROUVE, DATE_RESTITUTION, TYPE_DEMANDE, COMMENTAIRE " +
                            "FROM DEMANDE_DOSSIER dd " +
                            "WHERE " + filialePredicate + " " +
                            "ORDER BY DATE_ENVOI DESC");
            DemandeFilialeUtil.bindParameters(query, filiale, legacyFiliale);

            @SuppressWarnings("unchecked")
            List<Object[]> rows = query.getResultList();

            List<DemandeRow> loaded = new ArrayList<>();
            for (Object[] row : rows) {
                Long id = row[0] instanceof Number ? ((Number) row[0]).longValue() : null;
                String pin = toStringValue(row[1]);
                String boite = toStringValue(row[2]);
                String emetteur = toStringValue(row[3]);
                String recepteur = toStringValue(row[4]);
                Date dateEnvoi = (Date) row[5];
                Date dateApprouve = (Date) row[6];
                Date dateRestitution = (Date) row[7];
                String typeDemande = toStringValue(row[8]);
                String commentaire = toStringValue(row[9]);

                String statut;
                if (dateApprouve != null) {
                    statut = "ACCEPTEE";
                } else if (dateRestitution != null) {
                    statut = "REFUSEE";
                } else {
                    statut = "EN ATTENTE";
                }

                loaded.add(new DemandeRow(id, pin, boite, emetteur, recepteur, dateEnvoi, dateApprouve, dateRestitution, typeDemande, commentaire, statut));
            }
            demandes = loaded;
        } catch (RuntimeException e) {
            demandes = Collections.emptyList();
            addError("Erreur chargement demandes : " + e.getMessage());
        } finally {
            em.close();
        }
    }

    public void accepter(DemandeRow row) {
        processDecision(row, true);
    }

    public void refuser(DemandeRow row) {
        processDecision(row, false);
    }

    private void processDecision(DemandeRow row, boolean approve) {
        if (row == null || row.getIdDemande() == null) {
            return;
        }
        if (!row.isPending()) {
            addWarn("Cette demande est deja traitee.");
            return;
        }

        EntityManager em = getEMF().createEntityManager();
        boolean txStarted = false;
        try {
            utx.begin();
            txStarted = true;
            em.joinTransaction();

            String decisionReceiver = resolveDecisionReceiver();
            String filiale = resolveSessionFiliale();
            String legacyFiliale = resolveSessionLegacyFiliale();
            String filialePredicate = DemandeFilialeUtil.buildPredicate(em, "DEMANDE_DOSSIER", filiale, legacyFiliale);
            String sql;
            if (approve) {
                sql = "UPDATE DEMANDE_DOSSIER " +
                        "SET DATE_APPROUVE = SYSDATE, DATE_RESTITUTION = NULL, RECEPTEUR = :receiver " +
                        "WHERE ID_DEMANDE = :id AND DATE_APPROUVE IS NULL AND DATE_RESTITUTION IS NULL " +
                        "AND " + filialePredicate;
            } else {
                sql = "UPDATE DEMANDE_DOSSIER " +
                        "SET DATE_RESTITUTION = SYSDATE, RECEPTEUR = :receiver " +
                        "WHERE ID_DEMANDE = :id AND DATE_APPROUVE IS NULL AND DATE_RESTITUTION IS NULL " +
                        "AND " + filialePredicate;
            }

            Query query = em.createNativeQuery(sql)
                    .setParameter("receiver", decisionReceiver)
                    .setParameter("id", row.getIdDemande());
            DemandeFilialeUtil.bindParameters(query, filiale, legacyFiliale);

            int updated = query.executeUpdate();

            if (updated == 0) {
                if (txStarted) {
                    try { utx.rollback(); } catch (Exception ignored) {}
                }
                addWarn("Demande deja traitee ou introuvable.");
                reload();
                return;
            }

            utx.commit();
            txStarted = false;
            addInfo(approve ? "Demande acceptee." : "Demande refusee.");
            reload();
        } catch (NotSupportedException | SystemException | RollbackException
                 | HeuristicMixedException | HeuristicRollbackException e) {
            if (txStarted) {
                try { utx.rollback(); } catch (Exception ignored) {}
            }
            addError("Erreur traitement demande : " + e.getMessage());
        } catch (RuntimeException e) {
            if (txStarted) {
                try { utx.rollback(); } catch (Exception ignored) {}
            }
            addError("Erreur traitement demande : " + e.getMessage());
        } finally {
            em.close();
        }
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

    private String resolveDecisionReceiver() {
        if (loginBean == null || loginBean.getUtilisateur() == null) {
            return "admin";
        }

        String lib = normalize(loginBean.getUtilisateur().getLib());
        if (!lib.isBlank()) {
            return lib;
        }
        String unix = normalize(loginBean.getUtilisateur().getUnix());
        if (!unix.isBlank()) {
            return unix;
        }
        String cuti = normalize(loginBean.getUtilisateur().getCuti());
        if (!cuti.isBlank()) {
            return cuti;
        }
        return "admin";
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

    public List<DemandeRow> getDemandes() {
        return demandes;
    }

    public long getPendingCount() {
        if (demandes == null || demandes.isEmpty()) {
            return 0L;
        }
        long count = 0L;
        for (DemandeRow row : demandes) {
            if (row != null && row.isPending()) {
                count++;
            }
        }
        return count;
    }

    public static class DemandeRow implements Serializable {
        private static final long serialVersionUID = 1L;

        private final Long idDemande;
        private final String pin;
        private final String boite;
        private final String emetteur;
        private final String recepteur;
        private final Date dateEnvoi;
        private final Date dateApprouve;
        private final Date dateRestitution;
        private final String typeDemande;
        private final String commentaire;
        private final String statut;

        DemandeRow(Long idDemande, String pin, String boite, String emetteur, String recepteur,
                   Date dateEnvoi, Date dateApprouve, Date dateRestitution, String typeDemande, String commentaire, String statut) {
            this.idDemande = idDemande;
            this.pin = pin;
            this.boite = boite;
            this.emetteur = emetteur;
            this.recepteur = recepteur;
            this.dateEnvoi = dateEnvoi;
            this.dateApprouve = dateApprouve;
            this.dateRestitution = dateRestitution;
            this.typeDemande = typeDemande;
            this.commentaire = commentaire;
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

        public String getTypeDemande() {
            return typeDemande;
        }

        public String getCommentaire() {
            return commentaire;
        }

        public String getStatut() {
            return statut;
        }

        public boolean isPending() {
            return "EN ATTENTE".equals(statut);
        }
    }
}
