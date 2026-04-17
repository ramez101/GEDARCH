package com.btk.bean;

import java.io.InputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;

import com.btk.model.ArchDossier;
import com.btk.util.DossierEmpUtil;
import com.btk.util.FilialeUtil;

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
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;
import jakarta.transaction.UserTransaction;
import org.primefaces.PrimeFaces;

@Named("ajoutArchivesBean")
@ViewScoped
public class AjoutArchivesBean implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final Path DOCUMENTS_ROOT = Paths.get("C:\\Documents_Archives");

    private static EntityManagerFactory emf;

    @Resource
    private UserTransaction utx;

    @Inject
    private LoginBean loginBean;

    private String searchType = "pin";
    private String searchPinValue;
    private String searchRelationValue;

    private Long idDossier;
    private String pin;
    private String relation;
    private String boite;
    private Integer boitePrincipale;
    private boolean dossierLoaded;

    private String docsPayload;
    private List<DocumentRow> existingDocuments = Collections.emptyList();
    private final Map<Long, PendingDelete> pendingDeletes = new LinkedHashMap<>();

    public void search() {
        clearResult();

        String value = resolveSearchValue();
        if (value == null || value.isBlank()) {
            FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_WARN, "Veuillez saisir un pin ou une relation.", null));
            return;
        }

        EntityManager em = getEMF().createEntityManager();
        try {
            boolean byRelation = "relation".equalsIgnoreCase(searchType);
            String field = byRelation ? "relation" : "pin";

            List<ArchDossier> rows = em.createQuery(
                            "select d from " + ArchDossier.class.getSimpleName() + " d " +
                                    "where upper(trim(d." + field + ")) = :val " +
                                    "and (lower(trim(d.filiale)) = :filiale " +
                                    "or (d.filiale is null and lower(trim(d.idFiliale)) = :legacyFiliale)) " +
                                    "order by d.idDossier",
                            ArchDossier.class)
                    .setParameter("val", normalize(value))
                    .setParameter("filiale", resolveSessionFiliale())
                    .setParameter("legacyFiliale", resolveSessionLegacyFiliale())
                    .setMaxResults(1)
                    .getResultList();

            if (rows.isEmpty()) {
                FacesContext.getCurrentInstance().addMessage(null,
                        new FacesMessage(FacesMessage.SEVERITY_WARN, "Dossier introuvable.", null));
                return;
            }

            ArchDossier row = rows.get(0);
            idDossier = row.getIdDossier();
            pin = row.getPin();
            relation = row.getRelation();
            boitePrincipale = DossierEmpUtil.findPrimaryBoite(em, idDossier);
            boite = DossierEmpUtil.findBoitesSummary(em, idDossier);
            dossierLoaded = true;

            loadExistingDocuments(em);
            PrimeFaces.current().executeScript("window.BTKUploadState = {docs: [], counter: 0}; if (window.btkRenderDocs) { window.btkRenderDocs(); }");
        } finally {
            em.close();
        }
    }

    public void confirm() {
        if (!dossierLoaded || idDossier == null) {
            FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_WARN, "Veuillez d'abord rechercher un dossier.", null));
            return;
        }

        EntityManager em = getEMF().createEntityManager();
        try {
            List<DocItem> docs = parseDocsPayload(resolveDocsPayload());
            boolean hasAdds = !docs.isEmpty();
            boolean hasDeletes = !pendingDeletes.isEmpty();

            if (!hasAdds && !hasDeletes) {
                FacesContext.getCurrentInstance().addMessage(null,
                        new FacesMessage(FacesMessage.SEVERITY_WARN, "Aucune modification a confirmer.", null));
                return;
            }

            Map<String, Part> parts = Collections.emptyMap();
            if (hasAdds) {
                parts = collectParts();
            }
            if (hasAdds && parts.isEmpty()) {
                FacesContext.getCurrentInstance().addMessage(null,
                        new FacesMessage(FacesMessage.SEVERITY_WARN, "Aucun fichier recu.", null));
                return;
            }

            utx.begin();
            em.joinTransaction();

            String dossierName = buildDossierName();
            Path dossierPath = resolveDossierPath(em, dossierName);
            Files.createDirectories(dossierPath);

            long nextDocId = fetchNextDocId(em);
            List<String> usedPartNames = new ArrayList<>();
            Map<String, DocMeta> metadata = readMetadataFile(dossierPath);
            int savedCount = 0;
            int deletedCount = 0;
            List<PendingDelete> deletedEntries = new ArrayList<>();

            if (hasDeletes) {
                for (PendingDelete pendingDelete : pendingDeletes.values()) {
                    int deleted = em.createNativeQuery("delete from ARCH_DOCUMENT where ID_DOCUMENT = :id")
                            .setParameter("id", pendingDelete.idDocument)
                            .executeUpdate();
                    if (deleted > 0) {
                        deletedCount += deleted;
                        deletedEntries.add(pendingDelete);
                        metadata.remove(pendingDelete.fileName.toLowerCase(Locale.ROOT));
                    }
                }
            }

            if (hasAdds) {
                for (DocItem doc : docs) {
                    Part part = resolvePart(doc, parts, usedPartNames);
                    if (part == null) {
                        throw new IllegalStateException("Fichier introuvable pour le document : " + doc.label);
                    }

                    String submitted = part.getSubmittedFileName();
                    String fileName = submitted == null || submitted.isBlank()
                            ? doc.file
                            : Paths.get(submitted).getFileName().toString();
                    fileName = sanitizeFileName(fileName);

                    Path target = dossierPath.resolve(fileName);
                    try (InputStream in = part.getInputStream()) {
                        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                    }

                    em.createNativeQuery(
                            "insert into ARCH_DOCUMENT (ID_DOCUMENT, NOM_DOSSIER, PATH_DOSSIER, DOCUMENTS, BOITE, UTILISATEUR_CREE, DATE_CREATION) " +
                                            "values (:id, :nom, :path, :docs, :boite, :user, SYSDATE)")
                            .setParameter("id", nextDocId++)
                            .setParameter("nom", dossierName)
                            .setParameter("path", dossierPath.toString())
                            .setParameter("docs", fileName)
                            .setParameter("boite", boitePrincipale)
                            .setParameter("user", resolveUtilisateur())
                            .executeUpdate();

                    metadata.put(fileName.toLowerCase(Locale.ROOT),
                            new DocMeta(doc.label, doc.copie, doc.nombre, doc.desc));
                    savedCount++;
                }
            }

            writeMetadataFile(dossierPath, metadata);
            utx.commit();

            for (PendingDelete pendingDelete : deletedEntries) {
                cleanupDeletedFile(pendingDelete);
            }

            docsPayload = null;
            pendingDeletes.clear();
            loadExistingDocuments(em);
            PrimeFaces.current().executeScript("window.BTKUploadState = {docs: [], counter: 0}; if (window.btkRenderDocs) { window.btkRenderDocs(); }");

            FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_INFO,
                            "Mise a jour terminee. Ajoutes: " + savedCount + " | Supprimes: " + deletedCount + ".", null));
        } catch (Exception e) {
            try {
                if (utx != null) {
                    utx.rollback();
                }
            } catch (Exception ignored) {}

            FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_ERROR,
                            "Erreur mise a jour documents : " + e.getMessage(), null));
        } finally {
            em.close();
        }
    }

    public void cancel() {
        docsPayload = null;
        pendingDeletes.clear();
        if (dossierLoaded && idDossier != null) {
            EntityManager em = getEMF().createEntityManager();
            try {
                loadExistingDocuments(em);
            } finally {
                em.close();
            }
        }
        PrimeFaces.current().executeScript("window.BTKUploadState = {docs: [], counter: 0}; if (window.btkRenderDocs) { window.btkRenderDocs(); }");
        FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO, "Modifications annulees.", null));
    }

    public void clear() {
        searchType = "pin";
        searchPinValue = null;
        searchRelationValue = null;
        clearResult();
        docsPayload = null;
        pendingDeletes.clear();
        PrimeFaces.current().executeScript("window.BTKUploadState = {docs: [], counter: 0}; if (window.btkRenderDocs) { window.btkRenderDocs(); }");
    }

    public void stageDeleteExistingDocument(DocumentRow doc) {
        if (doc == null || doc.getIdDocument() == null) {
            FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_WARN, "Document introuvable.", null));
            return;
        }
        if (pendingDeletes.containsKey(doc.getIdDocument())) {
            return;
        }

        pendingDeletes.put(doc.getIdDocument(),
                new PendingDelete(doc.getIdDocument(), doc.getDocument(), doc.getPath()));

        List<DocumentRow> remaining = new ArrayList<>();
        for (DocumentRow row : existingDocuments) {
            if (!doc.getIdDocument().equals(row.getIdDocument())) {
                remaining.add(row);
            }
        }
        existingDocuments = remaining;

        FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO,
                        "Document marque pour suppression. Cliquez Confirmer pour valider.", null));
    }

    private void cleanupDeletedFile(PendingDelete pendingDelete) {
        if (pendingDelete == null || pendingDelete.path == null || pendingDelete.path.isBlank()
                || pendingDelete.fileName == null || pendingDelete.fileName.isBlank()) {
            return;
        }

        Path dossierPath = Paths.get(pendingDelete.path);
        try {
            Files.deleteIfExists(dossierPath.resolve(pendingDelete.fileName));
        } catch (Exception ignored) {
            // Best effort on filesystem cleanup.
        }
        try {
            Map<String, DocMeta> metadata = readMetadataFile(dossierPath);
            metadata.remove(pendingDelete.fileName.toLowerCase(Locale.ROOT));
            writeMetadataFile(dossierPath, metadata);
        } catch (Exception ignored) {
            // Best effort on metadata cleanup.
        }
    }

    public List<String> completeRelation(String query) {
        if (!"relation".equalsIgnoreCase(searchType) || query == null || query.isBlank()) {
            return Collections.emptyList();
        }
        EntityManager em = getEMF().createEntityManager();
        try {
            return em.createQuery(
                            "select distinct d.relation from " + ArchDossier.class.getSimpleName() + " d " +
                                    "where upper(d.relation) like :q " +
                                    "and (lower(trim(d.filiale)) = :filiale " +
                                    "or (d.filiale is null and lower(trim(d.idFiliale)) = :legacyFiliale)) " +
                                    "order by d.relation",
                            String.class)
                    .setParameter("q", "%" + query.trim().toUpperCase(Locale.ROOT) + "%")
                    .setParameter("filiale", resolveSessionFiliale())
                    .setParameter("legacyFiliale", resolveSessionLegacyFiliale())
                    .setMaxResults(20)
                    .getResultList();
        } finally {
            em.close();
        }
    }

    private void loadExistingDocuments(EntityManager em) {
        String dossierName = buildDossierName();
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                        "select ID_DOCUMENT, DOCUMENTS, PATH_DOSSIER, UTILISATEUR_CREE, DATE_CREATION " +
                                "from ARCH_DOCUMENT " +
                                "where upper(NOM_DOSSIER) = :nom " +
                                "order by ID_DOCUMENT")
                .setParameter("nom", dossierName.toUpperCase(Locale.ROOT))
                .getResultList();

        if (rows.isEmpty()) {
            existingDocuments = Collections.emptyList();
            return;
        }

        String dossierPath = toStringValue(rows.get(0)[2]);
        Map<String, DocMeta> metadata = readMetadataFile(Paths.get(dossierPath));

        List<DocumentRow> docs = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            Long idDoc = row[0] instanceof Number ? ((Number) row[0]).longValue() : null;
            String fileName = toStringValue(row[1]);
            DocMeta meta = metadata.get(fileName.toLowerCase(Locale.ROOT));
            String nomDocument = meta == null || meta.nomDocument.isBlank() ? fileName : meta.nomDocument;

            docs.add(new DocumentRow(
                    idDoc,
                    fileName,
                    nomDocument,
                    meta == null ? "" : meta.copie,
                    meta == null ? "" : meta.nombre,
                    meta == null ? "" : meta.description,
                    toStringValue(row[2]),
                    toStringValue(row[3]),
                    toDateValue(row[4])
            ));
        }
        existingDocuments = docs;
    }

    private Path resolveDossierPath(EntityManager em, String dossierName) {
        @SuppressWarnings("unchecked")
        List<String> paths = em.createNativeQuery(
                        "select PATH_DOSSIER from ARCH_DOCUMENT where upper(NOM_DOSSIER) = :nom and PATH_DOSSIER is not null")
                .setParameter("nom", dossierName.toUpperCase(Locale.ROOT))
                .setMaxResults(1)
                .getResultList();
        if (!paths.isEmpty() && paths.get(0) != null && !paths.get(0).isBlank()) {
            return Paths.get(paths.get(0));
        }
        return DOCUMENTS_ROOT.resolve(dossierName);
    }

    private Map<String, DocMeta> readMetadataFile(Path dossierPath) {
        Path metadataFile = dossierPath.resolve("_metadata.json");
        if (!Files.exists(metadataFile)) {
            return new HashMap<>();
        }

        Map<String, DocMeta> map = new HashMap<>();
        try (JsonReader reader = Json.createReader(Files.newBufferedReader(metadataFile, StandardCharsets.UTF_8))) {
            JsonArray arr = reader.readArray();
            for (var value : arr) {
                if (!(value instanceof JsonObject)) {
                    continue;
                }
                JsonObject obj = (JsonObject) value;
                String file = obj.getString("file", "");
                if (file.isBlank()) {
                    continue;
                }
                map.put(file.toLowerCase(Locale.ROOT), new DocMeta(
                        obj.getString("nomDocument", ""),
                        obj.getString("copie", ""),
                        obj.getString("nombre", ""),
                        obj.getString("description", "")
                ));
            }
        } catch (Exception ignored) {
            return new HashMap<>();
        }
        return map;
    }

    private void writeMetadataFile(Path dossierPath, Map<String, DocMeta> metadata) throws Exception {
        var arrayBuilder = Json.createArrayBuilder();
        for (Entry<String, DocMeta> entry : metadata.entrySet()) {
            String file = entry.getKey();
            DocMeta meta = entry.getValue();
            arrayBuilder.add(Json.createObjectBuilder()
                    .add("file", file)
                    .add("nomDocument", safe(meta.nomDocument))
                    .add("copie", safe(meta.copie))
                    .add("nombre", safe(meta.nombre))
                    .add("description", safe(meta.description))
                    .build());
        }
        Files.writeString(dossierPath.resolve("_metadata.json"),
                arrayBuilder.build().toString(),
                StandardCharsets.UTF_8);
    }

    private Part resolvePart(DocItem doc, Map<String, Part> parts, List<String> usedPartNames) {
        if (doc.fileKey != null && !doc.fileKey.isBlank()) {
            Part keyedPart = parts.get(doc.fileKey);
            if (keyedPart != null && !usedPartNames.contains(doc.fileKey)) {
                usedPartNames.add(doc.fileKey);
                return keyedPart;
            }
        }

        if (doc.file != null && !doc.file.isBlank()) {
            for (Entry<String, Part> entry : parts.entrySet()) {
                String key = entry.getKey();
                if (usedPartNames.contains(key)) {
                    continue;
                }
                Part part = entry.getValue();
                String submitted = part.getSubmittedFileName();
                if (submitted == null || submitted.isBlank()) {
                    continue;
                }
                String submittedName = Paths.get(submitted).getFileName().toString();
                if (submittedName.equalsIgnoreCase(doc.file)) {
                    usedPartNames.add(key);
                    return part;
                }
            }
        }

        for (Entry<String, Part> entry : parts.entrySet()) {
            String key = entry.getKey();
            if (usedPartNames.contains(key)) {
                continue;
            }
            usedPartNames.add(key);
            return entry.getValue();
        }
        return null;
    }

    private Map<String, Part> collectParts() throws Exception {
        Map<String, Part> map = new HashMap<>();
        HttpServletRequest request = (HttpServletRequest) FacesContext.getCurrentInstance()
                .getExternalContext().getRequest();
        for (Part part : request.getParts()) {
            String submitted = part.getSubmittedFileName();
            if (submitted == null || submitted.isBlank() || part.getSize() <= 0) {
                continue;
            }
            map.put(part.getName(), part);
        }
        return map;
    }

    private String resolveDocsPayload() {
        HttpServletRequest request = (HttpServletRequest) FacesContext.getCurrentInstance()
                .getExternalContext().getRequest();
        String requestPayload = request.getParameter("docsPayload");
        if (requestPayload != null && !requestPayload.isBlank()) {
            return requestPayload;
        }
        try {
            Part payloadPart = request.getPart("docsPayload");
            if (payloadPart != null && payloadPart.getSize() > 0) {
                try (InputStream in = payloadPart.getInputStream()) {
                    String payload = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    if (!payload.isBlank()) {
                        return payload;
                    }
                }
            }
        } catch (Exception ignored) {
            // fallback below
        }
        return docsPayload;
    }

    private List<DocItem> parseDocsPayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return Collections.emptyList();
        }
        try (JsonReader reader = Json.createReader(new java.io.StringReader(payload))) {
            JsonArray arr = reader.readArray();
            List<DocItem> items = new ArrayList<>();
            for (var value : arr) {
                if (!(value instanceof JsonObject)) {
                    continue;
                }
                JsonObject obj = (JsonObject) value;
                items.add(new DocItem(
                        obj.getString("label", ""),
                        obj.getString("copie", ""),
                        obj.getString("nombre", ""),
                        obj.getString("desc", ""),
                        obj.getString("file", ""),
                        obj.getString("fileKey", "")
                ));
            }
            return items;
        }
    }

    private long fetchNextDocId(EntityManager em) {
        Object max = em.createNativeQuery("select max(id_document) from ARCH_DOCUMENT").getSingleResult();
        if (max instanceof Number) {
            return ((Number) max).longValue() + 1L;
        }
        return 1L;
    }

    private String resolveSearchValue() {
        return "relation".equalsIgnoreCase(searchType) ? searchRelationValue : searchPinValue;
    }

    private String normalize(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private String buildDossierName() {
        String relationValue = relation == null ? "" : relation.trim();
        String pinValue = pin == null ? "" : pin.trim();
        String base = "Dossier num " + idDossier + " : " + relationValue + "-" + pinValue;
        return sanitizeFolderName(base);
    }

    private String sanitizeFolderName(String value) {
        if (value == null) {
            return "Dossier";
        }
        return value.replaceAll("[\\\\/:*?\"<>|]", "-").trim();
    }

    private String sanitizeFileName(String value) {
        if (value == null || value.isBlank()) {
            return "document";
        }
        return value.replaceAll("[\\\\/:*?\"<>|]", "-").trim();
    }

    private String resolveUtilisateur() {
        if (loginBean != null && loginBean.getUtilisateur() != null) {
            String unix = loginBean.getUtilisateur().getUnix();
            if (unix != null && !unix.isBlank()) {
                return unix;
            }
        }
        return "unknown";
    }

    private String toStringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private Date toDateValue(Object value) {
        if (value instanceof Date) {
            return (Date) value;
        }
        if (value instanceof Timestamp) {
            return new Date(((Timestamp) value).getTime());
        }
        return null;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private void clearResult() {
        idDossier = null;
        pin = null;
        relation = null;
        boitePrincipale = null;
        boite = null;
        dossierLoaded = false;
        existingDocuments = Collections.emptyList();
        pendingDeletes.clear();
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

    public String getSearchPinValue() {
        return searchPinValue;
    }

    public void setSearchPinValue(String searchPinValue) {
        this.searchPinValue = searchPinValue;
    }

    public String getSearchRelationValue() {
        return searchRelationValue;
    }

    public void setSearchRelationValue(String searchRelationValue) {
        this.searchRelationValue = searchRelationValue;
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

    public String getBoite() {
        return boite;
    }

    public boolean isDossierLoaded() {
        return dossierLoaded;
    }

    public String getDocsPayload() {
        return docsPayload;
    }

    public void setDocsPayload(String docsPayload) {
        this.docsPayload = docsPayload;
    }

    public List<DocumentRow> getExistingDocuments() {
        return existingDocuments;
    }

    private String resolveSessionFiliale() {
        if (loginBean != null) {
            return loginBean.getCurrentFilialeCode();
        }
        return FilialeUtil.normalizeKey(null);
    }

    private String resolveSessionLegacyFiliale() {
        return loginBean == null ? "" : loginBean.getCurrentFilialeId();
    }

    public static class DocumentRow implements Serializable {
        private static final long serialVersionUID = 1L;

        private final Long idDocument;
        private final String document;
        private final String nomDocument;
        private final String copie;
        private final String nombre;
        private final String description;
        private final String path;
        private final String utilisateur;
        private final Date dateCreation;

        DocumentRow(Long idDocument, String document, String nomDocument, String copie, String nombre, String description,
                    String path, String utilisateur, Date dateCreation) {
            this.idDocument = idDocument;
            this.document = document;
            this.nomDocument = nomDocument;
            this.copie = copie;
            this.nombre = nombre;
            this.description = description;
            this.path = path;
            this.utilisateur = utilisateur;
            this.dateCreation = dateCreation;
        }

        public Long getIdDocument() {
            return idDocument;
        }

        public String getDocument() {
            return document;
        }

        public String getNomDocument() {
            return nomDocument;
        }

        public String getCopie() {
            return copie;
        }

        public String getNombre() {
            return nombre;
        }

        public String getDescription() {
            return description;
        }

        public String getPath() {
            return path;
        }

        public String getUtilisateur() {
            return utilisateur;
        }

        public Date getDateCreation() {
            return dateCreation;
        }
    }

    private static class PendingDelete implements Serializable {
        private static final long serialVersionUID = 1L;

        private final Long idDocument;
        private final String fileName;
        private final String path;

        PendingDelete(Long idDocument, String fileName, String path) {
            this.idDocument = idDocument;
            this.fileName = fileName == null ? "" : fileName;
            this.path = path == null ? "" : path;
        }
    }

    private static class DocItem {
        final String label;
        final String copie;
        final String nombre;
        final String desc;
        final String file;
        final String fileKey;

        DocItem(String label, String copie, String nombre, String desc, String file, String fileKey) {
            this.label = label;
            this.copie = copie;
            this.nombre = nombre;
            this.desc = desc;
            this.file = file;
            this.fileKey = fileKey;
        }
    }

    private static class DocMeta implements Serializable {
        private static final long serialVersionUID = 1L;

        private final String nomDocument;
        private final String copie;
        private final String nombre;
        private final String description;

        DocMeta(String nomDocument, String copie, String nombre, String description) {
            this.nomDocument = nomDocument == null ? "" : nomDocument;
            this.copie = copie == null ? "" : copie;
            this.nombre = nombre == null ? "" : nombre;
            this.description = description == null ? "" : description;
        }
    }
}
