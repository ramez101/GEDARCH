package com.btk.bean;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.btk.model.ArchDossier;
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
import jakarta.annotation.PostConstruct;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.ExternalContext;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;

@Named("listeArchivesBean")
@ViewScoped
public class ListeArchivesBean implements Serializable {

    private static final long serialVersionUID = 1L;
    private static EntityManagerFactory emf;

    @Inject
    private LoginBean loginBean;

    private List<ArchiveRow> archives = Collections.emptyList();

    @PostConstruct
    public void init() {
        loadArchives();
    }

    public void reload() {
        loadArchives();
    }

    public void downloadPdf() {
        FacesContext ctx = FacesContext.getCurrentInstance();
        ExternalContext ext = ctx.getExternalContext();

        if (archives == null || archives.isEmpty()) {
            loadArchives();
        }

        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            buildArchivesPdf(baos, ext);
            byte[] pdf = baos.toByteArray();

            String dateSuffix = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
            ext.responseReset();
            ext.setResponseContentType("application/pdf");
            ext.setResponseContentLength(pdf.length);
            ext.setResponseHeader("Content-Disposition",
                    "attachment; filename=\"liste_archives_" + dateSuffix + ".pdf\"");

            try (OutputStream out = ext.getResponseOutputStream()) {
                out.write(pdf);
                out.flush();
            }
            ctx.responseComplete();
        } catch (Exception e) {
            ctx.addMessage(null, new FacesMessage(FacesMessage.SEVERITY_ERROR,
                    "Erreur export PDF : " + e.getMessage(), null));
        }
    }

    private void loadArchives() {
        EntityManager em = getEMF().createEntityManager();
        try {
            @SuppressWarnings("unchecked")
            List<String> dossierNamesWithDocsRaw = em.createNativeQuery(
                            "select distinct upper(NOM_DOSSIER) from ARCH_DOCUMENT where NOM_DOSSIER is not null")
                    .getResultList();
            Set<String> dossierNamesWithDocs = new HashSet<>();
            for (String name : dossierNamesWithDocsRaw) {
                if (name != null) {
                    dossierNamesWithDocs.add(name);
                }
            }

            List<ArchDossier> rows = em.createQuery(
                            "select d from " + ArchDossier.class.getSimpleName() + " d " +
                                    "where (lower(trim(d.filiale)) = :filiale " +
                                    "or (d.filiale is null and lower(trim(d.idFiliale)) = :legacyFiliale)) " +
                                    "order by d.idDossier desc",
                            ArchDossier.class)
                    .setParameter("filiale", resolveSessionFiliale())
                    .setParameter("legacyFiliale", resolveSessionLegacyFiliale())
                    .getResultList();

            List<Long> dossierIds = new ArrayList<>(rows.size());
            for (ArchDossier row : rows) {
                if (row.getIdDossier() != null) {
                    dossierIds.add(row.getIdDossier());
                }
            }
            var boitesByDossier = DossierEmpUtil.findBoitesByDossierIds(em, dossierIds);

            List<ArchiveRow> loaded = new ArrayList<>(rows.size());
            for (ArchDossier row : rows) {
                Long idDossier = row.getIdDossier();
                String portefeuille = toStringValue(row.getPortefeuille());
                String pin = toStringValue(row.getPin());
                String relation = toStringValue(row.getRelation());
                String charge = toStringValue(row.getCharge());
                String typeArchive = toTypeArchiveLabel(toStringValue(row.getTypeArchive()));
                String filiale = toFilialeLabel(resolveFilialeValue(row));
                String boite = DossierEmpUtil.formatBoites(boitesByDossier.get(idDossier));

                String dossierName = buildDossierName(idDossier, relation, pin).toUpperCase(Locale.ROOT);
                boolean hasDocuments = dossierNamesWithDocs.contains(dossierName);
                String documents = hasDocuments ? "Oui" : "Non";

                loaded.add(new ArchiveRow(
                        idDossier, portefeuille, pin, relation, charge, typeArchive, filiale, boite, documents
                ));
            }
            archives = loaded;
        } finally {
            em.close();
        }
    }

    private void buildArchivesPdf(OutputStream output, ExternalContext ext) throws Exception {
        Color deepBlue = new Color(11, 47, 69);
        Color blue = new Color(0, 123, 174);
        Color lightBlue = new Color(235, 244, 252);
        Color rowAlt = new Color(248, 252, 255);

        Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18, deepBlue);
        Font subtitleFont = FontFactory.getFont(FontFactory.HELVETICA, 10, blue);
        Font infoFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, deepBlue);
        Font tableHeaderFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, Color.WHITE);
        Font tableCellFont = FontFactory.getFont(FontFactory.HELVETICA, 8, Color.BLACK);
        Font emptyFont = FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 9, new Color(90, 110, 130));

        Document document = new Document(PageSize.A4.rotate(), 22, 22, 20, 20);
        PdfWriter.getInstance(document, output);
        document.open();

        PdfPTable header = new PdfPTable(new float[]{1.3f, 5.7f});
        header.setWidthPercentage(100);

        PdfPCell logoCell = new PdfPCell();
        logoCell.setBorder(Rectangle.NO_BORDER);
        logoCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        try (InputStream logoStream = ext.getResourceAsStream("/resources/images/btk.png")) {
            if (logoStream != null) {
                Image logo = Image.getInstance(logoStream.readAllBytes());
                logo.scaleToFit(105, 60);
                logoCell.addElement(logo);
            }
        }
        header.addCell(logoCell);

        PdfPCell titleCell = new PdfPCell();
        titleCell.setBorder(Rectangle.NO_BORDER);
        titleCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        titleCell.addElement(new Paragraph("Liste des Archives", titleFont));
        titleCell.addElement(new Paragraph("Banque Tuniso-Koweitienne", subtitleFont));
        header.addCell(titleCell);
        document.add(header);

        document.add(new Paragraph(" "));

        PdfPTable infoTable = new PdfPTable(new float[]{1.2f, 2.2f, 1.2f, 2.2f});
        infoTable.setWidthPercentage(100);
        infoTable.setSpacingAfter(8);
        addInfoCell(infoTable, "Date export", infoFont, lightBlue);
        addInfoCell(infoTable, LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")), tableCellFont, Color.WHITE);
        addInfoCell(infoTable, "Total dossiers", infoFont, lightBlue);
        addInfoCell(infoTable, String.valueOf(archives == null ? 0 : archives.size()), tableCellFont, Color.WHITE);
        document.add(infoTable);

        PdfPTable table = new PdfPTable(new float[]{0.45f, 1.8f, 0.9f, 1.5f, 1.2f, 1.1f, 1.2f, 0.8f, 0.8f});
        table.setWidthPercentage(100);
        table.setHeaderRows(1);

        addHeaderCell(table, "#", tableHeaderFont, deepBlue);
        addHeaderCell(table, "Portefeuille", tableHeaderFont, deepBlue);
        addHeaderCell(table, "Pin", tableHeaderFont, deepBlue);
        addHeaderCell(table, "Relation", tableHeaderFont, deepBlue);
        addHeaderCell(table, "Charge", tableHeaderFont, deepBlue);
        addHeaderCell(table, "Type archive", tableHeaderFont, deepBlue);
        addHeaderCell(table, "Filiale", tableHeaderFont, deepBlue);
        addHeaderCell(table, "Boite", tableHeaderFont, deepBlue);
        addHeaderCell(table, "Docs", tableHeaderFont, deepBlue);

        if (archives == null || archives.isEmpty()) {
            PdfPCell emptyCell = new PdfPCell(new Phrase("Aucun dossier archive trouve.", emptyFont));
            emptyCell.setColspan(9);
            emptyCell.setHorizontalAlignment(Element.ALIGN_CENTER);
            emptyCell.setPadding(9);
            table.addCell(emptyCell);
        } else {
            int index = 1;
            for (ArchiveRow row : archives) {
                Color bg = (index % 2 == 0) ? rowAlt : Color.WHITE;
                addDataCell(table, String.valueOf(index), tableCellFont, bg, Element.ALIGN_CENTER);
                addDataCell(table, row.getPortefeuille(), tableCellFont, bg, Element.ALIGN_LEFT);
                addDataCell(table, row.getPin(), tableCellFont, bg, Element.ALIGN_CENTER);
                addDataCell(table, row.getRelation(), tableCellFont, bg, Element.ALIGN_LEFT);
                addDataCell(table, row.getCharge(), tableCellFont, bg, Element.ALIGN_LEFT);
                addDataCell(table, row.getTypeArchive(), tableCellFont, bg, Element.ALIGN_LEFT);
                addDataCell(table, row.getFiliale(), tableCellFont, bg, Element.ALIGN_LEFT);
                addDataCell(table, safe(row.getBoite()), tableCellFont, bg, Element.ALIGN_CENTER);
                addDataCell(table, row.getDocuments(), tableCellFont, bg, Element.ALIGN_CENTER);
                index++;
            }
        }

        document.add(table);
        document.close();
    }

    private void addInfoCell(PdfPTable table, String value, Font font, Color bg) {
        PdfPCell cell = new PdfPCell(new Phrase(safe(value), font));
        cell.setPadding(6);
        cell.setBackgroundColor(bg);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        table.addCell(cell);
    }

    private void addHeaderCell(PdfPTable table, String value, Font font, Color bg) {
        PdfPCell cell = new PdfPCell(new Phrase(safe(value), font));
        cell.setPadding(6);
        cell.setBackgroundColor(bg);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        table.addCell(cell);
    }

    private void addDataCell(PdfPTable table, String value, Font font, Color bg, int alignment) {
        PdfPCell cell = new PdfPCell(new Phrase(safe(value), font));
        cell.setPadding(5);
        cell.setBackgroundColor(bg);
        cell.setHorizontalAlignment(alignment);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        table.addCell(cell);
    }

    private String buildDossierName(Long idDossier, String relation, String pin) {
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
            return "Intermediaire";
        }
        if ("finale".equalsIgnoreCase(value)) {
            return "Finale";
        }
        return value;
    }

    private String toStringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String safe(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static synchronized EntityManagerFactory getEMF() {
        if (emf == null || !emf.isOpen()) {
            emf = Persistence.createEntityManagerFactory("btk");
        }
        return emf;
    }

    public List<ArchiveRow> getArchives() {
        return archives;
    }

    private String resolveFilialeValue(ArchDossier dossier) {
        if (dossier == null) {
            return "";
        }
        String value = dossier.getFiliale();
        if (value == null || value.isBlank()) {
            value = dossier.getIdFiliale();
        }
        return toStringValue(value);
    }

    private String resolveSessionFiliale() {
        return loginBean == null ? "" : loginBean.getCurrentFilialeCode();
    }

    private String resolveSessionLegacyFiliale() {
        return loginBean == null ? "" : loginBean.getCurrentFilialeId();
    }

    public static class ArchiveRow implements Serializable {
        private static final long serialVersionUID = 1L;

        private final Long idDossier;
        private final String portefeuille;
        private final String pin;
        private final String relation;
        private final String charge;
        private final String typeArchive;
        private final String filiale;
        private final String boite;
        private final String documents;

        ArchiveRow(Long idDossier, String portefeuille, String pin, String relation, String charge,
                   String typeArchive, String filiale, String boite, String documents) {
            this.idDossier = idDossier;
            this.portefeuille = portefeuille;
            this.pin = pin;
            this.relation = relation;
            this.charge = charge;
            this.typeArchive = typeArchive;
            this.filiale = filiale;
            this.boite = boite;
            this.documents = documents;
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

        public String getBoite() {
            return boite;
        }

        public String getDocuments() {
            return documents;
        }
    }
}
