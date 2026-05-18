package com.btk.bean;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

import com.btk.model.ArchDossier;
import com.btk.util.DossierEmpUtil;
import com.btk.util.FilialeUtil;

import jakarta.annotation.PostConstruct;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import java.util.Locale;

@Named("consultationBoitesBean")
@ViewScoped
public class ConsultationBoitesBean implements Serializable {

    private static final long serialVersionUID = 1L;

    private static EntityManagerFactory emf;

    @Inject
    private LoginBean loginBean;

    private String searchBoite;
    private List<ArchDossier> dossiers = Collections.emptyList();
    private ArchDossier selectedDossier;
    private String selectedDossierBoites;
    private boolean searched;
    private boolean resultLoaded;

    @PostConstruct
    public void init() {
        applyGlobalSearchPrefill();
    }

    public void search() {
        resetSearchResult();

        if (searchBoite == null || searchBoite.isBlank()) {
            addWarning("Veuillez choisir un numéro de boîte.");
            return;
        }

        Integer boite;
        try {
            boite = Integer.valueOf(searchBoite.trim());
        } catch (NumberFormatException e) {
            addWarning("Le numéro de boite doit être numérique.");
            return;
        }

        EntityManager em = getEMF().createEntityManager();
        try {
            dossiers = em.createQuery(
                            "select d from " + ArchDossier.class.getSimpleName() + " d " +
                                    "where (" +
                                    "d.idDossier in (" +
                                    "select de.idDossier from DossierEmp de where de.boite = :boite" +
                                    ") or upper(trim(d.pin)) in (" +
                                    "select upper(trim(de.pin)) from DossierEmp de where de.boite = :boite and de.pin is not null" +
                                    ")" +
                                    ") and (lower(trim(d.filiale)) = :filiale " +
                                    "or (d.filiale is null and lower(trim(d.idFiliale)) = :legacyFiliale)) " +
                                    "order by d.pin, d.relation, d.idDossier",
                            ArchDossier.class)
                    .setParameter("boite", boite)
                    .setParameter("filiale", resolveSessionFiliale())
                    .setParameter("legacyFiliale", resolveSessionLegacyFiliale())
                    .getResultList();

            searched = true;
            resultLoaded = !dossiers.isEmpty();
        } finally {
            em.close();
        }
    }

    public void clear() {
        searchBoite = null;
        resetSearchResult();
    }

    private void resetSearchResult() {
        searched = false;
        dossiers = Collections.emptyList();
        selectedDossier = null;
        selectedDossierBoites = null;
        resultLoaded = false;
    }

    private void applyGlobalSearchPrefill() {
        FacesContext context = FacesContext.getCurrentInstance();
        if (context == null || context.isPostback()) {
            return;
        }

        String rawBoite = context.getExternalContext().getRequestParameterMap().get("globalBoite");
        if (rawBoite == null || rawBoite.isBlank()) {
            return;
        }

        String normalized = rawBoite.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("\\d+")) {
            return;
        }

        searchBoite = normalized;
        search();
    }

    private void addWarning(String message) {
        FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_WARN, null, message));
    }

    private static synchronized EntityManagerFactory getEMF() {
        if (emf == null || !emf.isOpen()) {
            emf = Persistence.createEntityManagerFactory("btk");
        }
        return emf;
    }

    public String getSearchBoite() {
        return searchBoite;
    }

    public void setSearchBoite(String searchBoite) {
        this.searchBoite = searchBoite;
    }

    public List<ArchDossier> getDossiers() {
        return dossiers;
    }

    public ArchDossier getSelectedDossier() {
        return selectedDossier;
    }

    public void setSelectedDossier(ArchDossier selectedDossier) {
        this.selectedDossier = selectedDossier;
        if (selectedDossier == null || selectedDossier.getIdDossier() == null) {
            selectedDossierBoites = null;
            return;
        }

        EntityManager em = getEMF().createEntityManager();
        try {
            selectedDossierBoites = DossierEmpUtil.findBoitesSummary(
                    em,
                    selectedDossier.getIdDossier(),
                    selectedDossier.getPin(),
                    selectedDossier.getRelation()
            );
        } finally {
            em.close();
        }
    }

    public String getSelectedDossierBoites() {
        return selectedDossierBoites;
    }

    public String getSelectedDossierFilialeLabel() {
        if (selectedDossier == null) {
            return "";
        }
        String value = selectedDossier.getFiliale();
        if (value == null || value.isBlank()) {
            value = selectedDossier.getIdFiliale();
        }
        return FilialeUtil.toLabel(value);
    }

    public boolean isSearched() {
        return searched;
    }

    public boolean isBoiteVide() {
        return searched && dossiers.isEmpty();
    }

    public boolean isResultLoaded() {
        return resultLoaded;
    }

    private String resolveSessionFiliale() {
        return loginBean == null ? "" : loginBean.getCurrentFilialeCode();
    }

    private String resolveSessionLegacyFiliale() {
        return loginBean == null ? "" : loginBean.getCurrentFilialeId();
    }
}
