package com.btk.bean;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import com.btk.model.ArchDossier;
import com.btk.model.ArchEmplacement;
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
import jakarta.transaction.HeuristicMixedException;
import jakarta.transaction.HeuristicRollbackException;
import jakarta.transaction.NotSupportedException;
import jakarta.transaction.RollbackException;
import jakarta.transaction.SystemException;
import jakarta.transaction.UserTransaction;
import org.primefaces.PrimeFaces;

@Named("modifierArchivesBean")
@ViewScoped
public class ModifierArchivesBean implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final String RELATION_SUGGESTION_SEPARATOR = " | ";

    private static EntityManagerFactory emf;

    @Resource
    private UserTransaction utx;

    @Inject
    private LoginBean loginBean;

    private String searchType = "pin";
    private String searchValue;

    private Long dossierId;
    private boolean resultLoaded;

    private String portefeuille;
    private String pin;
    private String relation;
    private String charge;
    private String typeArchive;
    private String filialeId;
    private List<ChargeOption> consultationOptions = Collections.emptyList();
    private Integer boite;
    private Integer boiteToRemove;
    private List<Integer> selectedBoites = new ArrayList<>();
    private String originalPortefeuille;
    private String originalPin;
    private String originalRelation;
    private String originalCharge;
    private String originalTypeArchive;
    private List<Integer> originalSelectedBoites = new ArrayList<>();

    private List<Integer> boites = Collections.emptyList();

    @PostConstruct
    public void init() {
        boites = fetchBoites();
        consultationOptions = fetchConsultationOptions();
    }

    public void search() {
        clearResult();

        boolean searchByRelation = "relation".equalsIgnoreCase(searchType);
        String effectiveSearchValue = searchByRelation ? extractRelationSearchTerm(searchValue) : searchValue;
        if (effectiveSearchValue == null || effectiveSearchValue.isBlank()) {
            addWarn("Veuillez saisir un pin ou une relation.");
            return;
        }

        EntityManager em = getEMF().createEntityManager();
        try {
            String searchedField = searchByRelation ? "relation" : "pin";

            List<ArchDossier> rows = em.createQuery(
                            "select d from " + ArchDossier.class.getSimpleName() + " d " +
                                    "where upper(trim(d." + searchedField + ")) = :value " +
                                    "and (lower(trim(d.filiale)) = :filiale " +
                                    "or (d.filiale is null and lower(trim(d.idFiliale)) = :legacyFiliale)) " +
                                    "order by d.idDossier",
                            ArchDossier.class)
                    .setParameter("value", normalizeSearchValue(effectiveSearchValue))
                    .setParameter("filiale", resolveSessionFiliale())
                    .setParameter("legacyFiliale", resolveSessionLegacyFiliale())
                    .setMaxResults(1)
                    .getResultList();

            if (rows.isEmpty()) {
                PrimeFaces.current().executeScript("PF('modifierNotFoundDialog').show()");
                return;
            }

            ArchDossier dossier = rows.get(0);
            dossierId = dossier.getIdDossier();
            portefeuille = dossier.getPortefeuille();
            pin = dossier.getPin();
            relation = dossier.getRelation();
            charge = resolveSelectedCharge(dossier.getCharge());
            consultationOptions = fetchConsultationOptions(charge);
            typeArchive = dossier.getTypeArchive();
            filialeId = dossier.getIdFiliale();
            resultLoaded = true;

            selectedBoites = new ArrayList<>(DossierEmpUtil.findBoitesByDossierId(em, dossierId));
            boite = null;
            boiteToRemove = null;
            boites = fetchBoites();
            captureOriginalSnapshot();

            PrimeFaces.current().executeScript("PF('modifierNotFoundDialog').hide()");
        } finally {
            em.close();
        }
    }

    public void save() {
        if (dossierId == null) {
            addWarn("Veuillez rechercher un dossier avant de confirmer.");
            return;
        }

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
                                        "where d.idDossier <> :idDossier " +
                                        "and upper(trim(d.pin)) = :pin " +
                                        "and (lower(trim(d.filiale)) = :filiale " +
                                        "or (d.filiale is null and lower(trim(d.idFiliale)) = :legacyFiliale))",
                                Long.class)
                        .setParameter("idDossier", dossierId)
                        .setParameter("pin", pin.trim().toUpperCase(Locale.ROOT))
                        .setParameter("filiale", sessionFiliale)
                        .setParameter("legacyFiliale", sessionLegacyFiliale)
                        .getSingleResult();
                if (existing != null && existing > 0) {
                    addWarn("PIN déjà utilisé dans cette filiale.");
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

            ArchDossier dossier = em.find(ArchDossier.class, dossierId);
            if (dossier == null) {
                addError("Dossier introuvable.");
                markValidationFailed();
                return;
            }

            dossier.setPortefeuille(portefeuille);
            dossier.setPin(pin);
            dossier.setRelation(relation);
            dossier.setCharge(resolveSelectedCharge(charge));
            dossier.setTypeArchive(typeArchive);
            dossier.setIdFiliale(sessionLegacyFiliale);
            dossier.setFiliale(sessionFiliale);

            DossierEmpUtil.replaceBoites(em, dossierId, pin, relation, boitesToSave);
            em.flush();

            utx.commit();
            txStarted = false;

            addInfo("Modification enregistrée.");
            clear();
        } catch (NotSupportedException | SystemException | RollbackException
                 | HeuristicMixedException | HeuristicRollbackException e) {
            if (txStarted) {
                try { utx.rollback(); } catch (Exception ignored) {}
            }
            addError("Erreur modification : " + e.getMessage());
            markValidationFailed();
        } catch (RuntimeException e) {
            if (txStarted) {
                try { utx.rollback(); } catch (Exception ignored) {}
            }
            addError("Erreur modification : " + e.getMessage());
            markValidationFailed();
        } finally {
            em.close();
        }
    }

    public void clear() {
        searchType = "pin";
        searchValue = null;
        clearResult();
    }

    private void clearResult() {
        dossierId = null;
        resultLoaded = false;
        portefeuille = null;
        pin = null;
        relation = null;
        charge = null;
        typeArchive = null;
        filialeId = null;
        boite = null;
        boiteToRemove = null;
        selectedBoites = new ArrayList<>();
        originalPortefeuille = null;
        originalPin = null;
        originalRelation = null;
        originalCharge = null;
        originalTypeArchive = null;
        originalSelectedBoites = new ArrayList<>();
        boites = fetchBoites();
        consultationOptions = fetchConsultationOptions();
    }

    private void captureOriginalSnapshot() {
        originalPortefeuille = portefeuille;
        originalPin = pin;
        originalRelation = relation;
        originalCharge = charge;
        originalTypeArchive = typeArchive;
        originalSelectedBoites = new ArrayList<>(DossierEmpUtil.normalizeBoites(selectedBoites));
    }

    public void prepareConfirmModification() {
        PrimeFaces.current().ajax().addCallbackParam("openDialog", hasPendingChanges());
    }

    public void prepareCancelModification() {
        PrimeFaces.current().ajax().addCallbackParam("openDialog", hasPendingChanges());
    }

    private boolean hasPendingChanges() {
        if (!resultLoaded || dossierId == null) {
            return false;
        }

        return !equalsNormalized(portefeuille, originalPortefeuille)
                || !equalsNormalized(pin, originalPin)
                || !equalsNormalized(relation, originalRelation)
                || !equalsNormalized(charge, originalCharge)
                || !equalsNormalized(typeArchive, originalTypeArchive)
                || !DossierEmpUtil.normalizeBoites(resolveBoitesToSave())
                .equals(DossierEmpUtil.normalizeBoites(originalSelectedBoites));
    }

    private boolean equalsNormalized(String left, String right) {
        return normalize(left).equals(normalize(right));
    }

    private String normalizeSearchValue(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
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

    public void addBoiteSelection() {
        if (boite == null) {
            addWarn("Saisir un numéro de boite avant d'ajouter.");
            markValidationFailed();
            return;
        }

        if (boites == null || !boites.contains(boite)) {
            addError("La boite " + boite + " est introuvable.");
            markValidationFailed();
            return;
        }

        if (selectedBoites.contains(boite)) {
            addWarn("La boite " + boite + " est déjà associée.");
            boite = null;
            markValidationFailed();
            return;
        }

        selectedBoites.add(boite);
        selectedBoites = new ArrayList<>(DossierEmpUtil.normalizeBoites(selectedBoites));
        addInfo("Boite " + boite + " ajoutée.");
        boite = null;
    }

    public void removeBoiteSelection() {
        if (boiteToRemove == null) {
            return;
        }

        selectedBoites.remove(boiteToRemove);
        addInfo("Boite " + boiteToRemove + " retirée.");
        boiteToRemove = null;
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

    private List<Integer> resolveBoitesToSave() {
        List<Integer> resolved = new ArrayList<>(selectedBoites);
        if (boite != null && !resolved.contains(boite)) {
            resolved.add(boite);
        }
        return resolved;
    }

    private List<Integer> fetchBoites() {
        EntityManager em = getEMF().createEntityManager();
        try {
            return DossierEmpUtil.findBoitesByFiliale(em, resolveSessionFiliale());
        } finally {
            em.close();
        }
    }

    private List<ChargeOption> fetchConsultationOptions() {
        return fetchConsultationOptions(null);
    }

    private List<ChargeOption> fetchConsultationOptions(String selectedCharge) {
        EntityManager em = getEMF().createEntityManager();
        try {
            String sessionFiliale = resolveSessionFiliale();
            String sessionLegacyFiliale = resolveSessionLegacyFiliale();

            @SuppressWarnings("unchecked")
            List<Object[]> rows = em.createNativeQuery(
                            "SELECT CUTI, UNIX, LIB " +
                                    "FROM ARCH_UTILISATEURS " +
                                    "WHERE UPPER(TRIM(ROLE)) = 'CONSULTATION' " +
                                    "AND (LOWER(TRIM(PUTI)) = :sessionFiliale OR LOWER(TRIM(PUTI)) = :sessionLegacyFiliale) " +
                                    "ORDER BY UPPER(TRIM(NVL(LIB, UNIX))), UPPER(TRIM(CUTI))")
                    .setParameter("sessionFiliale", sessionFiliale)
                    .setParameter("sessionLegacyFiliale", sessionLegacyFiliale)
                    .getResultList();

            List<ChargeOption> options = new ArrayList<>();
            for (Object[] row : rows) {
                String cuti = normalize(toStringValue(row[0]));
                String unix = normalize(toStringValue(row[1]));
                String lib = normalize(toStringValue(row[2]));
                String value = resolveChargeValue(cuti, unix, lib);
                if (value.isBlank()) {
                    continue;
                }
                options.add(new ChargeOption(value, buildChargeLabel(lib, unix, cuti)));
            }

            String currentCharge = normalize(selectedCharge);
            if (!currentCharge.isBlank() && !containsChargeOption(options, currentCharge)) {
                options.add(0, new ChargeOption(currentCharge, currentCharge));
            }

            return options;
        } finally {
            em.close();
        }
    }

    private boolean containsChargeOption(List<ChargeOption> options, String chargeValue) {
        if (options == null || options.isEmpty() || chargeValue == null || chargeValue.isBlank()) {
            return false;
        }
        for (ChargeOption option : options) {
            if (option != null && chargeValue.equalsIgnoreCase(normalize(option.getValue()))) {
                return true;
            }
        }
        return false;
    }

    private String buildChargeLabel(String lib, String unix, String cuti) {
        String displayName = !lib.isBlank() ? lib : (!unix.isBlank() ? unix : cuti);
        String account = !unix.isBlank() ? unix : cuti;
        if (account.isBlank() || displayName.equalsIgnoreCase(account)) {
            return displayName;
        }
        return displayName + " (" + account + ")";
    }

    private String resolveChargeValue(String cuti, String unix, String lib) {
        String normalizedLib = normalize(lib);
        if (!normalizedLib.isBlank()) {
            return normalizedLib;
        }
        String normalizedUnix = normalize(unix);
        if (!normalizedUnix.isBlank()) {
            return normalizedUnix;
        }
        return normalize(cuti);
    }

    private String resolveSelectedCharge(String selectedCharge) {
        String normalizedSelectedCharge = normalize(selectedCharge);
        if (normalizedSelectedCharge.isBlank()) {
            return normalizedSelectedCharge;
        }

        EntityManager em = getEMF().createEntityManager();
        try {
            String sessionFiliale = resolveSessionFiliale();
            String sessionLegacyFiliale = resolveSessionLegacyFiliale();

            @SuppressWarnings("unchecked")
            List<Object[]> rows = em.createNativeQuery(
                            "SELECT CUTI, UNIX, LIB " +
                                    "FROM ARCH_UTILISATEURS " +
                                    "WHERE UPPER(TRIM(ROLE)) = 'CONSULTATION' " +
                                    "AND (LOWER(TRIM(PUTI)) = :sessionFiliale OR LOWER(TRIM(PUTI)) = :sessionLegacyFiliale)")
                    .setParameter("sessionFiliale", sessionFiliale)
                    .setParameter("sessionLegacyFiliale", sessionLegacyFiliale)
                    .getResultList();

            for (Object[] row : rows) {
                String cuti = normalize(toStringValue(row[0]));
                String unix = normalize(toStringValue(row[1]));
                String lib = normalize(toStringValue(row[2]));
                if (normalizedSelectedCharge.equalsIgnoreCase(cuti)
                        || normalizedSelectedCharge.equalsIgnoreCase(unix)
                        || normalizedSelectedCharge.equalsIgnoreCase(lib)) {
                    return resolveChargeValue(cuti, unix, lib);
                }
            }
            return normalizedSelectedCharge;
        } finally {
            em.close();
        }
    }

    private String toStringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static synchronized EntityManagerFactory getEMF() {
        if (emf == null || !emf.isOpen()) {
            emf = Persistence.createEntityManagerFactory("btk");
        }
        return emf;
    }

    public String getSearchType() { return searchType; }
    public void setSearchType(String searchType) { this.searchType = searchType; }

    public String getSearchValue() { return searchValue; }
    public void setSearchValue(String searchValue) { this.searchValue = searchValue; }

    public boolean isResultLoaded() { return resultLoaded; }

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

    public List<ChargeOption> getConsultationOptions() { return consultationOptions; }

    public String getFilialeLabel() {
        String current = filialeId;
        if (current == null || current.isBlank()) {
            current = resolveSessionFiliale();
        }
        return FilialeUtil.toLabel(current);
    }

    public Integer getBoite() { return boite; }
    public void setBoite(Integer boite) { this.boite = boite; }

    public List<Integer> getBoites() { return boites; }
    public List<Integer> getSelectedBoites() { return selectedBoites; }
    public Integer getBoiteToRemove() { return boiteToRemove; }
    public void setBoiteToRemove(Integer boiteToRemove) { this.boiteToRemove = boiteToRemove; }
    public String getBoitesSummary() { return DossierEmpUtil.formatBoites(resolveBoitesToSave()); }
    public String getOriginalPortefeuille() { return originalPortefeuille; }
    public String getOriginalPin() { return originalPin; }
    public String getOriginalRelation() { return originalRelation; }
    public String getOriginalCharge() { return originalCharge; }
    public String getOriginalTypeArchive() { return originalTypeArchive; }
    public String getOriginalBoitesCsv() { return DossierEmpUtil.formatBoites(originalSelectedBoites); }

    private String resolveSessionFiliale() {
        return loginBean == null ? "" : loginBean.getCurrentFilialeCode();
    }

    private String resolveSessionLegacyFiliale() {
        return loginBean == null ? "" : loginBean.getCurrentFilialeId();
    }

    public static class ChargeOption implements Serializable {
        private static final long serialVersionUID = 1L;

        private final String value;
        private final String label;

        ChargeOption(String value, String label) {
            this.value = value;
            this.label = label == null ? "" : label;
        }

        public String getValue() { return value; }
        public String getLabel() { return label; }
    }
}
