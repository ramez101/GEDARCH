package com.btk.bean;

import java.io.InputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import com.btk.model.ArchDossier;
import com.btk.model.ArchEmplacement;
import com.btk.util.DossierEmpUtil;
import com.btk.util.FilialeUtil;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Named;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import jakarta.transaction.UserTransaction;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.inject.Inject;

@Named("ajoutDossiersArchivesBean")
@ViewScoped
public class AjoutDossiersArchivesBean implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final Path DOCUMENTS_ROOT = Paths.get("C:\\Documents_Archives");

    private static EntityManagerFactory emf;

    @Resource
    private UserTransaction utx;

    private Long nextDossierId;
    private Integer boite;
    private List<Integer> boites = Collections.emptyList();
    private List<Integer> selectedBoites = new ArrayList<>();
    private Integer boiteToRemove;
    private String docsPayload;

    @Inject
    private LoginBean loginBean;

    private String portefeuille;
    private String pin;
    private String relation;
    private String charge;
    private String typeArchive;
    private String filialeId;

    @PostConstruct
    public void init() {
        refreshNextDossierId();
        boites = fetchBoites();
    }

    public void save() {
        EntityManager em = getEMF().createEntityManager();
        boolean txStarted = false;
        try {
            String sessionFiliale = resolveSessionFiliale();
            String sessionLegacyFiliale = resolveSessionLegacyFiliale();
            List<Integer> boitesToSave = DossierEmpUtil.normalizeBoites(resolveBoitesToSave());
            if (boitesToSave.isEmpty()) {
                addWarn("Ajouter au moins une boite.");
                markValidationFailed();
                return;
            }

            if (pin != null && !pin.isBlank()) {
                Long existing = em.createQuery(
                                "select count(d) from " + ArchDossier.class.getSimpleName() + " d " +
                                        "where upper(trim(d.pin)) = :pin " +
                                        "and (lower(trim(d.filiale)) = :filiale " +
                                        "or (d.filiale is null and lower(trim(d.idFiliale)) = :legacyFiliale))",
                                Long.class)
                        .setParameter("pin", pin.trim().toUpperCase())
                        .setParameter("filiale", sessionFiliale)
                        .setParameter("legacyFiliale", sessionLegacyFiliale)
                        .getSingleResult();
                if (existing != null && existing > 0) {
                    addWarn("Pin deja utilise.");
                    markValidationFailed();
                    return;
                }
            }

            for (Integer boiteValue : boitesToSave) {
                if (!DossierEmpUtil.boiteExists(em, boiteValue, sessionFiliale)) {
                    addError("La boite " + boiteValue + " est introuvable.");
                    markValidationFailed();
                    return;
                }
            }

            utx.begin();
            txStarted = true;
            em.joinTransaction();

            ArchDossier dossier = new ArchDossier();
            dossier.setPortefeuille(portefeuille);
            dossier.setPin(pin);
            dossier.setRelation(relation);
            dossier.setCharge(charge);
            dossier.setTypeArchive(typeArchive);
            dossier.setIdFiliale(sessionLegacyFiliale);
            dossier.setFiliale(sessionFiliale);

            em.persist(dossier);
            em.flush();

            DossierEmpUtil.replaceBoites(em, dossier.getIdDossier(), dossier.getPin(), dossier.getRelation(), boitesToSave);

            Path dossierPath = prepareDossierDirectory(dossier);

            int docsSaved = saveDocuments(em, dossier, dossierPath, boitesToSave.get(0));

            utx.commit();
            txStarted = false;
            resetForm();
            addInfo("Dossier enregistre avec succes. Boites associees: "
                    + formatBoites(boitesToSave)
                    + ". Documents sauvegardes: " + docsSaved + ".");
        } catch (Exception e) {
            try {
                if (txStarted && utx != null) {
                    utx.rollback();
                }
            } catch (Exception ignored) {}
            addError("Erreur enregistrement : " + e.getMessage());
        } finally {
            em.close();
        }
    }

    private void resetForm() {
        portefeuille = null;
        pin = null;
        relation = null;
        charge = null;
        typeArchive = null;
        filialeId = null;

        boite = null;
        selectedBoites = new ArrayList<>();
        boiteToRemove = null;
        refreshNextDossierId();
        boites = fetchBoites();
        docsPayload = null;
    }

    private void refreshNextDossierId() {
        EntityManager em = getEMF().createEntityManager();
        try {
            Long maxId = em.createQuery(
                            "select max(d.idDossier) from " + ArchDossier.class.getSimpleName() + " d",
                            Long.class)
                    .getSingleResult();
            nextDossierId = (maxId == null) ? 1L : maxId + 1L;
        } finally {
            em.close();
        }
    }

    private List<Integer> fetchBoites() {
        EntityManager em = getEMF().createEntityManager();
        try {
            return DossierEmpUtil.findBoitesByFiliale(em, resolveSessionFiliale());
        } finally {
            em.close();
        }
    }

    public List<Integer> completeBoite(String query) {
        if (boites == null || boites.isEmpty()) {
            return Collections.emptyList();
        }

        String term = query == null ? "" : query.trim();
        if (term.isBlank()) {
            return boites;
        }

        List<Integer> result = new ArrayList<>();
        for (Integer item : boites) {
            if (item == null) {
                continue;
            }
            if (String.valueOf(item).contains(term)) {
                result.add(item);
            }
        }
        return result;
    }

    public void addBoiteSelection() {
        if (boite == null) {
            addWarn("Saisir un numero de boite avant d'ajouter.");
            markValidationFailed();
            return;
        }

        if (boites == null || !boites.contains(boite)) {
            addError("La boite " + boite + " est introuvable.");
            markValidationFailed();
            return;
        }

        if (selectedBoites.contains(boite)) {
            addWarn("La boite " + boite + " est deja associee.");
            boite = null;
            markValidationFailed();
            return;
        }

        selectedBoites.add(boite);
        addInfo("Boite " + boite + " ajoutee.");
        boite = null;
    }

    public void removeBoiteSelection() {
        if (boiteToRemove == null) {
            return;
        }

        selectedBoites.remove(boiteToRemove);
        addInfo("Boite " + boiteToRemove + " retiree.");
        boiteToRemove = null;
    }

    private static synchronized EntityManagerFactory getEMF() {
        if (emf == null || !emf.isOpen()) {
            emf = Persistence.createEntityManagerFactory("btk");
        }
        return emf;
    }

    public Integer getBoite() { return boite; }
    public void setBoite(Integer boite) { this.boite = boite; }

    public Long getNextDossierId() { return nextDossierId; }
    public List<Integer> getBoites() { return boites; }
    public List<Integer> getSelectedBoites() { return selectedBoites; }
    public Integer getBoiteToRemove() { return boiteToRemove; }
    public void setBoiteToRemove(Integer boiteToRemove) { this.boiteToRemove = boiteToRemove; }
    public String getBoitesSummary() { return DossierEmpUtil.formatBoites(resolveBoitesToSave()); }

    public String getDocsPayload() { return docsPayload; }
    public void setDocsPayload(String docsPayload) { this.docsPayload = docsPayload; }

    private int saveDocuments(EntityManager em, ArchDossier dossier, Path dossierPath, Integer boitePrincipale) throws Exception {
        String payload = resolveDocsPayload();
        List<DocItem> docs = parseDocsPayload(payload);
        Map<String, Part> parts = collectParts();

        if (docs.isEmpty() && parts.isEmpty()) {
            return 0;
        }
        if (docs.isEmpty()) {
            docs = buildDocsFromParts(parts);
        }

        String dossierName = buildDossierName(dossier);
        if (parts.isEmpty()) {
            throw new IllegalStateException("Aucun fichier n'a ete recu par le serveur.");
        }
        long nextDocId = fetchNextDocId(em);
        int savedCount = 0;
        List<String> usedPartNames = new ArrayList<>();
        List<DocItem> savedDocsMeta = new ArrayList<>();

        for (DocItem doc : docs) {
            Part part = resolvePart(doc, parts, usedPartNames);
            String fileName = doc.file;
            if (part == null) {
                throw new IllegalStateException("Fichier introuvable pour le document : " + doc.label);
            }

            String submitted = part.getSubmittedFileName();
            if (submitted != null && !submitted.isBlank()) {
                fileName = Paths.get(submitted).getFileName().toString();
            }

            Path target = dossierPath.resolve(sanitizeFileName(fileName));
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
            savedDocsMeta.add(new DocItem(
                    doc.label,
                    doc.copie,
                    doc.nombre,
                    doc.desc,
                    fileName,
                    doc.fileKey
            ));
            savedCount++;
        }
        writeMetadataFile(dossierPath, savedDocsMeta);

        return savedCount;
    }

    private Path prepareDossierDirectory(ArchDossier dossier) throws Exception {
        Files.createDirectories(DOCUMENTS_ROOT);
        Path dossierPath = DOCUMENTS_ROOT.resolve(buildDossierName(dossier));
        Files.createDirectories(dossierPath);
        return dossierPath;
    }

    private void writeMetadataFile(Path dossierPath, List<DocItem> docs) throws Exception {
        var arrayBuilder = Json.createArrayBuilder();
        for (DocItem doc : docs) {
            arrayBuilder.add(Json.createObjectBuilder()
                    .add("file", safeJsonValue(doc.file))
                    .add("nomDocument", safeJsonValue(doc.label))
                    .add("copie", safeJsonValue(doc.copie))
                    .add("nombre", safeJsonValue(doc.nombre))
                    .add("description", safeJsonValue(doc.desc))
                    .build());
        }
        Files.writeString(dossierPath.resolve("_metadata.json"),
                arrayBuilder.build().toString(),
                StandardCharsets.UTF_8);
    }

    private String safeJsonValue(String value) {
        return value == null ? "" : value;
    }

    private List<DocItem> buildDocsFromParts(Map<String, Part> parts) {
        if (parts.isEmpty()) {
            return Collections.emptyList();
        }

        List<Entry<String, Part>> entries = new ArrayList<>(parts.entrySet());
        entries.sort(Comparator.comparing(Entry::getKey));

        List<DocItem> docs = new ArrayList<>(entries.size());
        for (Entry<String, Part> entry : entries) {
            Part part = entry.getValue();
            String fileName = part.getSubmittedFileName();
            if (fileName != null && !fileName.isBlank()) {
                fileName = Paths.get(fileName).getFileName().toString();
            } else {
                fileName = entry.getKey();
            }

            docs.add(new DocItem(fileName, "", "", "", fileName, entry.getKey()));
        }

        return docs;
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
                    String partPayload = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    if (!partPayload.isBlank()) {
                        return partPayload;
                    }
                }
            }
        } catch (Exception ignored) {
            // Fallback to the JSF-bound value below.
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

    private long fetchNextDocId(EntityManager em) {
        Object max = em.createNativeQuery("select max(id_document) from ARCH_DOCUMENT").getSingleResult();
        if (max instanceof Number) {
            return ((Number) max).longValue() + 1L;
        }
        return 1L;
    }

    private String buildDossierName(ArchDossier dossier) {
        String relationValue = dossier.getRelation() == null ? "" : dossier.getRelation().trim();
        String pinValue = dossier.getPin() == null ? "" : dossier.getPin().trim();
        String base = "Dossier num " + dossier.getIdDossier() + " : " + relationValue + "-" + pinValue;
        return sanitizeFolderName(base);
    }

    private String sanitizeFolderName(String value) {
        if (value == null) {
            return "Dossier";
        }
        return value.replaceAll("[\\\\/:*?\"<>|]", "-").trim();
    }

    private String sanitizeFileName(String value) {
        if (value == null) {
            return "document";
        }
        return value.replaceAll("[\\\\/:*?\"<>|]", "-").trim();
    }

    private List<Integer> resolveBoitesToSave() {
        List<Integer> resolved = new ArrayList<>(selectedBoites);
        if (boite != null && !resolved.contains(boite)) {
            resolved.add(boite);
        }
        return resolved;
    }

    private String formatBoites(List<Integer> dossierBoites) {
        return DossierEmpUtil.formatBoites(dossierBoites);
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

    private void markValidationFailed() {
        FacesContext.getCurrentInstance().validationFailed();
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

    public String getPortefeuille() { return portefeuille; }
    public void setPortefeuille(String portefeuille) { this.portefeuille = portefeuille; }

    public String getPin() { return pin; }
    public void setPin(String pin) { this.pin = pin; }

    public String getRelation() { return relation; }
    public void setRelation(String relation) { this.relation = relation; }

    public String getCharge() { return charge; }
    public void setCharge(String charge) { this.charge = charge; }

    public String getTypeArchive() { return typeArchive; }
    public void setTypeArchive(String typeArchive) { this.typeArchive = typeArchive; }

    public String getFilialeId() { return filialeId; }
    public void setFilialeId(String filialeId) { this.filialeId = filialeId; }

    public String getFilialeLabel() { return FilialeUtil.toLabel(resolveSessionFiliale()); }

    private String resolveSessionFiliale() {
        if (loginBean != null) {
            return loginBean.getCurrentFilialeCode();
        }
        return FilialeUtil.normalizeKey(filialeId);
    }

    private String resolveSessionLegacyFiliale() {
        return FilialeUtil.toLegacyId(resolveSessionFiliale());
    }

}

