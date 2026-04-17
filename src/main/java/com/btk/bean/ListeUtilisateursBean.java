package com.btk.bean;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
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

@Named("listeUtilisateursBean")
@ViewScoped
public class ListeUtilisateursBean implements Serializable {

    private static final long serialVersionUID = 1L;

    private static EntityManagerFactory emf;

    @Resource
    private UserTransaction utx;

    private List<UserRow> utilisateurs = new ArrayList<>();
    private boolean activeColumnAvailable;

    @PostConstruct
    public void init() {
        refresh();
    }

    public void refresh() {
        EntityManager em = getEMF().createEntityManager();
        try {
            activeColumnAvailable = hasActiveColumn(em);
            String sql;
            if (activeColumnAvailable) {
                sql = "SELECT CUTI, UNIX, LIB, PUTI, AGE, ROLE, NVL(TO_CHAR(ACTIVE), '1') " +
                      "FROM ARCH_UTILISATEURS " +
                      "ORDER BY CUTI";
            } else {
                sql = "SELECT CUTI, UNIX, LIB, PUTI, AGE, ROLE, '1' " +
                      "FROM ARCH_UTILISATEURS " +
                      "ORDER BY CUTI";
            }

            Query query = em.createNativeQuery(sql);
            List<?> rows = query.getResultList();

            List<UserRow> loaded = new ArrayList<>();
            for (Object raw : rows) {
                Object[] row = (Object[]) raw;
                loaded.add(new UserRow(
                        toText(row[0]),
                        toText(row[1]),
                        toText(row[2]),
                        toText(row[3]),
                        toText(row[4]),
                        toText(row[5]),
                        parseActive(row[6])
                ));
            }
            utilisateurs = loaded;

            if (!activeColumnAvailable) {
                addWarn("La colonne ACTIVE est absente de ARCH_UTILISATEURS. Les comptes sont consideres actifs.");
            }
        } catch (RuntimeException e) {
            addError("Erreur chargement utilisateurs : " + e.getMessage());
        } finally {
            em.close();
        }
    }

    public void toggleActive(UserRow row) {
        if (row == null || row.getCuti() == null || row.getCuti().isBlank()) {
            return;
        }

        if (!activeColumnAvailable) {
            addError("Impossible de modifier l'etat: colonne ACTIVE absente.");
            return;
        }

        boolean newValue = !row.isActif();

        EntityManager em = getEMF().createEntityManager();
        boolean txStarted = false;
        try {
            utx.begin();
            txStarted = true;
            em.joinTransaction();

            int updated = em.createNativeQuery(
                            "UPDATE ARCH_UTILISATEURS " +
                            "SET ACTIVE = ? " +
                            "WHERE UPPER(TRIM(CUTI)) = UPPER(TRIM(?))")
                    .setParameter(1, newValue ? 1 : 0)
                    .setParameter(2, row.getCuti())
                    .executeUpdate();

            if (updated == 0) {
                if (txStarted) {
                    try { utx.rollback(); } catch (Exception ignored) {}
                    txStarted = false;
                }
                addError("Utilisateur introuvable pour mise a jour.");
                return;
            }

            em.flush();
            utx.commit();
            txStarted = false;

            row.setActif(newValue);
            addInfo(newValue
                    ? "Utilisateur active avec succes."
                    : "Utilisateur desactive avec succes.");
        } catch (NotSupportedException | SystemException | RollbackException
                 | HeuristicMixedException | HeuristicRollbackException e) {
            if (txStarted) {
                try { utx.rollback(); } catch (Exception ignored) {}
            }
            addError("Erreur mise a jour active : " + e.getMessage());
        } catch (RuntimeException e) {
            if (txStarted) {
                try { utx.rollback(); } catch (Exception ignored) {}
            }
            addError("Erreur mise a jour active : " + e.getMessage());
        } finally {
            em.close();
        }
    }

    private boolean hasActiveColumn(EntityManager em) {
        Number count = (Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM USER_TAB_COLUMNS " +
                        "WHERE TABLE_NAME = 'ARCH_UTILISATEURS' " +
                        "AND COLUMN_NAME = 'ACTIVE'")
                .getSingleResult();
        return count != null && count.longValue() > 0;
    }

    private String toText(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private boolean parseActive(Object value) {
        if (value == null) {
            return true;
        }

        String normalized = String.valueOf(value).trim().toLowerCase();
        if (normalized.isEmpty()) {
            return true;
        }

        return "1".equals(normalized)
                || "true".equals(normalized)
                || "y".equals(normalized)
                || "yes".equals(normalized)
                || "o".equals(normalized)
                || "oui".equals(normalized)
                || "actif".equals(normalized)
                || "active".equals(normalized);
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

    public List<UserRow> getUtilisateurs() {
        return utilisateurs;
    }

    public boolean isActiveColumnAvailable() {
        return activeColumnAvailable;
    }

    public static class UserRow implements Serializable {
        private static final long serialVersionUID = 1L;

        private String cuti;
        private String unix;
        private String lib;
        private String puti;
        private String age;
        private String role;
        private boolean actif;

        public UserRow(String cuti, String unix, String lib, String puti, String age, String role, boolean actif) {
            this.cuti = cuti;
            this.unix = unix;
            this.lib = lib;
            this.puti = puti;
            this.age = age;
            this.role = role;
            this.actif = actif;
        }

        public String getCuti() {
            return cuti;
        }

        public String getUnix() {
            return unix;
        }

        public String getLib() {
            return lib;
        }

        public String getPuti() {
            return puti;
        }

        public String getAge() {
            return age;
        }

        public String getRole() {
            return role;
        }

        public boolean isActif() {
            return actif;
        }

        public void setActif(boolean actif) {
            this.actif = actif;
        }
    }
}
