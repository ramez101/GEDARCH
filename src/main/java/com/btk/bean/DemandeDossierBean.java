package com.btk.bean;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
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
    private static final String USER_BLOCKED_STATUS = "1";
    private static final String USER_UNBLOCKED_STATUS = "0";

    private static EntityManagerFactory emf;

    @Resource
    private UserTransaction utx;

    @Inject
    private LoginBean loginBean;

    private String searchType = "pin";
    private String searchValue;
    private Long dossierId;
    private String pin;
    private String relation;
    private String boite;
    private String typeDemande = "demande dossier complet";
    private String commentaire;
    private List<DocumentOption> availableDocumentOptions = Collections.emptyList();
    private List<String> selectedDocuments = new ArrayList<>();
    private boolean dossierLoaded;
    private boolean requestBlocked;
    private String blockedRequestMessage;
    private boolean blockExceptionActive;
    private String blockExceptionMessage;

    @PostConstruct
    public void init() {
        refreshRequestBlockStatus();
    }

    public void search() {
        boolean byRelation = "relation".equalsIgnoreCase(normalize(searchType));
        String effectiveSearchValue = byRelation ? extractRelationSearchTerm(searchValue) : searchValue;
        String cleanSearchValue = normalize(effectiveSearchValue);
        if (cleanSearchValue.isBlank()) {
            addError("Veuillez saisir un PIN ou une relation.");
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
                addError("Aucun dossier trouvÃ©.");
                return;
            }

            dossierId = row.getIdDossier();
            pin = normalize(row.getPin());
            relation = normalize(row.getRelation());
            boite = DossierEmpUtil.findBoitesSummary(em, row.getIdDossier(), row.getPin(), row.getRelation());
            availableDocumentOptions = loadAvailableDocuments(em, row);
            selectedDocuments = new ArrayList<>();
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
            addError("Veuillez d'abord rechercher un dossier.");
            return;
        }

        String cleanPin = normalize(pin);
        String cleanBoite = normalize(boite);
        String cleanTypeDemande = normalize(typeDemande);
        String cleanCommentaire = normalize(commentaire);
        List<String> cleanSelectedDocuments = normalizeSelectedDocuments(selectedDocuments);
        List<String> selectedDocumentLabels = resolveSelectedDocumentLabels(cleanSelectedDocuments);
        String emetteur = resolveEmitter();
        String emitterUnix = resolveCurrentUserUnix();
        String emitterCuti = resolveCurrentUserCuti();
        String sessionFiliale = resolveSessionFiliale();


        if (sessionFiliale.isBlank()) {
            addError("Profil filiale introuvable pour l'envoi de la demande.");
            return;
        }

        EntityManager em = getEMF().createEntityManager();
        boolean txStarted = false;
        boolean bypassUsed = false;
        try {
            if (refreshRequestBlockStatus(em)) {
                addError(blockedRequestMessage);
                return;
            }

            if (cleanPin.isBlank() || cleanBoite.isBlank()) {
                addError("Informations du dossier incomplÃ¨tes.");
                return;
            }

            if (cleanTypeDemande.isBlank()) {
                addError("Veuillez choisir un type de demande.");
                return;
            }

            if (isDocumentRequest(cleanTypeDemande)) {
                if (availableDocumentOptions == null || availableDocumentOptions.isEmpty()) {
                    addError("Aucun document disponible pour ce dossier.");
                    return;
                }
                if (cleanSelectedDocuments.isEmpty()) {
                    addError("Veuillez sÃƒÂ©lectionner au moins un document.");
                    return;
                }
            }

            if (cleanCommentaire.isBlank()) {
                addError("Saisir une justification pour expliquer pourquoi vous allez prendre ce dossier.");
                return;
            }

            if (!hasEligibleAdminReceiver(em, sessionFiliale)) {
                addError("Aucun administrateur actif disponible pour la filiale " + resolveTargetFilialeLabel() + ".");
                return;
            }

            String storedTypeDemande = buildStoredTypeDemande(cleanTypeDemande, selectedDocumentLabels);
            String storedCommentaire = cleanCommentaire;
            Integer maxTypeLength = resolveTypeDemandeColumnMaxLength(em);
            if (maxTypeLength != null && storedTypeDemande.length() > maxTypeLength) {
                addError("La liste des documents demandÃ©s est trop longue pour TYPE_DEMANDE. RÃ©duisez la sÃ©lection.");
                return;
            }

            Integer maxCommentLength = resolveCommentColumnMaxLength(em);
            if (maxCommentLength != null && storedCommentaire.length() > maxCommentLength) {
                addError("Justification trop longue. Réduisez le texte saisi.");
                return;
            }

            if (blockExceptionActive) {
                bypassUsed = RequestBlockExceptionStore.consumeGrant(sessionFiliale, emitterUnix, emitterCuti) != null;
                if (!bypassUsed && refreshRequestBlockStatus(em)) {
                    addError(blockedRequestMessage);
                    return;
                }
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
                    .setParameter("typeDemande", storedTypeDemande)
                    .setParameter("commentaire", storedCommentaire)
                    .setParameter("filiale", sessionFiliale);
            insertQuery.executeUpdate();

            if (bypassUsed) {
                boolean shouldReblock = hasApprovedNonRestitutedDossier(em, emitterUnix, emitterCuti);
                updateCurrentUserBlockStatus(em, shouldReblock);
            }

            utx.commit();
            txStarted = false;
            if (bypassUsed) {
                addInfo("Déblocage exceptionnel utilisé pour cette demande.");
            }
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
        selectedDocuments = new ArrayList<>();
        clearDossierFields();
        refreshRequestBlockStatus();
    }

    public void refreshBlockNotification() {
        refreshRequestBlockStatus();
    }

    private void clearDossierFields() {
        dossierId = null;
        pin = null;
        relation = null;
        boite = null;
        availableDocumentOptions = Collections.emptyList();
        selectedDocuments = new ArrayList<>();
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
        boolean exceptionGranted = hasBlockExceptionForCurrentUser();
        boolean statutColumnAvailable = hasStatutColumn(em);
        boolean blockedByStatus = statutColumnAvailable && isCurrentUserMarkedBlocked(em);

        if (exceptionGranted) {
            if (statutColumnAvailable) {
                updateCurrentUserBlockStatus(em, false);
            }
            requestBlocked = false;
            blockedRequestMessage = null;
            blockExceptionActive = true;
            blockExceptionMessage = buildBlockExceptionMessage(blockedRequest);
            return false;
        }

        if (blockedRequest != null) {
            if (statutColumnAvailable && !blockedByStatus) {
                updateCurrentUserBlockStatus(em, true);
            }
            requestBlocked = true;
            blockedRequestMessage = buildBlockedRequestMessage(blockedRequest);
            blockExceptionActive = false;
            blockExceptionMessage = null;
            return true;
        }

        if (blockedByStatus) {
            requestBlocked = true;
            blockedRequestMessage = buildStatusBlockedMessage();
            blockExceptionActive = false;
            blockExceptionMessage = null;
            return true;
        }

        if (statutColumnAvailable) {
            updateCurrentUserBlockStatus(em, false);
        }

        requestBlocked = false;
        blockedRequestMessage = null;
        blockExceptionActive = false;
        blockExceptionMessage = null;
        return false;
    }

    private BlockedRequestInfo findBlockedRequest(EntityManager em) {
        String emitterUnix = resolveCurrentUserUnix();
        String emitterCuti = resolveCurrentUserCuti();
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
        message.append("Attention : Votre accès est actuellement bloqué car le dossier PIN ");
        String pinValue = blockedRequest == null ? "" : normalize(blockedRequest.pin);
        String boiteValue = blockedRequest == null ? "" : normalize(blockedRequest.boite);
        message.append(pinValue.isBlank() ? "?" : pinValue);
        if (!boiteValue.isBlank()) {
            message.append(" (boîte ").append(boiteValue).append(")");
        }
        message.append(" n'a pas été restitué depuis plus de ")
                .append(REQUEST_BLOCK_DELAY_DAYS)
                .append(" jours. Merci de procéder à sa restitution afin de pouvoir effectuer une nouvelle demande de dossier.");
        return message.toString();
    }

    private String buildStatusBlockedMessage() {
        return "Attention : votre accès est bloqué. Merci de contacter un administrateur pour le déblocage.";
    }

    private String buildBlockExceptionMessage(BlockedRequestInfo blockedRequest) {
        if (blockedRequest == null) {
            return "Déblocage exceptionnel. Vous pouvez envoyer une seule nouvelle demande.";
        }
        StringBuilder message = new StringBuilder();
        message.append("Déblocage exceptionnel. ");
        message.append("Vous pouvez envoyer une seule nouvelle demande meme si le dossier PIN ");
        String pinValue = blockedRequest == null ? "" : normalize(blockedRequest.pin);
        String boiteValue = blockedRequest == null ? "" : normalize(blockedRequest.boite);
        message.append(pinValue.isBlank() ? "?" : pinValue);
        if (!boiteValue.isBlank()) {
            message.append(" (boîte ").append(boiteValue).append(")");
        }
        message.append(" est toujours non restitué.");
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

    private String resolveCurrentUserUnix() {
        return normalize(loginBean == null || loginBean.getUtilisateur() == null
                ? null : loginBean.getUtilisateur().getUnix());
    }

    private String resolveCurrentUserCuti() {
        return normalize(loginBean == null || loginBean.getUtilisateur() == null
                ? null : loginBean.getUtilisateur().getCuti());
    }

    private boolean hasBlockExceptionForCurrentUser() {
        return RequestBlockExceptionStore.hasActiveGrant(
                resolveSessionFiliale(),
                resolveCurrentUserUnix(),
                resolveCurrentUserCuti()
        );
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

    private boolean isCurrentUserMarkedBlocked(EntityManager em) {
        if (!hasStatutColumn(em)) {
            return false;
        }

        String accountKey = resolveCurrentUserAccountKey();
        if (accountKey.isBlank()) {
            return false;
        }

        String filiale = resolveSessionFiliale();
        String legacyFiliale = resolveSessionLegacyFiliale();
        @SuppressWarnings("unchecked")
        List<Object> rows = em.createNativeQuery(
                        "SELECT NVL(TO_CHAR(STATUT), '0') " +
                                "FROM ARCH_UTILISATEURS " +
                                "WHERE (UPPER(TRIM(CUTI)) = UPPER(TRIM(:accountKey)) " +
                                "OR UPPER(TRIM(UNIX)) = UPPER(TRIM(:accountKey))) " +
                                "AND (LOWER(TRIM(PUTI)) = :sessionFiliale OR LOWER(TRIM(PUTI)) = :sessionLegacyFiliale)")
                .setParameter("accountKey", accountKey)
                .setParameter("sessionFiliale", filiale)
                .setParameter("sessionLegacyFiliale", legacyFiliale)
                .setMaxResults(1)
                .getResultList();
        if (rows.isEmpty()) {
            return false;
        }
        String status = normalize(String.valueOf(rows.get(0)));
        return USER_BLOCKED_STATUS.equals(status);
    }

    private void updateCurrentUserBlockStatus(EntityManager em, boolean blocked) {
        if (!hasStatutColumn(em)) {
            return;
        }

        String accountKey = resolveCurrentUserAccountKey();
        if (accountKey.isBlank()) {
            return;
        }

        String filiale = resolveSessionFiliale();
        String legacyFiliale = resolveSessionLegacyFiliale();
        String newStatus = blocked ? USER_BLOCKED_STATUS : USER_UNBLOCKED_STATUS;
        try {
            em.createNativeQuery(
                            "UPDATE ARCH_UTILISATEURS " +
                                    "SET STATUT = :status " +
                                    "WHERE (UPPER(TRIM(CUTI)) = UPPER(TRIM(:accountKey)) " +
                                    "OR UPPER(TRIM(UNIX)) = UPPER(TRIM(:accountKey))) " +
                                    "AND (LOWER(TRIM(PUTI)) = :sessionFiliale OR LOWER(TRIM(PUTI)) = :sessionLegacyFiliale)")
                    .setParameter("status", newStatus)
                    .setParameter("accountKey", accountKey)
                    .setParameter("sessionFiliale", filiale)
                    .setParameter("sessionLegacyFiliale", legacyFiliale)
                    .executeUpdate();
        } catch (RuntimeException ignored) {
            // Ignore transient write errors in read-only flows; blocking still enforced in-memory.
        }
    }

    private boolean hasApprovedNonRestitutedDossier(EntityManager em, String emitterUnix, String emitterCuti) {
        String cleanUnix = normalize(emitterUnix);
        String cleanCuti = normalize(emitterCuti);
        if (cleanUnix.isBlank() && cleanCuti.isBlank()) {
            return false;
        }

        String filiale = resolveSessionFiliale();
        String legacyFiliale = resolveSessionLegacyFiliale();
        String filialePredicate = DemandeFilialeUtil.buildPredicate(em, "dd", filiale, legacyFiliale);
        Query query = em.createNativeQuery(
                        "SELECT COUNT(*) " +
                                "FROM DEMANDE_DOSSIER dd " +
                                "WHERE " + filialePredicate + " " +
                                "AND UPPER(TRIM(dd.EMETTEUR)) IN (UPPER(TRIM(:emetteurUnix)), UPPER(TRIM(:emetteurCuti))) " +
                                "AND dd.DATE_APPROUVE IS NOT NULL " +
                                "AND dd.DATE_RESTITUTION IS NULL")
                .setParameter("emetteurUnix", cleanUnix)
                .setParameter("emetteurCuti", cleanCuti);
        DemandeFilialeUtil.bindParameters(query, filiale, legacyFiliale);

        Number count = (Number) query.getSingleResult();
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

    private boolean hasStatutColumn(EntityManager em) {
        Number count = (Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM USER_TAB_COLUMNS " +
                                "WHERE TABLE_NAME = 'ARCH_UTILISATEURS' " +
                                "AND COLUMN_NAME = 'STATUT'")
                .getSingleResult();
        return count != null && count.longValue() > 0L;
    }

    private Integer resolveCommentColumnMaxLength(EntityManager em) {
        if (em == null) {
            return null;
        }

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                        "SELECT DATA_TYPE, DATA_LENGTH " +
                                "FROM USER_TAB_COLUMNS " +
                                "WHERE TABLE_NAME = 'DEMANDE_DOSSIER' " +
                                "AND COLUMN_NAME = 'COMMENTAIRE'")
                .setMaxResults(1)
                .getResultList();

        if (rows.isEmpty() || rows.get(0) == null || rows.get(0).length < 2) {
            return null;
        }

        String dataType = toStringValue(rows.get(0)[0]).trim().toUpperCase(Locale.ROOT);
        if (dataType.contains("CLOB")) {
            return null;
        }

        Object lengthValue = rows.get(0)[1];
        if (lengthValue instanceof Number) {
            int value = ((Number) lengthValue).intValue();
            return value > 0 ? value : null;
        }
        return null;
    }

    private Integer resolveTypeDemandeColumnMaxLength(EntityManager em) {
        if (em == null) {
            return null;
        }

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                        "SELECT DATA_TYPE, DATA_LENGTH " +
                                "FROM USER_TAB_COLUMNS " +
                                "WHERE TABLE_NAME = 'DEMANDE_DOSSIER' " +
                                "AND COLUMN_NAME = 'TYPE_DEMANDE'")
                .setMaxResults(1)
                .getResultList();

        if (rows.isEmpty() || rows.get(0) == null || rows.get(0).length < 2) {
            return null;
        }

        String dataType = toStringValue(rows.get(0)[0]).trim().toUpperCase(Locale.ROOT);
        if (dataType.contains("CLOB")) {
            return null;
        }

        Object lengthValue = rows.get(0)[1];
        if (lengthValue instanceof Number) {
            int value = ((Number) lengthValue).intValue();
            return value > 0 ? value : null;
        }
        return null;
    }

    private String resolveCurrentUserAccountKey() {
        String cuti = resolveCurrentUserCuti();
        if (!cuti.isBlank()) {
            return cuti;
        }
        return resolveCurrentUserUnix();
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

    public void onTypeDemandeChange() {
        if (!isDocumentRequest(typeDemande)) {
            selectedDocuments = new ArrayList<>();
        }
    }

    private List<DocumentOption> loadAvailableDocuments(EntityManager em, ArchDossier dossier) {
        if (em == null || dossier == null || dossier.getIdDossier() == null) {
            return Collections.emptyList();
        }

        String dossierName = buildDossierName(dossier.getIdDossier(), dossier.getRelation(), dossier.getPin());
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                        "select distinct DOCUMENTS, PATH_DOSSIER from ARCH_DOCUMENT " +
                                "where upper(NOM_DOSSIER) = :nom and DOCUMENTS is not null " +
                                "order by DOCUMENTS")
                .setParameter("nom", dossierName.toUpperCase(Locale.ROOT))
                .getResultList();

        LinkedHashSet<String> seenValues = new LinkedHashSet<>();
        Map<String, Map<String, String>> labelsByPath = new HashMap<>();
        List<DocumentOption> options = new ArrayList<>();
        for (Object[] row : rows) {
            if (row == null || row.length < 1) {
                continue;
            }
            String fileName = normalize(toStringValue(row[0]));
            String dossierPath = row.length < 2 ? "" : normalize(toStringValue(row[1]));
            if (fileName.isBlank()) {
                continue;
            }

            String fileKey = fileName.toUpperCase(Locale.ROOT);
            if (seenValues.contains(fileKey)) {
                continue;
            }
            seenValues.add(fileKey);

            String label = resolveDocumentLabel(fileName, dossierPath, labelsByPath);
            options.add(new DocumentOption(fileName, label));
        }
        return options;
    }

    private List<String> normalizeSelectedDocuments(List<String> rawDocuments) {
        if (rawDocuments == null || rawDocuments.isEmpty()) {
            return Collections.emptyList();
        }

        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String rawDocument : rawDocuments) {
            String cleanDocument = normalize(rawDocument);
            if (cleanDocument.isBlank()) {
                continue;
            }
            if (!containsDocumentValue(cleanDocument)) {
                continue;
            }
            unique.add(cleanDocument);
        }
        return new ArrayList<>(unique);
    }

    private boolean containsDocumentValue(String candidate) {
        if (availableDocumentOptions == null || availableDocumentOptions.isEmpty()
                || candidate == null || candidate.isBlank()) {
            return false;
        }
        for (DocumentOption option : availableDocumentOptions) {
            if (option == null) {
                continue;
            }
            String value = normalize(option.getValue());
            if (!value.isBlank() && value.equalsIgnoreCase(candidate.trim())) {
                return true;
            }
        }
        return false;
    }

    private List<String> resolveSelectedDocumentLabels(List<String> selectedValues) {
        if (selectedValues == null || selectedValues.isEmpty()) {
            return Collections.emptyList();
        }

        LinkedHashSet<String> labels = new LinkedHashSet<>();
        for (String selectedValue : selectedValues) {
            String cleanValue = normalize(selectedValue);
            if (cleanValue.isBlank()) {
                continue;
            }
            labels.add(resolveDocumentLabelByValue(cleanValue));
        }
        return new ArrayList<>(labels);
    }

    private String resolveDocumentLabelByValue(String selectedValue) {
        if (availableDocumentOptions != null) {
            for (DocumentOption option : availableDocumentOptions) {
                if (option == null) {
                    continue;
                }
                String value = normalize(option.getValue());
                if (!value.isBlank() && value.equalsIgnoreCase(selectedValue)) {
                    String label = normalize(option.getLabel());
                    return label.isBlank() ? selectedValue : label;
                }
            }
        }
        return selectedValue;
    }

    private String resolveDocumentLabel(String fileName,
                                        String dossierPath,
                                        Map<String, Map<String, String>> labelsByPath) {
        String cleanFileName = normalize(fileName);
        if (cleanFileName.isBlank()) {
            return "";
        }
        if (dossierPath == null || dossierPath.isBlank()) {
            return cleanFileName;
        }

        Map<String, String> labels = labelsByPath.computeIfAbsent(
                dossierPath,
                this::readDocumentLabelsFromMetadata
        );
        if (labels.isEmpty()) {
            return cleanFileName;
        }

        String label = labels.get(cleanFileName.toLowerCase(Locale.ROOT));
        if (label == null || label.isBlank()) {
            return cleanFileName;
        }
        return label;
    }

    private Map<String, String> readDocumentLabelsFromMetadata(String dossierPath) {
        if (dossierPath == null || dossierPath.isBlank()) {
            return Collections.emptyMap();
        }

        Path metadataFile = Paths.get(dossierPath).resolve("_metadata.json");
        if (!Files.exists(metadataFile)) {
            return Collections.emptyMap();
        }

        Map<String, String> labels = new HashMap<>();
        try (JsonReader reader = Json.createReader(Files.newBufferedReader(metadataFile, StandardCharsets.UTF_8))) {
            JsonArray entries = reader.readArray();
            for (var value : entries) {
                if (!(value instanceof JsonObject)) {
                    continue;
                }
                JsonObject object = (JsonObject) value;
                String file = normalize(object.getString("file", ""));
                if (file.isBlank()) {
                    continue;
                }
                String documentName = normalize(object.getString("nomDocument", ""));
                labels.put(file.toLowerCase(Locale.ROOT), documentName.isBlank() ? file : documentName);
            }
        } catch (Exception ignored) {
            return Collections.emptyMap();
        }
        return labels;
    }

    private String buildStoredTypeDemande(String demandeTypeValue, List<String> documents) {
        String cleanType = normalize(demandeTypeValue);
        if (!isDocumentRequest(cleanType)) {
            return cleanType;
        }

        String docs = documents == null || documents.isEmpty() ? "" : String.join(" | ", documents);
        if (docs.isBlank()) {
            return cleanType;
        }
        return docs;
    }

    private boolean isDocumentRequest(String demandeTypeValue) {
        return "demande document du dossier".equalsIgnoreCase(normalize(demandeTypeValue));
    }

    private String buildDossierName(Long dossierIdValue, String relationValue, String pinValue) {
        String relationPart = relationValue == null ? "" : relationValue.trim();
        String pinPart = pinValue == null ? "" : pinValue.trim();
        String base = "Dossier num " + dossierIdValue + " : " + relationPart + "-" + pinPart;
        return sanitizeFolderName(base);
    }

    private String sanitizeFolderName(String value) {
        if (value == null) {
            return "Dossier";
        }
        return value.replaceAll("[\\\\/:*?\"<>|]", "-").trim();
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

    public boolean isDocumentRequest() {
        return isDocumentRequest(typeDemande);
    }

    public List<DocumentOption> getAvailableDocumentOptions() {
        return availableDocumentOptions;
    }

    public List<String> getSelectedDocuments() {
        return selectedDocuments;
    }

    public void setSelectedDocuments(List<String> selectedDocuments) {
        this.selectedDocuments = selectedDocuments == null ? new ArrayList<>() : new ArrayList<>(selectedDocuments);
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

    public boolean isBlockExceptionActive() {
        return blockExceptionActive;
    }

    public String getBlockExceptionMessage() {
        return blockExceptionMessage;
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

    public static final class DocumentOption implements Serializable {
        private static final long serialVersionUID = 1L;

        private final String value;
        private final String label;

        private DocumentOption(String value, String label) {
            this.value = value == null ? "" : value;
            this.label = label == null || label.isBlank() ? this.value : label;
        }

        public String getValue() {
            return value;
        }

        public String getLabel() {
            return label;
        }
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

