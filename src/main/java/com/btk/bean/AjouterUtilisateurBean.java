package com.btk.bean;

import java.io.Serializable;
import com.btk.model.Utilisateur;

import jakarta.annotation.Resource;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Named;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import jakarta.persistence.TypedQuery;
import jakarta.transaction.HeuristicMixedException;
import jakarta.transaction.HeuristicRollbackException;
import jakarta.transaction.NotSupportedException;
import jakarta.transaction.RollbackException;
import jakarta.transaction.SystemException;
import jakarta.transaction.UserTransaction;

@Named("ajouterUtilisateurBean")
@ViewScoped
public class AjouterUtilisateurBean implements Serializable {

    private static final long serialVersionUID = 1L;

    private static EntityManagerFactory emf;

    @Resource
    private UserTransaction utx;

    private String cuti;
    private String unix;
    private String lib;
    private String puti;
    private String age;
    private String role;
    private String modCuti;
    private String modUnix;
    private String modLib;
    private String modPuti;
    private String modAge;
    private String modRole;
    private String modSearchType = "cuti";
    private String modSearchValue;
    private boolean modResultLoaded;

    public void save() {
        String cleanCuti = normalize(cuti);
        String cleanUnix = normalize(unix);
        String cleanLib = normalize(lib);
        String cleanPuti = normalize(puti);
        String cleanAge = normalize(age);
        String cleanRole = normalize(role);

        if (cleanCuti.isBlank() || cleanUnix.isBlank() || cleanLib.isBlank()
                || cleanPuti.isBlank() || cleanRole.isBlank()) {
            addError("CUTI, Mot de passe, Nom, PUTI et ROLE sont obligatoires.");
            markValidationFailed();
            return;
        }

        if (!isAllowedPuti(cleanPuti)) {
            addError("PUTI doit etre Bank ou Finance.");
            markValidationFailed();
            return;
        }

        if (!isAllowedRole(cleanRole)) {
            addError("ROLE doit etre admin, Super_admin ou Consultation.");
            markValidationFailed();
            return;
        }

        if (cleanCuti.length() > 24 || cleanUnix.length() > 20 || cleanLib.length() > 20
                || cleanPuti.length() > 20 || cleanAge.length() > 8 || cleanRole.length() > 30) {
            addError("Verifier les longueurs des champs (CUTI 24, UNIX 20, LIB 20, PUTI 20, AGE 8, ROLE 30).");
            markValidationFailed();
            return;
        }

        EntityManager em = getEMF().createEntityManager();
        boolean txStarted = false;
        try {
            Number count = (Number) em.createNativeQuery(
                            "SELECT COUNT(*) FROM ARCH_UTILISATEURS WHERE UPPER(TRIM(CUTI)) = UPPER(TRIM(?))")
                    .setParameter(1, cleanCuti)
                    .getSingleResult();
            if (count != null && count.longValue() > 0) {
                addError("Ce CUTI existe deja.");
                markValidationFailed();
                return;
            }

            utx.begin();
            txStarted = true;
            em.joinTransaction();

            Utilisateur utilisateur = new Utilisateur();
            utilisateur.setCuti(cleanCuti);
            utilisateur.setUnix(cleanUnix);
            utilisateur.setLib(cleanLib);
            utilisateur.setPuti(normalizePuti(cleanPuti));
            utilisateur.setAge(cleanAge);
            utilisateur.setRole(normalizeRole(cleanRole));

            em.persist(utilisateur);
            em.flush();

            utx.commit();
            txStarted = false;

            addInfo("Utilisateur ajoute avec succes.");
            clear();
        } catch (NotSupportedException | SystemException | RollbackException
                 | HeuristicMixedException | HeuristicRollbackException e) {
            if (txStarted) {
                try { utx.rollback(); } catch (Exception ignored) {}
            }
            addError("Erreur ajout utilisateur : " + e.getMessage());
            markValidationFailed();
        } catch (RuntimeException e) {
            if (txStarted) {
                try { utx.rollback(); } catch (Exception ignored) {}
            }
            addError("Erreur ajout utilisateur : " + e.getMessage());
            markValidationFailed();
        } finally {
            em.close();
        }
    }

    public void searchForUpdate() {
        String cleanSearchValue = normalize(modSearchValue);
        if (cleanSearchValue.isBlank()) {
            addError("Saisir une valeur de recherche.");
            markValidationFailed();
            return;
        }

        EntityManager em = getEMF().createEntityManager();
        try {
            boolean searchByLib = "lib".equalsIgnoreCase(normalize(modSearchType));

            TypedQuery<Utilisateur> query;
            if (searchByLib) {
                query = em.createQuery(
                        "select u from Utilisateur u " +
                                "where upper(trim(u.lib)) = :searchValue " +
                                "order by u.cuti",
                        Utilisateur.class);
                query.setMaxResults(2);
            } else {
                query = em.createQuery(
                        "select u from Utilisateur u " +
                                "where upper(trim(u.cuti)) = :searchValue",
                        Utilisateur.class);
                query.setMaxResults(1);
            }
            query.setParameter("searchValue", cleanSearchValue.toUpperCase());

            java.util.List<Utilisateur> results = query.getResultList();
            if (results.isEmpty()) {
                modCuti = null;
                modUnix = null;
                modLib = null;
                modPuti = null;
                modAge = null;
                modRole = null;
                modResultLoaded = false;
                addError("Aucun utilisateur trouve.");
                markValidationFailed();
                return;
            }

            if (searchByLib && results.size() > 1) {
                modCuti = null;
                modUnix = null;
                modLib = null;
                modPuti = null;
                modAge = null;
                modRole = null;
                modResultLoaded = false;
                addError("Plusieurs utilisateurs avec ce nom. Utiliser la recherche CUTI.");
                markValidationFailed();
                return;
            }

            Utilisateur found = results.get(0);
            modCuti = found.getCuti();
            modUnix = found.getUnix();
            modLib = found.getLib();
            modPuti = found.getPuti();
            modAge = found.getAge();
            modRole = found.getRole();
            modResultLoaded = true;

            addInfo("Utilisateur charge. Vous pouvez modifier puis valider.");
        } catch (RuntimeException e) {
            modCuti = null;
            modUnix = null;
            modLib = null;
            modPuti = null;
            modAge = null;
            modRole = null;
            modResultLoaded = false;
            addError("Erreur recherche utilisateur : " + e.getMessage());
            markValidationFailed();
        } finally {
            em.close();
        }
    }

    public void update() {
        if (!modResultLoaded) {
            addError("Rechercher d'abord un utilisateur (CUTI ou LIB).");
            markValidationFailed();
            return;
        }

        String cleanCuti = normalize(modCuti);
        String cleanUnix = normalize(modUnix);
        String cleanLib = normalize(modLib);
        String cleanPuti = normalize(modPuti);
        String cleanAge = normalize(modAge);
        String cleanRole = normalize(modRole);

        if (cleanCuti.isBlank() || cleanUnix.isBlank() || cleanLib.isBlank()
                || cleanPuti.isBlank() || cleanRole.isBlank()) {
            addError("CUTI, Mot de passe, Nom, PUTI et ROLE sont obligatoires.");
            markValidationFailed();
            return;
        }

        if (!isAllowedPuti(cleanPuti)) {
            addError("PUTI doit etre Bank ou Finance.");
            markValidationFailed();
            return;
        }

        if (!isAllowedRole(cleanRole)) {
            addError("ROLE doit etre admin, Super_admin ou Consultation.");
            markValidationFailed();
            return;
        }

        if (cleanCuti.length() > 24 || cleanUnix.length() > 20 || cleanLib.length() > 20
                || cleanPuti.length() > 20 || cleanAge.length() > 8 || cleanRole.length() > 30) {
            addError("Verifier les longueurs des champs (CUTI 24, UNIX 20, LIB 20, PUTI 20, AGE 8, ROLE 30).");
            markValidationFailed();
            return;
        }

        EntityManager em = getEMF().createEntityManager();
        boolean txStarted = false;
        try {
            Number count = (Number) em.createNativeQuery(
                            "SELECT COUNT(*) FROM ARCH_UTILISATEURS WHERE UPPER(TRIM(CUTI)) = UPPER(TRIM(?))")
                    .setParameter(1, cleanCuti)
                    .getSingleResult();
            if (count == null || count.longValue() == 0) {
                addError("Aucun utilisateur trouve avec ce CUTI.");
                markValidationFailed();
                return;
            }

            utx.begin();
            txStarted = true;
            em.joinTransaction();

            Utilisateur utilisateur = em.find(Utilisateur.class, cleanCuti);
            if (utilisateur == null) {
                if (txStarted) {
                    try { utx.rollback(); } catch (Exception ignored) {}
                    txStarted = false;
                }
                addError("Aucun utilisateur trouve avec ce CUTI.");
                markValidationFailed();
                return;
            }

            utilisateur.setUnix(cleanUnix);
            utilisateur.setLib(cleanLib);
            utilisateur.setPuti(normalizePuti(cleanPuti));
            utilisateur.setAge(cleanAge);
            utilisateur.setRole(normalizeRole(cleanRole));

            em.merge(utilisateur);
            em.flush();

            utx.commit();
            txStarted = false;

            addInfo("Utilisateur modifie avec succes.");
            clearModification();
        } catch (NotSupportedException | SystemException | RollbackException
                 | HeuristicMixedException | HeuristicRollbackException e) {
            if (txStarted) {
                try { utx.rollback(); } catch (Exception ignored) {}
            }
            addError("Erreur modification utilisateur : " + e.getMessage());
            markValidationFailed();
        } catch (RuntimeException e) {
            if (txStarted) {
                try { utx.rollback(); } catch (Exception ignored) {}
            }
            addError("Erreur modification utilisateur : " + e.getMessage());
            markValidationFailed();
        } finally {
            em.close();
        }
    }

    public void clear() {
        cuti = null;
        unix = null;
        lib = null;
        puti = null;
        age = null;
        role = null;
    }

    public void clearModification() {
        modCuti = null;
        modUnix = null;
        modLib = null;
        modPuti = null;
        modAge = null;
        modRole = null;
        modSearchType = "cuti";
        modSearchValue = null;
        modResultLoaded = false;
    }

    private boolean isAllowedPuti(String value) {
        return "bank".equalsIgnoreCase(value) || "finance".equalsIgnoreCase(value);
    }

    private boolean isAllowedRole(String value) {
        return "admin".equalsIgnoreCase(value)
                || "super_admin".equalsIgnoreCase(value)
                || "consultation".equalsIgnoreCase(value);
    }

    private String normalizePuti(String value) {
        return "bank".equalsIgnoreCase(value) ? "Bank" : "Finance";
    }

    private String normalizeRole(String value) {
        if ("admin".equalsIgnoreCase(value)) {
            return "admin";
        }
        if ("super_admin".equalsIgnoreCase(value)) {
            return "Super_admin";
        }
        return "Consultation";
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private void addInfo(String message) {
        FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO, message, null));
    }

    private void addError(String message) {
        FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, message, null));
    }

    private void markValidationFailed() {
        FacesContext.getCurrentInstance().validationFailed();
    }

    private static synchronized EntityManagerFactory getEMF() {
        if (emf == null || !emf.isOpen()) {
            emf = Persistence.createEntityManagerFactory("btk");
        }
        return emf;
    }

    public String getCuti() {
        return cuti;
    }

    public void setCuti(String cuti) {
        this.cuti = cuti;
    }

    public String getUnix() {
        return unix;
    }

    public void setUnix(String unix) {
        this.unix = unix;
    }

    public String getLib() {
        return lib;
    }

    public void setLib(String lib) {
        this.lib = lib;
    }

    public String getPuti() {
        return puti;
    }

    public void setPuti(String puti) {
        this.puti = puti;
    }

    public String getAge() {
        return age;
    }

    public void setAge(String age) {
        this.age = age;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getModCuti() {
        return modCuti;
    }

    public void setModCuti(String modCuti) {
        this.modCuti = modCuti;
    }

    public String getModUnix() {
        return modUnix;
    }

    public void setModUnix(String modUnix) {
        this.modUnix = modUnix;
    }

    public String getModLib() {
        return modLib;
    }

    public void setModLib(String modLib) {
        this.modLib = modLib;
    }

    public String getModPuti() {
        return modPuti;
    }

    public void setModPuti(String modPuti) {
        this.modPuti = modPuti;
    }

    public String getModAge() {
        return modAge;
    }

    public void setModAge(String modAge) {
        this.modAge = modAge;
    }

    public String getModRole() {
        return modRole;
    }

    public void setModRole(String modRole) {
        this.modRole = modRole;
    }

    public String getModSearchType() {
        return modSearchType;
    }

    public void setModSearchType(String modSearchType) {
        this.modSearchType = modSearchType;
    }

    public String getModSearchValue() {
        return modSearchValue;
    }

    public void setModSearchValue(String modSearchValue) {
        this.modSearchValue = modSearchValue;
    }

    public boolean isModResultLoaded() {
        return modResultLoaded;
    }
}
