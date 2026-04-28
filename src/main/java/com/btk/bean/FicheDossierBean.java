package com.btk.bean;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.primefaces.PrimeFaces;

import com.btk.model.ArchDossier;
import com.btk.model.ArchEmplacement;
import com.btk.util.DossierEmpUtil;
import com.btk.util.FilialeUtil;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;

import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.ExternalContext;
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
import jakarta.persistence.TypedQuery;

@Named("ficheDossierBean")
@ViewScoped
public class FicheDossierBean implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final String RELATION_SUGGESTION_SEPARATOR = " | ";

    private static EntityManagerFactory emf;

    @Inject
    private LoginBean loginBean;

    private String searchType = "pin";
    private String searchValue;
    private String searchPinValue;
    private String searchRelationValue;

    private Long idDossier;
    private String portefeuille;
    private String pin;
    private String relation;
    private String charge;
    private String typeArchive;
    private String filiale;
    private Integer etage;
    private Integer salle;
    private Integer rayon;
    private Integer rangee;
    private String boite;
    private boolean resultLoaded;
    private List<FicheDocumentRow> documents = Collections.emptyList();

    public void search() {
        clearResult();

        String effectiveValue = resolveSearchValue();
        if (effectiveValue == null || effectiveValue.isBlank()) {
            FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_WARN, "Veuillez saisir un PIN ou une relation.", null));
            return;
        }

        EntityManager em = getEMF().createEntityManager();
        try {
            boolean byRelation = "relation".equalsIgnoreCase(searchType);
            String field = byRelation ? "relation" : "pin";

            TypedQuery<ArchDossier> query = em.createQuery(
                    "select d from " + ArchDossier.class.getSimpleName() + " d " +
                            "where upper(trim(d." + field + ")) = :searchValue " +
                            "and (lower(trim(d.filiale)) = :filiale " +
                            "or (d.filiale is null and lower(trim(d.idFiliale)) = :legacyFiliale)) " +
                            "order by d.idDossier",
                    ArchDossier.class);
            query.setParameter("searchValue", normalizeSearchValue(effectiveValue));
            query.setParameter("filiale", resolveSessionFiliale());
            query.setParameter("legacyFiliale", resolveSessionLegacyFiliale());
            query.setMaxResults(1);

            List<ArchDossier> rows = query.getResultList();
            if (rows.isEmpty()) {
                PrimeFaces.current().executeScript("PF('ficheNotFoundDialog').show()");
                return;
            }

            ArchDossier row = rows.get(0);
            idDossier = row.getIdDossier();
            portefeuille = row.getPortefeuille();
            pin = row.getPin();
            relation = row.getRelation();
            charge = row.getCharge();
            typeArchive = toTypeArchiveLabel(row.getTypeArchive());
            filiale = toFilialeLabel(resolveFilialeValue(row));

            ArchEmplacement emplacement = DossierEmpUtil.findPrimaryEmplacement(em, row);
            etage = emplacement == null ? null : emplacement.getEtage();
            salle = emplacement == null ? null : emplacement.getSalle();
            rayon = emplacement == null ? null : emplacement.getRayon();
            rangee = emplacement == null ? null : emplacement.getRangee();
            boite = DossierEmpUtil.findBoitesSummary(em, idDossier);
            resultLoaded = true;

            loadDocumentsForCurrentDossier(em);
            PrimeFaces.current().executeScript("PF('ficheNotFoundDialog').hide()");
        } finally {
            em.close();
        }
    }

    public void clear() {
        searchType = "pin";
        searchValue = null;
        searchPinValue = null;
        searchRelationValue = null;
        clearResult();
        PrimeFaces.current().executeScript("PF('ficheNotFoundDialog').hide()");
    }

    public List<String> completeRelation(String query) {
        if (!"relation".equalsIgnoreCase(searchType) || query == null || query.isBlank()) {
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

    public void downloadPdf() {
        if (!resultLoaded || idDossier == null) {
            FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_WARN, "Veuillez d'abord rechercher un dossier.", null));
            return;
        }

        FacesContext ctx = FacesContext.getCurrentInstance();
        ExternalContext ext = ctx.getExternalContext();

        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            buildFichePdf(baos, ext);
            byte[] pdf = baos.toByteArray();

            ext.responseReset();
            ext.setResponseContentType("application/pdf");
            ext.setResponseContentLength(pdf.length);
            ext.setResponseHeader("Content-Disposition",
                    "attachment; filename=\"fiche_dossier_" + idDossier + ".pdf\"");

            try (OutputStream out = ext.getResponseOutputStream()) {
                out.write(pdf);
                out.flush();
            }
            ctx.responseComplete();
        } catch (Exception e) {
            FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_ERROR,
                            "Erreur generation PDF : " + e.getMessage(), null));
        }
    }

    private void buildFichePdf(OutputStream output, ExternalContext ext) throws Exception {
        Color deepBlue = new Color(11, 47, 69);
        Color blue = new Color(0, 123, 174);
        Color lightBlue = new Color(235, 244, 252);

        Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18, deepBlue);
        Font sectionFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, Color.WHITE);
        Font labelFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, deepBlue);
        Font valueFont = FontFactory.getFont(FontFactory.HELVETICA, 10, Color.BLACK);
        Font tableHeaderFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, Color.WHITE);
        Font tableCellFont = FontFactory.getFont(FontFactory.HELVETICA, 9, Color.BLACK);

        Document document = new Document(PageSize.A4);
        PdfWriter.getInstance(document, output);
        document.open();

        PdfPTable header = new PdfPTable(new float[]{1.4f, 4.6f});
        header.setWidthPercentage(100);

        PdfPCell logoCell = new PdfPCell();
        logoCell.setBorder(Rectangle.NO_BORDER);
        logoCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        try (InputStream logoStream = ext.getResourceAsStream("/resources/images/btk.png")) {
            if (logoStream != null) {
                Image logo = Image.getInstance(logoStream.readAllBytes());
                logo.scaleToFit(95, 55);
                logoCell.addElement(logo);
            }
        }
        header.addCell(logoCell);

        PdfPCell titleCell = new PdfPCell();
        titleCell.setBorder(Rectangle.NO_BORDER);
        titleCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        titleCell.addElement(new Paragraph("Fiche Dossier", titleFont));
        titleCell.addElement(new Paragraph("Banque Tuniso-Koweitienne", FontFactory.getFont(FontFactory.HELVETICA, 10, blue)));
        header.addCell(titleCell);
        document.add(header);

        document.add(new Paragraph(" "));

        PdfPTable infoTitle = new PdfPTable(1);
        infoTitle.setWidthPercentage(100);
        PdfPCell infoTitleCell = new PdfPCell(new Phrase("Informations dossier", sectionFont));
        infoTitleCell.setBackgroundColor(blue);
        infoTitleCell.setPadding(8);
        infoTitleCell.setBorderColor(lightBlue);
        infoTitle.addCell(infoTitleCell);
        document.add(infoTitle);

        PdfPTable infoTable = new PdfPTable(new float[]{1.3f, 1.7f, 1.3f, 1.7f});
        infoTable.setWidthPercentage(100);
        infoTable.setSpacingBefore(6);
        addInfoPair(infoTable, "Portefeuille", portefeuille, labelFont, valueFont, lightBlue);
        addInfoPair(infoTable, "Pin", pin, labelFont, valueFont, lightBlue);
        addInfoPair(infoTable, "Relation", relation, labelFont, valueFont, lightBlue);
        addInfoPair(infoTable, "Charge", charge, labelFont, valueFont, lightBlue);
        addInfoPair(infoTable, "Type d'archive", typeArchive, labelFont, valueFont, lightBlue);
        addInfoPair(infoTable, "Filiale", filiale, labelFont, valueFont, lightBlue);
        addInfoPair(infoTable, "Boîtes", safe(boite), labelFont, valueFont, lightBlue);
        document.add(infoTable);

        document.add(new Paragraph(" "));

        PdfPTable docsTitle = new PdfPTable(1);
        docsTitle.setWidthPercentage(100);
        PdfPCell docsTitleCell = new PdfPCell(new Phrase("Documents", sectionFont));
        docsTitleCell.setBackgroundColor(blue);
        docsTitleCell.setPadding(8);
        docsTitleCell.setBorderColor(lightBlue);
        docsTitle.addCell(docsTitleCell);
        document.add(docsTitle);

        PdfPTable docsTable = new PdfPTable(new float[]{2.3f, 1.0f, 1.0f, 2.8f, 2.2f});
        docsTable.setWidthPercentage(100);
        docsTable.setSpacingBefore(6);
        addHeaderCell(docsTable, "Nom document", tableHeaderFont, deepBlue);
        addHeaderCell(docsTable, "Copie", tableHeaderFont, deepBlue);
        addHeaderCell(docsTable, "Nombre", tableHeaderFont, deepBlue);
        addHeaderCell(docsTable, "Description", tableHeaderFont, deepBlue);
        addHeaderCell(docsTable, "Fichier", tableHeaderFont, deepBlue);

        if (documents == null || documents.isEmpty()) {
            PdfPCell emptyCell = new PdfPCell(new Phrase("Aucun document trouve pour ce dossier.", tableCellFont));
            emptyCell.setColspan(5);
            emptyCell.setPadding(7);
            docsTable.addCell(emptyCell);
        } else {
            for (FicheDocumentRow doc : documents) {
                addDataCell(docsTable, doc.getNomDocument(), tableCellFont);
                addDataCell(docsTable, doc.getCopie(), tableCellFont);
                addDataCell(docsTable, doc.getNombre(), tableCellFont);
                addDataCell(docsTable, doc.getDescription(), tableCellFont);
                addDataCell(docsTable, doc.getFichier(), tableCellFont);
            }
        }
        document.add(docsTable);

        document.close();
    }

    private void addInfoPair(PdfPTable table, String label, String value, Font labelFont, Font valueFont, Color bg) {
        PdfPCell labelCell = new PdfPCell(new Phrase(safe(label), labelFont));
        labelCell.setBackgroundColor(bg);
        labelCell.setPadding(6);
        table.addCell(labelCell);

        PdfPCell valueCell = new PdfPCell(new Phrase(safe(value), valueFont));
        valueCell.setPadding(6);
        table.addCell(valueCell);
    }

    private void addHeaderCell(PdfPTable table, String value, Font font, Color bg) {
        PdfPCell cell = new PdfPCell(new Phrase(value, font));
        cell.setBackgroundColor(bg);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setPadding(6);
        table.addCell(cell);
    }

    private void addDataCell(PdfPTable table, String value, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(safe(value), font));
        cell.setPadding(6);
        table.addCell(cell);
    }

    private void loadDocumentsForCurrentDossier(EntityManager em) {
        documents = Collections.emptyList();
        if (!resultLoaded || idDossier == null) {
            return;
        }

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                        "select DOCUMENTS, NOM_DOSSIER, PATH_DOSSIER " +
                                "from ARCH_DOCUMENT " +
                                "where upper(NOM_DOSSIER) = :nom " +
                                "order by ID_DOCUMENT")
                .setParameter("nom", buildDossierName().toUpperCase(Locale.ROOT))
                .getResultList();

        if (rows.isEmpty()) {
            return;
        }

        String dossierPath = toStringValue(rows.get(0)[2]);
        Map<String, DocMeta> metadataByFile = readMetadata(Paths.get(dossierPath));

        List<FicheDocumentRow> loaded = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            String fileName = toStringValue(row[0]);
            DocMeta meta = metadataByFile.get(fileName.toLowerCase(Locale.ROOT));
            String nomDocument = meta == null || meta.nomDocument.isBlank() ? fileName : meta.nomDocument;
            loaded.add(new FicheDocumentRow(
                    nomDocument,
                    meta == null || meta.copie.isBlank() ? "-" : meta.copie,
                    meta == null || meta.nombre.isBlank() ? "-" : meta.nombre,
                    meta == null || meta.description.isBlank() ? "-" : meta.description,
                    fileName));
        }
        documents = loaded;
    }

    private Map<String, DocMeta> readMetadata(Path dossierPath) {
        if (dossierPath == null) {
            return Collections.emptyMap();
        }
        Path metadataFile = dossierPath.resolve("_metadata.json");
        if (!Files.exists(metadataFile)) {
            return Collections.emptyMap();
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
            return Collections.emptyMap();
        }
        return map;
    }

    private String resolveSearchValue() {
        if ("relation".equalsIgnoreCase(searchType)) {
            searchValue = extractRelationSearchTerm(searchRelationValue);
        } else {
            searchValue = searchPinValue;
        }
        return searchValue;
    }

    private String extractRelationSearchTerm(String value) {
        if (value == null) {
            return null;
        }
        String clean = value.trim();
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
        String cleanPin = pinValue == null ? "" : pinValue.trim();
        String cleanRelation = relationValue == null ? "" : relationValue.trim();
        if (cleanPin.isBlank()) {
            return cleanRelation;
        }
        return cleanPin + RELATION_SUGGESTION_SEPARATOR + cleanRelation;
    }

    private void clearResult() {
        idDossier = null;
        portefeuille = null;
        pin = null;
        relation = null;
        charge = null;
        typeArchive = null;
        filiale = null;
        etage = null;
        salle = null;
        rayon = null;
        rangee = null;
        boite = null;
        resultLoaded = false;
        documents = Collections.emptyList();
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

    private String normalizeSearchValue(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private String toFilialeLabel(String filialeId) {
        return FilialeUtil.toLabel(filialeId);
    }

    private String toTypeArchiveLabel(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        if ("courant".equalsIgnoreCase(value)) {
            return "Courant";
        }
        if ("intermediaire".equalsIgnoreCase(value)) {
            return "Intermédiaire";
        }
        if ("finale".equalsIgnoreCase(value)) {
            return "Finale";
        }
        return value;
    }

    private String toStringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String safe(String value) {
        return value == null ? "" : value;
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

    public String getPortefeuille() {
        return portefeuille;
    }

    public String getPin() {
        return pin;
    }

    public String getRelation() {
        return relation;
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

    public Integer getEtage() {
        return etage;
    }

    public Integer getSalle() {
        return salle;
    }

    public Integer getRayon() {
        return rayon;
    }

    public Integer getRangee() {
        return rangee;
    }

    public String getBoite() {
        return boite;
    }

    public boolean isResultLoaded() {
        return resultLoaded;
    }

    public List<FicheDocumentRow> getDocuments() {
        return documents;
    }

    private String resolveFilialeValue(ArchDossier dossier) {
        if (dossier == null) {
            return "";
        }
        String value = dossier.getFiliale();
        if (value == null || value.isBlank()) {
            value = dossier.getIdFiliale();
        }
        return value;
    }

    private String resolveSessionFiliale() {
        return loginBean == null ? "" : loginBean.getCurrentFilialeCode();
    }

    private String resolveSessionLegacyFiliale() {
        return loginBean == null ? "" : loginBean.getCurrentFilialeId();
    }

    public static class FicheDocumentRow implements Serializable {
        private static final long serialVersionUID = 1L;

        private final String nomDocument;
        private final String copie;
        private final String nombre;
        private final String description;
        private final String fichier;

        FicheDocumentRow(String nomDocument, String copie, String nombre, String description, String fichier) {
            this.nomDocument = nomDocument;
            this.copie = copie;
            this.nombre = nombre;
            this.description = description;
            this.fichier = fichier;
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

        public String getFichier() {
            return fichier;
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
