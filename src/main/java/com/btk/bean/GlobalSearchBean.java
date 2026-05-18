package com.btk.bean;

import com.btk.model.ArchDossier;
import com.btk.util.DossierEmpUtil;
import com.btk.util.FilialeUtil;
import jakarta.annotation.PostConstruct;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;

import java.io.Serializable;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Named("globalSearchBean")
@ViewScoped
public class GlobalSearchBean implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final int MAX_DOSSIER_RESULTS = 25;
    private static final int MAX_DOCUMENT_RESULTS = 25;

    private static EntityManagerFactory emf;

    @Inject
    private LoginBean loginBean;

    private String query;
    private String navbarQuery;
    private boolean searched;
    private String selectedSection = "dossiers";

    private List<DossierHit> dossierResults = Collections.emptyList();
    private List<DocumentHit> documentResults = Collections.emptyList();

    @PostConstruct
    public void init() {
        String q = readRequestParam("q");
        if (q == null) {
            return;
        }

        String normalized = normalizeQuery(q);
        query = normalized;
        navbarQuery = normalized;
        if (!normalized.isBlank()) {
            executeSearch(normalized);
            searched = true;
        }
    }

    public String searchFromNavbar() {
        String normalized = normalizeQuery(navbarQuery);
        if (normalized.isBlank()) {
            return "/recherche-globale.xhtml?faces-redirect=true";
        }
        return "/recherche-globale.xhtml?faces-redirect=true&q=" + urlEncode(normalized);
    }

    public String searchFromPage() {
        String normalized = normalizeQuery(query);
        if (normalized.isBlank()) {
            return "/recherche-globale.xhtml?faces-redirect=true";
        }
        return "/recherche-globale.xhtml?faces-redirect=true&q=" + urlEncode(normalized);
    }

    public String clearSearch() {
        query = "";
        navbarQuery = "";
        searched = false;
        selectedSection = "dossiers";
        resetResults();
        return "/recherche-globale.xhtml?faces-redirect=true";
    }

    private void executeSearch(String term) {
        resetResults();

        EntityManager em = getEMF().createEntityManager();
        try {
            String likeTerm = "%" + term.toUpperCase(Locale.ROOT) + "%";

            List<ArchDossier> dossiers = fetchDossiers(em, likeTerm, parseLongOrNull(term));
            Map<Long, List<Integer>> boitesByDossier = DossierEmpUtil.findBoitesByDossiers(em, dossiers);
            dossierResults = toDossierHits(dossiers, boitesByDossier);

            documentResults = fetchDocumentHits(em, likeTerm, term.toUpperCase(Locale.ROOT));
            selectBestSection();
        } finally {
            em.close();
        }
    }

    private List<ArchDossier> fetchDossiers(EntityManager em, String likeTerm, Long numericIdTerm) {
        StringBuilder jpql = new StringBuilder();
        jpql.append("select d from ").append(ArchDossier.class.getSimpleName()).append(" d ")
                .append("where (lower(trim(d.filiale)) = :filiale ")
                .append("or (d.filiale is null and lower(trim(d.idFiliale)) = :legacyFiliale)) ")
                .append("and (")
                .append("upper(trim(coalesce(d.pin, ''))) like :term ")
                .append("or upper(trim(coalesce(d.relation, ''))) like :term ")
                .append("or upper(trim(coalesce(d.portefeuille, ''))) like :term ")
                .append("or upper(trim(coalesce(d.charge, ''))) like :term");

        if (numericIdTerm != null) {
            jpql.append(" or d.idDossier = :idTerm");
        }

        jpql.append(") order by d.idDossier desc");

        var queryObj = em.createQuery(jpql.toString(), ArchDossier.class)
                .setParameter("filiale", resolveSessionFiliale())
                .setParameter("legacyFiliale", resolveSessionLegacyFiliale())
                .setParameter("term", likeTerm)
                .setMaxResults(MAX_DOSSIER_RESULTS);

        if (numericIdTerm != null) {
            queryObj.setParameter("idTerm", numericIdTerm);
        }

        return queryObj.getResultList();
    }

    private List<DossierHit> toDossierHits(List<ArchDossier> dossiers, Map<Long, List<Integer>> boitesByDossier) {
        if (dossiers == null || dossiers.isEmpty()) {
            return Collections.emptyList();
        }

        List<DossierHit> hits = new ArrayList<>(dossiers.size());
        for (ArchDossier dossier : dossiers) {
            String filialeValue = dossier.getFiliale();
            if (filialeValue == null || filialeValue.isBlank()) {
                filialeValue = dossier.getIdFiliale();
            }

            String boites = DossierEmpUtil.formatBoites(boitesByDossier.get(dossier.getIdDossier()));
            hits.add(new DossierHit(
                    dossier.getIdDossier(),
                    safeString(dossier.getPin()),
                    safeString(dossier.getRelation()),
                    safeString(dossier.getPortefeuille()),
                    safeString(dossier.getCharge()),
                    safeString(dossier.getTypeArchive()),
                    FilialeUtil.toLabel(filialeValue),
                    boites
            ));
        }
        return hits;
    }

    private List<DocumentHit> fetchDocumentHits(EntityManager em, String likeTerm, String normalizedTerm) {
        String pathFilter = resolveDocumentPathFilter();

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                        "select ID_DOCUMENT, DOCUMENTS, NOM_DOSSIER, PATH_DOSSIER, BOITE, UTILISATEUR_CREE, DATE_CREATION " +
                                "from ARCH_DOCUMENT " +
                                "where upper(nvl(PATH_DOSSIER, '')) like :pathFilter " +
                                "order by DATE_CREATION desc nulls last, ID_DOCUMENT desc")
                .setParameter("pathFilter", pathFilter)
                .getResultList();

        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }

        List<DocumentHit> hits = new ArrayList<>();
        Map<String, Map<String, DocumentMetadata>> metadataCacheByPath = new LinkedHashMap<>();
        for (Object[] row : rows) {
            Long id = toLong(row[0]);
            String fileName = safeString(row[1]);
            String dossierName = safeString(row[2]);
            String pathDossier = safeString(row[3]);
            Integer boite = toInteger(row[4]);
            String utilisateur = safeString(row[5]);
            Date dateCreation = toDate(row[6]);

            String cacheKey = pathDossier.toLowerCase(Locale.ROOT);
            Map<String, DocumentMetadata> metadataByFile = metadataCacheByPath.computeIfAbsent(
                    cacheKey,
                    ignored -> readMetadataByFileName(pathDossier)
            );
            DocumentMetadata metadata = metadataByFile.get(fileName.toLowerCase(Locale.ROOT));

            String documentName = metadata == null ? "" : metadata.nomDocument;
            String documentDescription = metadata == null ? "" : metadata.description;

            boolean matches = containsIgnoreCase(fileName, normalizedTerm)
                    || containsIgnoreCase(dossierName, normalizedTerm)
                    || containsIgnoreCase(documentName, normalizedTerm)
                    || containsIgnoreCase(documentDescription, normalizedTerm);
            if (!matches) {
                continue;
            }

            ExtractedReference ref = extractReferenceFromDossierName(dossierName);

            String outcome = null;
            String paramName = null;
            String paramValue = null;
            if (!ref.pin.isBlank()) {
                outcome = "/consultation-archives";
                paramName = "globalSearchType";
                paramValue = "pin|" + ref.pin;
            } else if (!ref.relation.isBlank()) {
                outcome = "/consultation-archives";
                paramName = "globalSearchType";
                paramValue = "relation|" + ref.relation;
            } else if (boite != null) {
                outcome = "/consultation-boites";
                paramName = "globalBoite";
                paramValue = String.valueOf(boite);
            }

            hits.add(new DocumentHit(
                    id,
                    fileName,
                    documentName,
                    documentDescription,
                    dossierName,
                    boite,
                    utilisateur,
                    dateCreation,
                    ref.pin,
                    ref.relation,
                    outcome,
                    paramName,
                    paramValue
            ));

            if (hits.size() >= MAX_DOCUMENT_RESULTS) {
                break;
            }
        }

        return hits;
    }

    private Map<String, DocumentMetadata> readMetadataByFileName(String folderPath) {
        if (folderPath == null || folderPath.isBlank()) {
            return Collections.emptyMap();
        }

        Path metadataFile = Paths.get(folderPath).resolve("_metadata.json");
        if (!Files.exists(metadataFile)) {
            return Collections.emptyMap();
        }

        Map<String, DocumentMetadata> metadataByFile = new LinkedHashMap<>();
        try (JsonReader reader = Json.createReader(Files.newBufferedReader(metadataFile, StandardCharsets.UTF_8))) {
            JsonArray array = reader.readArray();
            for (var value : array) {
                if (!(value instanceof JsonObject)) {
                    continue;
                }
                JsonObject obj = (JsonObject) value;
                String fileName = safeString(obj.getString("file", ""));
                if (fileName.isBlank()) {
                    continue;
                }
                String nomDocument = safeString(obj.getString("nomDocument", ""));
                String description = safeString(obj.getString("description", ""));
                metadataByFile.put(fileName.toLowerCase(Locale.ROOT), new DocumentMetadata(nomDocument, description));
            }
        } catch (Exception ignored) {
            return Collections.emptyMap();
        }

        return metadataByFile;
    }

    private boolean containsIgnoreCase(String value, String termUpper) {
        if (termUpper == null || termUpper.isBlank()) {
            return true;
        }
        if (value == null || value.isBlank()) {
            return false;
        }
        return value.toUpperCase(Locale.ROOT).contains(termUpper);
    }

    public String resolveDocumentOutcome(DocumentHit row) {
        if (row == null) {
            return "";
        }
        return safeString(row.getOutcome());
    }

    public String resolveDocumentParamName(DocumentHit row) {
        if (row == null) {
            return "";
        }

        if ("/consultation-archives".equals(row.getOutcome())) {
            return "globalSearchType";
        }
        return safeString(row.getParamName());
    }

    public String resolveDocumentParamValue(DocumentHit row) {
        if (row == null) {
            return "";
        }

        if (!"/consultation-archives".equals(row.getOutcome())) {
            return safeString(row.getParamValue());
        }

        String encoded = safeString(row.getParamValue());
        int sep = encoded.indexOf('|');
        if (sep < 0) {
            return "";
        }
        return encoded.substring(sep + 1);
    }

    public String resolveDocumentSearchType(DocumentHit row) {
        if (row == null || !"/consultation-archives".equals(row.getOutcome())) {
            return "";
        }

        String encoded = safeString(row.getParamValue());
        int sep = encoded.indexOf('|');
        if (sep < 0) {
            return "";
        }
        return encoded.substring(0, sep);
    }

    public long getTotalResults() {
        return dossierResults.size() + documentResults.size();
    }

    public boolean isEmptyResult() {
        return searched && getTotalResults() == 0L;
    }

    public void showDossiers() {
        selectedSection = "dossiers";
    }

    public void showDocuments() {
        selectedSection = "documents";
    }

    public boolean isDossiersSelected() {
        return "dossiers".equalsIgnoreCase(selectedSection);
    }

    public boolean isDocumentsSelected() {
        return "documents".equalsIgnoreCase(selectedSection);
    }

    public boolean isShowDossiersSection() {
        return searched && isDossiersSelected();
    }

    public boolean isShowDocumentsSection() {
        return searched && isDocumentsSelected();
    }

    public boolean isSelectedSectionEmpty() {
        if (isDossiersSelected()) {
            return dossierResults == null || dossierResults.isEmpty();
        }
        if (isDocumentsSelected()) {
            return documentResults == null || documentResults.isEmpty();
        }
        return true;
    }

    private void resetResults() {
        dossierResults = Collections.emptyList();
        documentResults = Collections.emptyList();
    }

    private void selectBestSection() {
        if (dossierResults != null && !dossierResults.isEmpty()) {
            selectedSection = "dossiers";
            return;
        }
        if (documentResults != null && !documentResults.isEmpty()) {
            selectedSection = "documents";
            return;
        }
        selectedSection = "dossiers";
    }

    private String resolveDocumentPathFilter() {
        String filiale = resolveSessionFiliale();
        if ("bank".equals(filiale)) {
            return "%BTK - BANK%";
        }
        if ("finance".equals(filiale)) {
            return "%BTK - FINANCE%";
        }
        return "%";
    }

    private ExtractedReference extractReferenceFromDossierName(String dossierName) {
        if (dossierName == null || dossierName.isBlank()) {
            return new ExtractedReference("", "");
        }

        String value = dossierName.trim();
        int colon = value.indexOf(':');
        if (colon >= 0 && colon < value.length() - 1) {
            value = value.substring(colon + 1).trim();
        }

        int lastDash = value.lastIndexOf('-');
        if (lastDash <= 0 || lastDash >= value.length() - 1) {
            return new ExtractedReference("", value);
        }

        String relation = value.substring(0, lastDash).trim();
        String pin = value.substring(lastDash + 1).trim();
        return new ExtractedReference(pin, relation);
    }

    private String readRequestParam(String name) {
        var context = jakarta.faces.context.FacesContext.getCurrentInstance();
        if (context == null || name == null || name.isBlank()) {
            return null;
        }
        return context.getExternalContext().getRequestParameterMap().get(name);
    }

    private static synchronized EntityManagerFactory getEMF() {
        if (emf == null || !emf.isOpen()) {
            emf = Persistence.createEntityManagerFactory("btk");
        }
        return emf;
    }

    private String resolveSessionFiliale() {
        return loginBean == null ? "" : loginBean.getCurrentFilialeCode();
    }

    private String resolveSessionLegacyFiliale() {
        return loginBean == null ? "" : loginBean.getCurrentFilialeId();
    }

    private String normalizeQuery(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() > 100) {
            return trimmed.substring(0, 100);
        }
        return trimmed;
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String safeString(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private Long parseLongOrNull(String value) {
        if (value == null) {
            return null;
        }
        String clean = value.trim();
        if (!clean.matches("\\d+")) {
            return null;
        }

        try {
            return Long.parseLong(clean);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Long toLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return null;
    }

    private Integer toInteger(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return null;
    }

    private Date toDate(Object value) {
        if (value instanceof Date) {
            return (Date) value;
        }
        if (value instanceof java.sql.Timestamp) {
            return new Date(((java.sql.Timestamp) value).getTime());
        }
        return null;
    }

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public String getNavbarQuery() {
        return navbarQuery;
    }

    public void setNavbarQuery(String navbarQuery) {
        this.navbarQuery = navbarQuery;
    }

    public boolean isSearched() {
        return searched;
    }

    public List<DossierHit> getDossierResults() {
        return dossierResults;
    }

    public List<DocumentHit> getDocumentResults() {
        return documentResults;
    }

    private static class ExtractedReference {
        final String pin;
        final String relation;

        ExtractedReference(String pin, String relation) {
            this.pin = pin == null ? "" : pin;
            this.relation = relation == null ? "" : relation;
        }
    }

    private static class DocumentMetadata {
        final String nomDocument;
        final String description;

        DocumentMetadata(String nomDocument, String description) {
            this.nomDocument = nomDocument == null ? "" : nomDocument;
            this.description = description == null ? "" : description;
        }
    }

    public static class DossierHit implements Serializable {
        private static final long serialVersionUID = 1L;

        private final Long idDossier;
        private final String pin;
        private final String relation;
        private final String portefeuille;
        private final String charge;
        private final String typeArchive;
        private final String filiale;
        private final String boites;

        DossierHit(Long idDossier, String pin, String relation, String portefeuille,
                   String charge, String typeArchive, String filiale, String boites) {
            this.idDossier = idDossier;
            this.pin = pin;
            this.relation = relation;
            this.portefeuille = portefeuille;
            this.charge = charge;
            this.typeArchive = typeArchive;
            this.filiale = filiale;
            this.boites = boites;
        }

        public Long getIdDossier() {
            return idDossier;
        }

        public String getPin() {
            return pin;
        }

        public String getRelation() {
            return relation;
        }

        public String getPortefeuille() {
            return portefeuille;
        }

        public String getCharge() {
            return charge;
        }

        public String getTypeArchive() {
            return typeArchive;
        }

        public String getFiliale() {
            return filiale;
        }

        public String getBoites() {
            return boites;
        }

        public boolean isActionAvailable() {
            return (pin != null && !pin.isBlank()) || (relation != null && !relation.isBlank());
        }

        public String getActionType() {
            if (pin != null && !pin.isBlank()) {
                return "pin";
            }
            return "relation";
        }

        public String getActionValue() {
            if (pin != null && !pin.isBlank()) {
                return pin;
            }
            return relation == null ? "" : relation;
        }
    }

    public static class DocumentHit implements Serializable {
        private static final long serialVersionUID = 1L;

        private final Long idDocument;
        private final String fileName;
        private final String documentName;
        private final String documentDescription;
        private final String dossierName;
        private final Integer boite;
        private final String utilisateur;
        private final Date dateCreation;
        private final String pin;
        private final String relation;
        private final String outcome;
        private final String paramName;
        private final String paramValue;

        DocumentHit(Long idDocument, String fileName, String documentName, String documentDescription,
                    String dossierName, Integer boite,
                    String utilisateur, Date dateCreation, String pin, String relation,
                    String outcome, String paramName, String paramValue) {
            this.idDocument = idDocument;
            this.fileName = fileName;
            this.documentName = documentName == null ? "" : documentName;
            this.documentDescription = documentDescription == null ? "" : documentDescription;
            this.dossierName = dossierName;
            this.boite = boite;
            this.utilisateur = utilisateur;
            this.dateCreation = dateCreation;
            this.pin = pin == null ? "" : pin;
            this.relation = relation == null ? "" : relation;
            this.outcome = outcome == null ? "" : outcome;
            this.paramName = paramName == null ? "" : paramName;
            this.paramValue = paramValue == null ? "" : paramValue;
        }

        public Long getIdDocument() {
            return idDocument;
        }

        public String getFileName() {
            return fileName;
        }

        public String getDocumentName() {
            return documentName;
        }

        public String getDocumentDescription() {
            return documentDescription;
        }

        public String getDossierName() {
            return dossierName;
        }

        public Integer getBoite() {
            return boite;
        }

        public String getUtilisateur() {
            return utilisateur;
        }

        public Date getDateCreation() {
            return dateCreation;
        }

        public String getPin() {
            return pin;
        }

        public String getRelation() {
            return relation;
        }

        public String getOutcome() {
            return outcome;
        }

        public String getParamName() {
            return paramName;
        }

        public String getParamValue() {
            return paramValue;
        }

        public boolean isActionAvailable() {
            return outcome != null && !outcome.isBlank() && paramValue != null && !paramValue.isBlank();
        }
    }
}
