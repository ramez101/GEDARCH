package com.btk.bean;

import java.io.IOException;
import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.btk.model.Utilisateur;
import com.btk.util.DemandeFilialeUtil;
import com.btk.util.FilialeUtil;

import jakarta.enterprise.context.SessionScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.faces.event.ComponentSystemEvent;
import jakarta.inject.Named;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import jakarta.persistence.Query;

/**
 * CDI Bean (Jakarta EE 9) – handles login, logout, and session guard.
 * @Named replaces @ManagedBean, @SessionScoped from jakarta.enterprise.context
 */
@Named("loginBean")
@SessionScoped
public class LoginBean implements Serializable {

    private static final long serialVersionUID = 1L;

    // ── Input fields bound to login.xhtml ────────────────────────────────────
    private String cuti;
    private String password;

    // ── Authenticated user stored in session ─────────────────────────────────
    private Utilisateur utilisateur;
    private List<NotificationItem> notifications = Collections.emptyList();
    private long notificationCount;
    private final Set<String> previousNotificationKeys = new HashSet<>();
    private final Set<String> unreadNotificationKeys = new HashSet<>();
    private final Set<String> seenNotificationKeys = new HashSet<>();
    private boolean notificationsPrimed;

    // ── Shared EntityManagerFactory ──────────────────────────────────────────
    private static EntityManagerFactory emf;

    private static synchronized EntityManagerFactory getEMF() {
        if (emf == null || !emf.isOpen()) {
            emf = Persistence.createEntityManagerFactory("btk");
        }
        return emf;
    }

    // ── Login action ─────────────────────────────────────────────────────────
    /**
     * Called by the Login button on login.xhtml.
     * Finds user by CUTI (Utilisateur) in the UTILISATEUR table.
     */
    public String login() {
        String inputCuti = (cuti == null) ? "" : cuti.trim();
        String inputPassword = (password == null) ? "" : password.trim();

        // Generic login (no DB)
        if ("admin".equalsIgnoreCase(inputCuti) && "admin".equals(inputPassword)) {
            Utilisateur generic = new Utilisateur();
            generic.setCuti("admin");
            generic.setUnix("admin");
            generic.setLib("admin");
            generic.setPuti("finance");
            generic.setAge("00000");
            generic.setRole("admin");
            this.utilisateur = generic;
            refreshNotifications();
            return routeByProfile(generic);
        }

        if (inputCuti.isBlank()) {
            addError("L'utilisateur est obligatoire.");
            return null;
        }

        EntityManager em = getEMF().createEntityManager();
        try {
            Utilisateur found = findUserByCuti(em, inputCuti);

            if (found == null) {
                addError("Utilisateur introuvable.");
                return null;
            }

            if (!verifyPassword(found, inputPassword)) {
                addError("Mot de passe incorrect.");
                return null;
            }

            if (!found.isActif()) {
                addError("Compte utilisateur inactif. Contactez l'administrateur.");
                return null;
            }

            this.utilisateur = found;
            refreshNotifications();
            return routeByProfile(found);

        } catch (Exception e) {
            addError("Erreur de connexion : " + e.getMessage());
            return null;
        } finally {
            em.close();
        }
    }

    private Utilisateur findUserByCuti(EntityManager em, String inputCuti) {
        if (em == null || inputCuti == null || inputCuti.isBlank()) {
            return null;
        }

        boolean activeColumnAvailable = hasActiveColumn(em);
        String sql;
        if (activeColumnAvailable) {
            sql = "SELECT CUTI, UNIX, LIB, PUTI, AGE, ROLE, NVL(TO_CHAR(ACTIVE), '1') " +
                  "FROM ARCH_UTILISATEURS " +
                  "WHERE UPPER(TRIM(CUTI)) = UPPER(TRIM(?))";
        } else {
            sql = "SELECT CUTI, UNIX, LIB, PUTI, AGE, ROLE, '1' " +
                  "FROM ARCH_UTILISATEURS " +
                  "WHERE UPPER(TRIM(CUTI)) = UPPER(TRIM(?))";
        }

        Query query = em.createNativeQuery(sql);
        query.setParameter(1, inputCuti);

        List<?> results = query.setMaxResults(1).getResultList();
        if (results.isEmpty()) {
            return null;
        }

        Object[] row = (Object[]) results.get(0);
        Utilisateur utilisateurTrouve = new Utilisateur();
        utilisateurTrouve.setCuti(toText(row[0]));
        utilisateurTrouve.setUnix(toText(row[1]));
        utilisateurTrouve.setLib(toText(row[2]));
        utilisateurTrouve.setPuti(toText(row[3]));
        utilisateurTrouve.setAge(toText(row[4]));
        utilisateurTrouve.setRole(toText(row[5]));
        utilisateurTrouve.setActif(parseActive(row[6]));
        return utilisateurTrouve;
    }

    private String routeByProfile(Utilisateur user) {
        if (user == null) {
            addError("Utilisateur invalide.");
            return null;
        }

        String profile = (user.getPuti() == null) ? "" : user.getPuti().trim();

        if ("finance".equalsIgnoreCase(profile)) {
            return "dashboard?faces-redirect=true";
        }

        if ("bank".equalsIgnoreCase(profile)) {
            return "dashboard2?faces-redirect=true";
        }

        this.utilisateur = null;
        resetNotifications();
        addError("Profil utilisateur inconnu (PUTI).");
        return null;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private String getCurrentViewId() {
        FacesContext fc = FacesContext.getCurrentInstance();
        if (fc == null || fc.getViewRoot() == null) {
            return "";
        }
        return fc.getViewRoot().getViewId();
    }

    private boolean isRestrictedPageForConsultation(String viewId) {
        return "/ajouter-dossiers-archives.xhtml".equals(viewId)
                || "/ajout-archives.xhtml".equals(viewId)
                || "/modifier-archives.xhtml".equals(viewId)
                || "/editions.xhtml".equals(viewId)
                || "/fichedossier.xhtml".equals(viewId)
                || "/listearchives.xhtml".equals(viewId)
                || "/parametres.xhtml".equals(viewId)
                || "/ajouter-utilisateur.xhtml".equals(viewId)
                || "/modifier-utilisateur.xhtml".equals(viewId)
                || "/liste-utilisateurs.xhtml".equals(viewId);
    }

    private boolean isAdminOnlyPage(String viewId) {
        return "/parametres.xhtml".equals(viewId)
                || "/liste-demandes.xhtml".equals(viewId)
                || "/suivi-dossiers.xhtml".equals(viewId);
    }

    private boolean isSuperAdminOnlyPage(String viewId) {
        return "/ajouter-utilisateur.xhtml".equals(viewId)
                || "/modifier-utilisateur.xhtml".equals(viewId)
                || "/liste-utilisateurs.xhtml".equals(viewId);
    }

    private String getDefaultLandingPage() {
        if (isBankProfile()) {
            return "/dashboard2.xhtml";
        }
        return "/dashboard.xhtml";
    }

    private boolean hasAccessToView(String viewId) {
        if (utilisateur == null) {
            return false;
        }

        if (viewId == null || viewId.isBlank()) {
            return true;
        }

        if (isSuperAdminOnlyPage(viewId)) {
            return isSuperAdminRole();
        }

        if (isAdminOnlyPage(viewId)) {
            return isAdminRole();
        }

        if (isAdminRole()) {
            return true;
        }

        return !isConsultationRole() || !isRestrictedPageForConsultation(viewId);
    }

    /**
     * Temporary password verification using the UNIX column as password.
     * Replace later with AD/LDAP verification (or a real password check).
     */
    private boolean verifyPassword(Utilisateur user, String rawPassword) {
        if (user == null) return false;
        if (rawPassword == null) return false;

        String unix = user.getUnix();
        if (unix == null) return false;

        // Current rule: password must equal the value stored in UNIX.
        return rawPassword.equals(unix.trim());
    }

    // ── Logout action ─────────────────────────────────────────────────────────
    private boolean hasActiveColumn(EntityManager em) {
        Number count = (Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM USER_TAB_COLUMNS " +
                        "WHERE TABLE_NAME = 'ARCH_UTILISATEURS' " +
                        "AND COLUMN_NAME = 'ACTIVE'")
                .getSingleResult();
        return count != null && count.longValue() > 0;
    }

    private String toText(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private String normalizeIdentifier(String value) {
        return value == null ? "" : value.trim();
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

    private boolean isCurrentUserActiveInDatabase() {
        if (utilisateur == null || utilisateur.getCuti() == null || utilisateur.getCuti().isBlank()) {
            return false;
        }

        if ("admin".equalsIgnoreCase(utilisateur.getCuti())) {
            return true;
        }

        EntityManager em = getEMF().createEntityManager();
        try {
            if (!hasActiveColumn(em)) {
                return true;
            }

            List<?> results = em.createNativeQuery(
                            "SELECT NVL(TO_CHAR(ACTIVE), '1') " +
                            "FROM ARCH_UTILISATEURS " +
                            "WHERE UPPER(TRIM(CUTI)) = UPPER(TRIM(?))")
                    .setParameter(1, utilisateur.getCuti())
                    .setMaxResults(1)
                    .getResultList();

            if (results.isEmpty()) {
                return false;
            }

            return parseActive(results.get(0));
        } catch (RuntimeException e) {
            return true;
        } finally {
            em.close();
        }
    }

    public String logout() {
        resetNotifications();
        FacesContext.getCurrentInstance().getExternalContext().invalidateSession();
        return "/login?faces-redirect=true";
    }

    // ── Session guard – called by dashboard.xhtml preRenderView ──────────────
    /**
     * Redirects unauthenticated users to login.xhtml.
     * Usage in xhtml: <f:event type="preRenderView" listener="#{loginBean.checkSession}"/>
     */
    public void checkSession(ComponentSystemEvent event) throws IOException {
        FacesContext fc = FacesContext.getCurrentInstance();
        if (fc == null) {
            return;
        }

        if (utilisateur == null) {
            fc.getExternalContext().redirect(
                fc.getExternalContext().getRequestContextPath() + "/login.xhtml"
            );
            fc.responseComplete();
            return;
        }

        if (!isCurrentUserActiveInDatabase()) {
            fc.getExternalContext().invalidateSession();
            fc.getExternalContext().redirect(
                fc.getExternalContext().getRequestContextPath() + "/login.xhtml"
            );
            fc.responseComplete();
            return;
        }

        String viewId = getCurrentViewId();
        if (!hasAccessToView(viewId)) {
            addError("Acces refuse pour ce role.");
            fc.getExternalContext().redirect(
                fc.getExternalContext().getRequestContextPath() + getDefaultLandingPage()
            );
            fc.responseComplete();
            return;
        }

        if (!fc.getPartialViewContext().isAjaxRequest()) {
            refreshNotifications();
        }
    }

    // ── Helper ───────────────────────────────────────────────────────────────
    public void refreshNotifications() {
        if (utilisateur == null) {
            resetNotifications();
            return;
        }

        if (!isAdminRole() && !isConsultationRole()) {
            resetNotifications();
            return;
        }

        EntityManager em = getEMF().createEntityManager();
        try {
            if (isAdminRole()) {
                loadAdminNotifications(em);
            } else {
                loadConsultationNotifications(em);
            }
        } catch (RuntimeException e) {
            // Keep previous notifications when a transient refresh error happens.
        } finally {
            em.close();
        }
    }

    private void loadAdminNotifications(EntityManager em) {
        if (utilisateur == null) {
            resetNotifications();
            return;
        }
        Set<String> currentKeys = new HashSet<>();

        long pendingCount = toLongValue(createScopedAdminDemandeQuery(
                        em,
                        "SELECT COUNT(*)",
                        "DATE_APPROUVE IS NULL AND DATE_RESTITUTION IS NULL",
                        null)
                .getSingleResult());

        long returnedCount = toLongValue(createScopedAdminDemandeQuery(
                        em,
                        "SELECT COUNT(*)",
                        "DATE_APPROUVE IS NOT NULL AND DATE_RESTITUTION IS NOT NULL",
                        null)
                .getSingleResult());

        List<NotificationItem> loaded = new ArrayList<>();
        if (pendingCount > 0) {
            String summaryKey = "admin.pending.summary";
            loaded.add(new NotificationItem(
                    summaryKey,
                    "Nouvelles demandes",
                    pendingCount + " demande(s) en attente de traitement.",
                    null,
                    "pending",
                    "/liste-demandes",
                    isNotificationNew(summaryKey, currentKeys, false)
            ));

            @SuppressWarnings("unchecked")
            List<Object[]> pendingRows = createScopedAdminDemandeQuery(
                            em,
                            "SELECT ID_DEMANDE, PIN, DATE_ENVOI",
                            "DATE_APPROUVE IS NULL AND DATE_RESTITUTION IS NULL",
                            "DATE_ENVOI DESC")
                    .setMaxResults(5)
                    .getResultList();

            for (Object[] row : pendingRows) {
                String id = toText(row[0]);
                String pin = toText(row[1]);
                Date date = toDateValue(row[2]);
                String itemKey = "admin.pending." + safeKeyPart(id);
                loaded.add(new NotificationItem(
                        itemKey,
                        "Demande #" + id,
                        "Nouveau dossier PIN " + pin + ".",
                        date,
                        "pending",
                        "/liste-demandes",
                        isNotificationNew(itemKey, currentKeys)
                ));
            }
        }

        if (returnedCount > 0) {
            String summaryKey = "admin.returned.summary";
            loaded.add(new NotificationItem(
                    summaryKey,
                    "Dossiers restitues",
                    returnedCount + " dossier(s) ont ete restitues.",
                    null,
                    "returned",
                    "/suivi-dossiers",
                    isNotificationNew(summaryKey, currentKeys, false)
            ));

            @SuppressWarnings("unchecked")
            List<Object[]> returnedRows = createScopedAdminDemandeQuery(
                            em,
                            "SELECT ID_DEMANDE, PIN, DATE_RESTITUTION",
                            "DATE_APPROUVE IS NOT NULL AND DATE_RESTITUTION IS NOT NULL",
                            "DATE_RESTITUTION DESC")
                    .setMaxResults(5)
                    .getResultList();

            for (Object[] row : returnedRows) {
                String id = toText(row[0]);
                String pin = toText(row[1]);
                Date date = toDateValue(row[2]);
                String itemKey = "admin.returned." + safeKeyPart(id);
                loaded.add(new NotificationItem(
                        itemKey,
                        "Restitution #" + id,
                        "Le dossier PIN " + pin + " a ete restitue.",
                        date,
                        "returned",
                        "/suivi-dossiers",
                        isNotificationNew(itemKey, currentKeys)
                ));
            }
        }

        notifications = loaded;
        notificationCount = pendingCount + returnedCount;
        applyNotificationSnapshot(currentKeys);
    }

    private void loadConsultationNotifications(EntityManager em) {
        String unix = normalizeIdentifier(utilisateur == null ? null : utilisateur.getUnix());
        String cutiValue = normalizeIdentifier(utilisateur == null ? null : utilisateur.getCuti());
        if (unix.isBlank() && cutiValue.isBlank()) {
            resetNotifications();
            return;
        }
        Set<String> currentKeys = new HashSet<>();

        long approvedCount = toLongValue(createScopedConsultationDemandeQuery(
                        em,
                        "SELECT COUNT(*)",
                        "dd.DATE_APPROUVE IS NOT NULL AND dd.DATE_RESTITUTION IS NULL",
                        null,
                        unix,
                        cutiValue)
                .getSingleResult());

        long refusedCount = toLongValue(createScopedConsultationDemandeQuery(
                        em,
                        "SELECT COUNT(*)",
                        "dd.DATE_APPROUVE IS NULL AND dd.DATE_RESTITUTION IS NOT NULL",
                        null,
                        unix,
                        cutiValue)
                .getSingleResult());

        List<NotificationItem> loaded = new ArrayList<>();
        if (approvedCount > 0) {
            String summaryKey = "consult.approved.summary";
            loaded.add(new NotificationItem(
                    summaryKey,
                    "Demandes approuvees",
                    approvedCount + " demande(s) approuvee(s).",
                    null,
                    "approved",
                    "/suivi-demandes",
                    isNotificationNew(summaryKey, currentKeys, false)
            ));

            @SuppressWarnings("unchecked")
            List<Object[]> approvedRows = createScopedConsultationDemandeQuery(
                            em,
                            "SELECT dd.ID_DEMANDE, dd.PIN, dd.RECEPTEUR, dd.DATE_APPROUVE",
                            "dd.DATE_APPROUVE IS NOT NULL AND dd.DATE_RESTITUTION IS NULL",
                            "dd.DATE_APPROUVE DESC",
                            unix,
                            cutiValue)
                    .setMaxResults(5)
                    .getResultList();

            for (Object[] row : approvedRows) {
                String id = toText(row[0]);
                String pin = toText(row[1]);
                String recepteur = toText(row[2]);
                Date date = toDateValue(row[3]);
                String itemKey = "consult.approved." + safeKeyPart(id);
                loaded.add(new NotificationItem(
                        itemKey,
                        "Demande #" + id + " approuvee",
                        "PIN " + pin + " approuve par " + recepteur + ".",
                        date,
                        "approved",
                        "/suivi-demandes",
                        isNotificationNew(itemKey, currentKeys)
                ));
            }
        }

        if (refusedCount > 0) {
            String summaryKey = "consult.refused.summary";
            loaded.add(new NotificationItem(
                    summaryKey,
                    "Demandes refusees",
                    refusedCount + " demande(s) refusee(s).",
                    null,
                    "refused",
                    "/suivi-demandes",
                    isNotificationNew(summaryKey, currentKeys, false)
            ));

            @SuppressWarnings("unchecked")
            List<Object[]> refusedRows = createScopedConsultationDemandeQuery(
                            em,
                            "SELECT dd.ID_DEMANDE, dd.PIN, dd.RECEPTEUR, dd.DATE_RESTITUTION",
                            "dd.DATE_APPROUVE IS NULL AND dd.DATE_RESTITUTION IS NOT NULL",
                            "dd.DATE_RESTITUTION DESC",
                            unix,
                            cutiValue)
                    .setMaxResults(5)
                    .getResultList();

            for (Object[] row : refusedRows) {
                String id = toText(row[0]);
                String pin = toText(row[1]);
                String recepteur = toText(row[2]);
                Date date = toDateValue(row[3]);
                String itemKey = "consult.refused." + safeKeyPart(id);
                loaded.add(new NotificationItem(
                        itemKey,
                        "Demande #" + id + " refusee",
                        "PIN " + pin + " refuse par " + recepteur + ".",
                        date,
                        "refused",
                        "/suivi-demandes",
                        isNotificationNew(itemKey, currentKeys)
                ));
            }
        }

        @SuppressWarnings("unchecked")
        List<Object[]> overdueRows = createScopedConsultationDemandeQuery(
                        em,
                        "SELECT dd.ID_DEMANDE, dd.PIN, dd.BOITE, dd.DATE_APPROUVE",
                        "dd.DATE_APPROUVE IS NOT NULL " +
                                "AND dd.DATE_RESTITUTION IS NULL " +
                                "AND TRUNC(SYSDATE) - TRUNC(dd.DATE_APPROUVE) >= 10",
                        "dd.DATE_APPROUVE ASC",
                        unix,
                        cutiValue)
                .getResultList();

        long alertCount = overdueRows == null ? 0L : overdueRows.size();
        if (alertCount > 0L) {
            String summaryKey = "consult.alert.summary";
            loaded.add(new NotificationItem(
                    summaryKey,
                    "Alertes de restitution",
                    alertCount + " dossier(s) ont depasse la duree de 10 jours sans restitution.",
                    null,
                    "alert",
                    "/suivi-demandes",
                    isNotificationNew(summaryKey, currentKeys, false)
            ));

            int maxRows = Math.min(overdueRows.size(), 8);
            Date now = new Date();
            for (int i = 0; i < maxRows; i++) {
                Object[] row = overdueRows.get(i);
                String id = toText(row[0]);
                String pin = toText(row[1]);
                String boite = toText(row[2]);
                Date dateApprouve = toDateValue(row[3]);
                long dureeJours = elapsedDays(dateApprouve, now);
                String itemKey = "consult.alert." + safeKeyPart(id);

                StringBuilder message = new StringBuilder();
                message.append("ALERTE : le dossier PIN ").append(pin);
                if (boite != null && !boite.isBlank()) {
                    message.append(" (boite ").append(boite).append(")");
                }
                message.append(" a atteint ").append(dureeJours)
                        .append(dureeJours == 1L ? " jour" : " jours")
                        .append(" depuis son approbation et n'a pas encore ete restitue. ")
                        .append("Merci de proceder a la restitution du dossier au plus vite.");

                loaded.add(new NotificationItem(
                        itemKey,
                        "Alerte dossier #" + id,
                        message.toString(),
                        dateApprouve,
                        "alert",
                        "/suivi-demandes",
                        isNotificationNew(itemKey, currentKeys, true, true)
                ));
            }
        }

        List<RappelNotificationStore.RappelNotification> rappels =
                RappelNotificationStore.findForRecipient(unix, cutiValue, getCurrentFilialeCode());
        long rappelCount = rappels == null ? 0L : rappels.size();
        if (rappelCount > 0L) {
            String summaryKey = "consult.reminder.summary";
            loaded.add(new NotificationItem(
                    summaryKey,
                    "Rappels de restitution",
                    rappelCount + " rappel(s) recu(s) pour des dossiers non restitues.",
                    null,
                    "reminder",
                    "/suivi-demandes",
                    isNotificationNew(summaryKey, currentKeys, false)
            ));

            int maxRows = Math.min(rappels.size(), 8);
            for (int i = 0; i < maxRows; i++) {
                RappelNotificationStore.RappelNotification rappel = rappels.get(i);
                String id = rappel.getIdDemande() == null ? "?" : String.valueOf(rappel.getIdDemande());
                String pin = rappel.getPin();
                String boite = rappel.getBoite();
                String sentBy = rappel.getSentBy();
                String itemKey = "consult.reminder." + rappel.getId();

                StringBuilder message = new StringBuilder();
                message.append("Merci de restituer le dossier PIN ").append(pin);
                if (boite != null && !boite.isBlank()) {
                    message.append(" (boite ").append(boite).append(")");
                }
                if (sentBy != null && !sentBy.isBlank()) {
                    message.append(". Rappel envoye par ").append(sentBy).append(".");
                } else {
                    message.append(".");
                }

                loaded.add(new NotificationItem(
                        itemKey,
                        "Rappel dossier #" + id,
                        message.toString(),
                        rappel.getCreatedAt(),
                        "reminder",
                        "/suivi-demandes",
                        isNotificationNew(itemKey, currentKeys)
                ));
            }
        }

        notifications = loaded;
        notificationCount = approvedCount + refusedCount + alertCount + rappelCount;
        applyNotificationSnapshot(currentKeys);
    }

    private void resetNotifications() {
        notifications = Collections.emptyList();
        notificationCount = 0L;
        previousNotificationKeys.clear();
        unreadNotificationKeys.clear();
        seenNotificationKeys.clear();
        notificationsPrimed = false;
    }

    private Query createScopedAdminDemandeQuery(EntityManager em, String selectClause, String additionalWhere, String orderByClause) {
        String filiale = getCurrentFilialeCode();
        String legacyFiliale = getCurrentFilialeId();
        String filialePredicate = DemandeFilialeUtil.buildPredicate(em, "dd", filiale, legacyFiliale);

        StringBuilder sql = new StringBuilder(selectClause)
                .append(" FROM DEMANDE_DOSSIER dd ")
                .append("WHERE ")
                .append(filialePredicate);

        if (additionalWhere != null && !additionalWhere.isBlank()) {
            sql.append(" AND ").append(additionalWhere);
        }
        if (orderByClause != null && !orderByClause.isBlank()) {
            sql.append(" ORDER BY ").append(orderByClause);
        }

        Query query = em.createNativeQuery(sql.toString());
        DemandeFilialeUtil.bindParameters(query, filiale, legacyFiliale);
        return query;
    }

    private Query createScopedConsultationDemandeQuery(EntityManager em,
                                                       String selectClause,
                                                       String additionalWhere,
                                                       String orderByClause,
                                                       String unix,
                                                       String cutiValue) {
        String filiale = getCurrentFilialeCode();
        String legacyFiliale = getCurrentFilialeId();
        String filialePredicate = DemandeFilialeUtil.buildPredicate(em, "dd", filiale, legacyFiliale);

        StringBuilder sql = new StringBuilder(selectClause)
                .append(" FROM DEMANDE_DOSSIER dd ")
                .append("WHERE ")
                .append(filialePredicate)
                .append(" AND UPPER(TRIM(dd.EMETTEUR)) IN (UPPER(TRIM(:emetteurUnix)), UPPER(TRIM(:emetteurCuti)))");

        if (additionalWhere != null && !additionalWhere.isBlank()) {
            sql.append(" AND ").append(additionalWhere);
        }
        if (orderByClause != null && !orderByClause.isBlank()) {
            sql.append(" ORDER BY ").append(orderByClause);
        }

        Query query = em.createNativeQuery(sql.toString())
                .setParameter("emetteurUnix", unix)
                .setParameter("emetteurCuti", cutiValue);
        DemandeFilialeUtil.bindParameters(query, filiale, legacyFiliale);
        return query;
    }

    private boolean isNotificationNew(String key, Set<String> currentKeys) {
        return isNotificationNew(key, currentKeys, true, false);
    }

    private boolean isNotificationNew(String key, Set<String> currentKeys, boolean countAsUnread) {
        return isNotificationNew(key, currentKeys, countAsUnread, false);
    }

    private boolean isNotificationNew(String key, Set<String> currentKeys, boolean countAsUnread, boolean keepVisibleOnFirstSnapshot) {
        if (key == null || key.isBlank()) {
            return false;
        }
        String normalizedKey = safeKeyPart(key);
        currentKeys.add(normalizedKey);

        // On the first snapshot after login, treat existing rows as already known.
        if (!notificationsPrimed && !keepVisibleOnFirstSnapshot) {
            if (countAsUnread) {
                seenNotificationKeys.add(normalizedKey);
            }
            unreadNotificationKeys.remove(normalizedKey);
            return false;
        }

        if (!countAsUnread || seenNotificationKeys.contains(normalizedKey)) {
            unreadNotificationKeys.remove(normalizedKey);
            return false;
        }

        if (!previousNotificationKeys.contains(normalizedKey)) {
            unreadNotificationKeys.add(normalizedKey);
        }
        return unreadNotificationKeys.contains(normalizedKey);
    }

    private void applyNotificationSnapshot(Set<String> currentKeys) {
        previousNotificationKeys.clear();
        previousNotificationKeys.addAll(currentKeys);
        unreadNotificationKeys.retainAll(currentKeys);
        notificationsPrimed = true;
    }

    private String safeKeyPart(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.trim();
    }

    private long toLongValue(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return 0L;
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

    private long elapsedDays(Date start, Date end) {
        if (start == null || end == null) {
            return 0L;
        }
        LocalDate startDate = Instant.ofEpochMilli(start.getTime())
                .atZone(ZoneId.systemDefault())
                .toLocalDate();
        LocalDate endDate = Instant.ofEpochMilli(end.getTime())
                .atZone(ZoneId.systemDefault())
                .toLocalDate();
        return Math.max(ChronoUnit.DAYS.between(startDate, endDate), 0L);
    }

    private void addError(String msg) {
        FacesContext.getCurrentInstance()
                .addMessage(null, new FacesMessage(FacesMessage.SEVERITY_ERROR, msg, null));
    }

    // ── Getters & Setters ─────────────────────────────────────────────────────
    public String getCuti()                          { return cuti; }
    public void   setCuti(String cuti)               { this.cuti = cuti; }

    public String getPassword()                      { return password; }
    public void   setPassword(String password)       { this.password = password; }

    public Utilisateur getUtilisateur()              { return utilisateur; }
    public void        setUtilisateur(Utilisateur u) { this.utilisateur = u; }

    public boolean isLoggedIn()                      { return utilisateur != null; }

    public List<NotificationItem> getNotifications() {
        return notifications;
    }

    public long getNotificationCount() {
        return notificationCount;
    }

    public String getNotificationBadge() {
        long unread = getNewNotificationCount();
        if (unread > 99L) {
            return "99+";
        }
        return String.valueOf(unread);
    }

    public long getNewNotificationCount() {
        if (notifications == null || notifications.isEmpty()) {
            return 0L;
        }
        long count = 0L;
        for (NotificationItem item : notifications) {
            if (item != null && item.isNewNotification()) {
                count++;
            }
        }
        return count;
    }

    public boolean isNewNotificationsAvailable() {
        return getNewNotificationCount() > 0L;
    }

    public void markNotificationsAsSeen() {
        if (notifications == null || notifications.isEmpty()) {
            unreadNotificationKeys.clear();
            return;
        }

        Set<String> currentKeys = new HashSet<>();
        List<NotificationItem> seenItems = new ArrayList<>(notifications.size());
        for (NotificationItem item : notifications) {
            if (item == null) {
                continue;
            }
            String key = safeKeyPart(item.getKey());
            currentKeys.add(key);
            seenNotificationKeys.add(key);
            seenItems.add(new NotificationItem(
                    key,
                    item.getTitle(),
                    item.getMessage(),
                    item.getDate(),
                    item.getSeverity(),
                    item.getOutcome(),
                    false
            ));
        }

        notifications = seenItems;
        unreadNotificationKeys.clear();
        applyNotificationSnapshot(currentKeys);
    }

    public boolean isBankProfile() {
        return "bank".equals(normalize(utilisateur == null ? null : utilisateur.getPuti()));
    }

    public boolean isFinanceProfile() {
        return "finance".equals(normalize(utilisateur == null ? null : utilisateur.getPuti()));
    }

    public String getCurrentFilialeCode() {
        return FilialeUtil.normalizeKey(utilisateur == null ? null : utilisateur.getPuti());
    }

    public String getCurrentFilialeId() {
        return FilialeUtil.toLegacyId(getCurrentFilialeCode());
    }

    public String getCurrentFilialeLabel() {
        return FilialeUtil.toLabel(getCurrentFilialeCode());
    }

    public boolean isAdminRole() {
        String role = normalize(utilisateur == null ? null : utilisateur.getRole());
        return "admin".equals(role) || "super_admin".equals(role);
    }

    public boolean isSuperAdminRole() {
        return "super_admin".equals(normalize(utilisateur == null ? null : utilisateur.getRole()));
    }

    public boolean isConsultationRole() {
        return "consultation".equals(normalize(utilisateur == null ? null : utilisateur.getRole()));
    }

    public boolean isConsultationAreaAllowed() {
        return utilisateur != null;
    }

    public boolean isTransmissionAllowed() {
        return utilisateur != null;
    }

    private long readPendingDemandesCount() {
        if (!isAdminRole() || utilisateur == null) {
            return 0L;
        }

        EntityManager em = getEMF().createEntityManager();
        try {
            Number count = (Number) createScopedAdminDemandeQuery(
                            em,
                            "SELECT COUNT(*)",
                            "DATE_APPROUVE IS NULL AND DATE_RESTITUTION IS NULL",
                            null)
                    .getSingleResult();
            return count == null ? 0L : count.longValue();
        } catch (RuntimeException e) {
            return 0L;
        } finally {
            em.close();
        }
    }

    public long getPendingDemandesCount() {
        return readPendingDemandesCount();
    }

    public String getPendingDemandesMenuLabel() {
        long count = readPendingDemandesCount();
        if (count > 0L) {
            return "Liste demande (" + count + ")";
        }
        return "Liste demande";
    }

    public boolean isArchiveManagementAllowed() {
        return utilisateur != null && !isConsultationRole();
    }

    public boolean isEditionsAllowed() {
        return utilisateur != null && !isConsultationRole();
    }

    public boolean isBoiteAllowed() {
        return utilisateur != null;
    }

    public boolean isBoiteManageAllowed() {
        return utilisateur != null && !isConsultationRole();
    }

    public boolean isParametersAllowed() {
        return utilisateur != null && isAdminRole();
    }

    public boolean isUserManagementAllowed() {
        return utilisateur != null && isSuperAdminRole();
    }

    public boolean isReadOnlyBoiteMode() {
        return utilisateur != null && isConsultationRole();
    }

    public static class NotificationItem implements Serializable {
        private static final long serialVersionUID = 1L;

        private final String key;
        private final String title;
        private final String message;
        private final Date date;
        private final String severity;
        private final String outcome;
        private final boolean newNotification;

        NotificationItem(String key, String title, String message, Date date, String severity, String outcome, boolean newNotification) {
            this.key = key == null ? "" : key;
            this.title = title == null ? "" : title;
            this.message = message == null ? "" : message;
            this.date = date;
            this.severity = severity == null ? "pending" : severity;
            this.outcome = outcome == null ? "/dashboard" : outcome;
            this.newNotification = newNotification;
        }

        public String getKey() {
            return key;
        }

        public String getTitle() {
            return title;
        }

        public String getMessage() {
            return message;
        }

        public Date getDate() {
            return date;
        }

        public String getSeverity() {
            return severity;
        }

        public String getOutcome() {
            return outcome;
        }

        public boolean isNewNotification() {
            return newNotification;
        }
    }
}
