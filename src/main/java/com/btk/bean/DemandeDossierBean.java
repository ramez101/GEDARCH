package com.btk.bean;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import com.btk.model.ArchDossier;
import com.btk.util.DemandeFilialeUtil;
import com.btk.util.DossierEmpUtil;
import com.btk.util.FilialeUtil;

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
import jakarta.persistence.TypedQuery;
import jakarta.transaction.HeuristicMixedException;
import jakarta.transaction.HeuristicRollbackException;
import jakarta.transaction.NotSupportedException;
import jakarta.transaction.RollbackException;
import jakarta.transaction.SystemException;
import jakarta.transaction.UserTransaction;

@Named("demandeDossierBean")
@ViewScoped
public class DemandeDossierBean implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final String RELATION_SUGGESTION_SEPARATOR = " | ";
    private static final int REQUEST_BLOCK_DELAY_DAYS = 15;

    private static EntityManagerFactory emf;

    @Resource
    private UserTransaction utx;

    @Inject
    private LoginBean loginBean;

    private String searchType = "pin";
    private String searchValue;
    private String pin;
    private String relation;
    private String boite;
    private String typeDemande = "demande dossier complet";
    private String commentaire;
    private boolean dossierLoaded;
    private boolean requestBlocked;
    private String blockedRequestMessage;

    @PostConstruct
    public void init() {
        refreshRequestBlockStatus();
    }

    public void search() {
        boolean byRelation = "relation".equalsIgnoreCase(normalize(searchType));
        String effectiveSearchValue = byRelation ? extractRelationSearchTerm(searchValue) : searchValue;
        String cleanSearchValue = normalize(effectiveSearchValue);
        if (cleanSearchValue.isBlank()) {
            addError("Saisir PIN ou RELATION.");
            return;
        }

        EntityManager em = getEMF().createEntityManager();
        try {
            refreshRequestBlockStatus(em);
            String normalizedSearchValue = cleanSearchValue.toUpperCase(Locale.ROOT);
            String primaryField = byRelation ? "relation" : "pin";
            String fallbackField = byRelation ? "pin" : "relation";

            ArchDossier row = findLatestByField(em, primaryField, normalizedSearchValue);
            if (row == null) {
                row = findLatestByField(em, fallbackField, normalizedSearchValue);
            }

            if (row == null) {
                clearDossierFields();
                addError("Aucun dossier trouvé.");
                return;
            }

            pin = normalize(row.getPin());
            relation = normalize(row.getRelation());
            boite = DossierEmpUtil.findBoitesSummary(em, row.getIdDossier());
            dossierLoaded = true;

            addInfo("Dossier chargé. Compléter les informations puis soumettre.");
        } finally {
            em.close();
        }
    }

    private ArchDossier findLatestByField(EntityManager em, String field, String searchValue) {
        TypedQuery<ArchDossier> query = em.createQuery(
                "select d from " + ArchDossier.class.getSimpleName() + " d " +
                        "where upper(trim(d." + field + ")) = :searchValue " +
                        "and (lower(trim(d.filiale)) = :filiale " +
                        "or (d.filiale is null and lower(trim(d.idFiliale)) = :legacyFiliale)) " +
                        "order by d.idDossier desc",
                ArchDossier.class);
        query.setParameter("searchValue", searchValue);
        query.setParameter("filiale", resolveSessionFiliale());
        query.setParameter("legacyFiliale", resolveSessionLegacyFiliale());
        query.setMaxResults(1);

        List<ArchDossier> rows = query.getResultList();
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<String> completeRelation(String query) {
        if (!"relation".equalsIgnoreCase(normalize(searchType)) || query == null || query.isBlank()) {
            return Collections.emptyList();
        }

        EntityManager em = getEMF().createEntityManager();
        try {
            List<Object[]> rows = em.createQuery(
                            "select distinct d.pin, d.relation from " + ArchDossier.class.getSimpleName() + " d " +
                                    "where (upper(d.relation) like :query or upper(d.pin) like :query) " +
                                    "and (lower(trim(d.filiale)) = :filiale " +
                                    "or (d.filiale is null and lower(trim(d.idFiliale)) = :legacyFiliale)) " +
                                    "order by d.pin, d.relation",
                            Object[].class)
                    .setParameter("query", "%" + query.trim().toUpperCase(Locale.ROOT) + "%")
                    .setParameter("filiale", resolveSessionFiliale())
                    .setParameter("legacyFiliale", resolveSessionLegacyFiliale())
                    .setMaxResults(20)
                    .getResultList();

            List<String> suggestions = new ArrayList<>();
            for (Object[] row : rows) {
                if (row == null || row.length < 2) {
                    continue;
                }
                String pinValue = row[0] == null ? "" : String.valueOf(row[0]).trim();
                String relationValue = row[1] == null ? "" : String.valueOf(row[1]).trim();
                if (relationValue.isBlank()) {
                    continue;
                }
                suggestions.add(formatRelationSuggestion(pinValue, relationValue));
            }
            return suggestions;
        } finally {
            em.close();
        }
    }

    public void submit() {
        if (!dossierLoaded) {
            addError("Rechercher d'abord un dossier.");
            return;
        }

        String cleanPin = normalize(pin);
        String cleanBoite = normalize(boite);
        String cleanTypeDemande = normalize(typeDemande);
        String cleanCommentaire = normalize(commentaire);
        String emetteur = resolveEmitter();
        String sessionFiliale = resolveSessionFiliale();


        if (sessionFiliale.isBlank()) {
            addError("Profil filiale introuvable pour l'envoi de la demande.");
            return;
        }

        EntityManager em = getEMF().createEntityManager();
        boolean txStarted = false;
        try {
            if (refreshRequestBlockStatus(em)) {
                addError(blockedRequestMessage);
                return;
            }

            if (cleanPin.isBlank() || cleanBoite.isBlank()) {
                addError("Informations dossier incomplètes.");
                return;
            }

            if (cleanTypeDemande.isBlank()) {
                addError("Choisir un type de demande.");
                return;
            }

            if (cleanCommentaire.isBlank()) {
                addError("Saisir une justification pour expliquer pourquoi vous allez prendre ce dossier.");
                return;
            }

            if (!hasEligibleAdminReceiver(em, sessionFiliale)) {
                addError("Aucun administrateur actif disponible pour la filiale " + resolveTargetFilialeLabel() + ".");
                return;
            }
            utx.begin();
            txStarted = true;
            em.joinTransaction();

            Number nextId = (Number) em.createNativeQuery(
                            "SELECT NVL(MAX(ID_DEMANDE), 0) + 1 FROM DEMANDE_DOSSIER")
                    .getSingleResult();

            String targetReceiver = resolveTargetReceiverLabel();
            Query insertQuery = em.createNativeQuery(
                            "INSERT INTO DEMANDE_DOSSIER " +
                            "(ID_DEMANDE, PIN, BOITE, EMETTEUR, RECEPTEUR, DATE_ENVOI, DATE_APPROUVE, DATE_RESTITUTION, TYPE_DEMANDE, COMMENTAIRE, FILIALE) " +
                            "VALUES (:id, :pin, :boite, :emetteur, :recepteur, SYSDATE, NULL, NULL, :typeDemande, :commentaire, :filiale)")
                    .setParameter("id", nextId == null ? 1L : nextId.longValue())
                    .setParameter("pin", cleanPin)
                    .setParameter("boite", cleanBoite)
                    .setParameter("emetteur", emetteur)
                    .setParameter("recepteur", targetReceiver)
                    .setParameter("typeDemande", cleanTypeDemande)
                    .setParameter("commentaire", cleanCommentaire)
                    .setParameter("filiale", sessionFiliale);
            insertQuery.executeUpdate();

            utx.commit();
            txStarted = false;
            addInfo("Demande envoyée avec succès.");
            clear();
        } catch (NotSupportedException | SystemException | RollbackException
                 | HeuristicMixedException | HeuristicRollbackException e) {
            if (txStarted) {
                try { utx.rollback(); } catch (Exception ignored) {}
            }
            addError("Erreur envoi demande : " + e.getMessage());
        } catch (RuntimeException e) {
            if (txStarted) {
                try { utx.rollback(); } catch (Exception ignored) {}
            }
            addError("Erreur envoi demande : " + e.getMessage());
        } finally {
            em.close();
        }
    }

    public void clear() {
        searchType = "pin";
        searchValue = null;
        typeDemande = "demande dossier complet";
        commentaire = null;
        clearDossierFields();
        refreshRequestBlockStatus();
    }

    public void refreshBlockNotification() {
        refreshRequestBlockStatus();
    }

    private void clearDossierFields() {
        pin = null;
        relation = null;
        boite = null;
        dossierLoaded = false;
    }

    private void refreshRequestBlockStatus() {
        EntityManager em = getEMF().createEntityManager();
        try {
            refreshRequestBlockStatus(em);
        } finally {
            em.close();
        }
    }

    private boolean refreshRequestBlockStatus(EntityManager em) {
        BlockedRequestInfo blockedRequest = findBlockedRequest(em);
        requestBlocked = blockedRequest != null;
        blockedRequestMessage = requestBlocked ? buildBlockedRequestMessage(blockedRequest) : null;
        return requestBlocked;
    }

    private BlockedRequestInfo findBlockedRequest(EntityManager em) {
        String emitterUnix = normalize(loginBean == null || loginBean.getUtilisateur() == null
                ? null : loginBean.getUtilisateur().getUnix());
        String emitterCuti = normalize(loginBean == null || loginBean.getUtilisateur() == null
                ? null : loginBean.getUtilisateur().getCuti());
        if (emitterUnix.isBlank() && emitterCuti.isBlank()) {
            return null;
        }

        String filiale = resolveSessionFiliale();
        String legacyFiliale = resolveSessionLegacyFiliale();
        String filialePredicate = DemandeFilialeUtil.buildPredicate(em, "dd", filiale, legacyFiliale);

        Query query = em.createNativeQuery(
                        "SELECT dd.PIN, dd.BOITE " +
                                "FROM DEMANDE_DOSSIER dd " +
                                "WHERE " + filialePredicate + " " +
                                "AND UPPER(TRIM(dd.EMETTEUR)) IN (UPPER(TRIM(:emetteurUnix)), UPPER(TRIM(:emetteurCuti))) " +
                                "AND dd.DATE_APPROUVE IS NOT NULL " +
                                "AND dd.DATE_RESTITUTION IS NULL " +
                                "AND TRUNC(SYSDATE) - TRUNC(dd.DATE_APPROUVE) >= :delayDays " +
                                "ORDER BY dd.DATE_APPROUVE ASC")
                .setParameter("emetteurUnix", emitterUnix)
                .setParameter("emetteurCuti", emitterCuti)
                .setParameter("delayDays", REQUEST_BLOCK_DELAY_DAYS)
                .setMaxResults(1);
        DemandeFilialeUtil.bindParameters(query, filiale, legacyFiliale);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();

        if (rows.isEmpty()) {
            return null;
        }

        Object[] row = rows.get(0);
        return new BlockedRequestInfo(toStringValue(row[0]), toStringValue(row[1]));
    }

    private String buildBlockedRequestMessage(BlockedRequestInfo blockedRequest) {
        StringBuilder message = new StringBuilder();
        message.append("Attention : votre accès est actuellement bloqué car le dossier PIN ");
        String pinValue = blockedRequest == null ? "" : normalize(blockedRequest.pin);
        String boiteValue = blockedRequest == null ? "" : normalize(blockedRequest.boite);
        message.append(pinValue.isBlank() ? "?" : pinValue);
        if (!boiteValue.isBlank()) {
            message.append(" (boite ").append(boiteValue).append(")");
        }
        message.append(" n'a pas été restitué depuis plus de ")
                .append(REQUEST_BLOCK_DELAY_DAYS)
                .append(" jours. Merci de procéder à sa restitution afin de pouvoir effectuer une nouvelle demande de dossier.");
        return message.toString();
    }

    private String resolveEmitter() {
        if (loginBean != null && loginBean.getUtilisateur() != null) {
            String unix = normalize(loginBean.getUtilisateur().getUnix());
            if (!unix.isBlank()) {
                return unix;
            }
            String cuti = normalize(loginBean.getUtilisateur().getCuti());
            if (!cuti.isBlank()) {
                return cuti;
            }
        }
        return "unknown";
    }

    private boolean hasEligibleAdminReceiver(EntityManager em, String filialeCode) {
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM ARCH_UTILISATEURS " +
                "WHERE UPPER(TRIM(PUTI)) = UPPER(TRIM(:puti)) " +
                "AND UPPER(TRIM(ROLE)) IN ('ADMIN', 'SUPER_ADMIN')");

        if (hasActiveColumn(em)) {
            sql.append(" AND UPPER(TRIM(NVL(TO_CHAR(ACTIVE), '1'))) ")
               .append("IN ('1', 'TRUE', 'Y', 'YES', 'O', 'OUI', 'ACTIF', 'ACTIVE')");
        }

        Number count = (Number) em.createNativeQuery(sql.toString())
                .setParameter("puti", filialeCode)
                .getSingleResult();
        return count != null && count.longValue() > 0L;
    }

    private boolean hasActiveColumn(EntityManager em) {
        Number count = (Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM USER_TAB_COLUMNS " +
                        "WHERE TABLE_NAME = 'ARCH_UTILISATEURS' " +
                        "AND COLUMN_NAME = 'ACTIVE'")
                .getSingleResult();
        return count != null && count.longValue() > 0L;
    }

    private String resolveTargetReceiverLabel() {
        return "Admins " + resolveTargetFilialeLabel();
    }

    private String resolveTargetFilialeLabel() {
        if (loginBean != null) {
            String label = normalize(loginBean.getCurrentFilialeLabel());
            if (!label.isBlank()) {
                return label;
            }
        }

        String filialeCode = resolveSessionFiliale();
        if ("finance".equalsIgnoreCase(filialeCode)) {
            return "Finance";
        }
        if ("bank".equalsIgnoreCase(filialeCode)) {
            return "Bank";
        }
        return "Admin";
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private String extractRelationSearchTerm(String value) {
        String clean = normalize(value);
        if (clean.isBlank()) {
            return clean;
        }
        int separatorIndex = clean.indexOf(RELATION_SUGGESTION_SEPARATOR);
        if (separatorIndex < 0) {
            return clean;
        }
        String extracted = clean.substring(separatorIndex + RELATION_SUGGESTION_SEPARATOR.length()).trim();
        return extracted.isBlank() ? clean : extracted;
    }

    private String formatRelationSuggestion(String pinValue, String relationValue) {
        String cleanPin = normalize(pinValue);
        String cleanRelation = normalize(relationValue);
        if (cleanPin.isBlank()) {
            return cleanRelation;
        }
        return cleanPin + RELATION_SUGGESTION_SEPARATOR + cleanRelation;
    }

    private String toStringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private void addInfo(String message) {
        FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO, message, null));
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

    public String getSearchType() {
        return searchType;
    }

    public void setSearchType(String searchType) {
        this.searchType = searchType;
    }

    public String getSearchValue() {
        return searchValue;
    }

    public void setSearchValue(String searchValue) {
        this.searchValue = searchValue;
    }

    public String getPin() {
        return pin;
    }

    public String getRelation() {
        return relation;
    }

    public String getBoite() {
        return boite;
    }

    public String getTypeDemande() {
        return typeDemande;
    }

    public void setTypeDemande(String typeDemande) {
        this.typeDemande = typeDemande;
    }

    public String getCommentaire() {
        return commentaire;
    }

    public void setCommentaire(String commentaire) {
        this.commentaire = commentaire;
    }

    public boolean isDossierLoaded() {
        return dossierLoaded;
    }

    public boolean isRequestBlocked() {
        return requestBlocked;
    }

    public String getBlockedRequestMessage() {
        return blockedRequestMessage;
    }

    private String resolveSessionFiliale() {
        return loginBean == null ? "" : loginBean.getCurrentFilialeCode();
    }

    private String resolveSessionLegacyFiliale() {
        if (loginBean != null) {
            return loginBean.getCurrentFilialeId();
        }
        return FilialeUtil.toLegacyId("");
    }

    private static final class BlockedRequestInfo {
        private final String pin;
        private final String boite;

        private BlockedRequestInfo(String pin, String boite) {
            this.pin = pin == null ? "" : pin;
            this.boite = boite == null ? "" : boite;
        }
    }
}
