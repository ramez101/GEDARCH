package com.btk.bean;

import java.io.Serializable;
import java.util.Date;

import com.btk.util.DemandeFilialeUtil;
import com.btk.util.FilialeUtil;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import jakarta.persistence.Query;

@Named("dashboardStatsBean")
@RequestScoped
public class DashboardStatsBean implements Serializable {

    private static final long serialVersionUID = 1L;
    private static EntityManagerFactory emf;

    @Inject
    private LoginBean loginBean;

    private long totalDossiers;
    private long totalBoites;
    private long totalDemandes;
    private long demandesEnAttente;
    private long demandesApprouvees;
    private long demandesRefusees;
    private long dossiersRestitues;
    private long dossiersNonRestitues;
    private long totalUtilisateurs;
    private long utilisateursActifs;
    private long utilisateursInactifs;
    private Date refreshedAt;

    @PostConstruct
    public void init() {
        load();
    }

    public void load() {
        if (loginBean == null || (!loginBean.isAdminRole() && !loginBean.isConsultationRole())) {
            resetStats();
            return;
        }

        EntityManager em = getEMF().createEntityManager();
        try {
            String filiale = resolveSessionFiliale();
            String legacyFiliale = resolveSessionLegacyFiliale();

            if (loginBean.isAdminRole()) {
                loadAdminStats(em, filiale, legacyFiliale);
            } else {
                loadConsultationStats(em, filiale, legacyFiliale);
            }

            refreshedAt = new Date();
        } finally {
            em.close();
        }
    }

    private void loadAdminStats(EntityManager em, String filiale, String legacyFiliale) {
        totalDossiers = countScopedDossiers(em, filiale, legacyFiliale);
        totalBoites = countScopedBoites(em, filiale, legacyFiliale);

        totalDemandes = countScopedDemandes(em, filiale, legacyFiliale, null);
        demandesEnAttente = countScopedDemandes(
                em, filiale, legacyFiliale, "dd.DATE_APPROUVE IS NULL AND dd.DATE_RESTITUTION IS NULL");
        demandesApprouvees = countScopedDemandes(
                em, filiale, legacyFiliale, "dd.DATE_APPROUVE IS NOT NULL AND dd.DATE_RESTITUTION IS NULL");
        demandesRefusees = countScopedDemandes(
                em, filiale, legacyFiliale, "dd.DATE_APPROUVE IS NULL AND dd.DATE_RESTITUTION IS NOT NULL");
        dossiersRestitues = countScopedDemandes(
                em, filiale, legacyFiliale, "dd.DATE_APPROUVE IS NOT NULL AND dd.DATE_RESTITUTION IS NOT NULL");
        dossiersNonRestitues = countScopedDemandes(
                em, filiale, legacyFiliale, "dd.DATE_APPROUVE IS NOT NULL AND dd.DATE_RESTITUTION IS NULL");

        totalUtilisateurs = countScopedUsers(em, filiale, legacyFiliale, false);
        if (hasActiveColumn(em)) {
            utilisateursActifs = countScopedUsers(em, filiale, legacyFiliale, true);
            utilisateursInactifs = Math.max(0L, totalUtilisateurs - utilisateursActifs);
        } else {
            utilisateursActifs = totalUtilisateurs;
            utilisateursInactifs = 0L;
        }
    }

    private void loadConsultationStats(EntityManager em, String filiale, String legacyFiliale) {
        totalDossiers = 0L;
        totalBoites = 0L;
        totalUtilisateurs = 0L;
        utilisateursActifs = 0L;
        utilisateursInactifs = 0L;

        String unix = normalizeIdentifier(loginBean.getUtilisateur() == null ? null : loginBean.getUtilisateur().getUnix());
        String cutiValue = normalizeIdentifier(loginBean.getUtilisateur() == null ? null : loginBean.getUtilisateur().getCuti());
        if (unix.isBlank() && cutiValue.isBlank()) {
            totalDemandes = 0L;
            demandesEnAttente = 0L;
            demandesApprouvees = 0L;
            demandesRefusees = 0L;
            dossiersRestitues = 0L;
            dossiersNonRestitues = 0L;
            return;
        }

        totalDemandes = countScopedConsultationDemandes(em, filiale, legacyFiliale, null, unix, cutiValue);
        demandesEnAttente = countScopedConsultationDemandes(
                em,
                filiale,
                legacyFiliale,
                "dd.DATE_APPROUVE IS NULL AND dd.DATE_RESTITUTION IS NULL",
                unix,
                cutiValue);
        demandesApprouvees = countScopedConsultationDemandes(
                em,
                filiale,
                legacyFiliale,
                "dd.DATE_APPROUVE IS NOT NULL",
                unix,
                cutiValue);
        demandesRefusees = countScopedConsultationDemandes(
                em,
                filiale,
                legacyFiliale,
                "dd.DATE_APPROUVE IS NULL AND dd.DATE_RESTITUTION IS NOT NULL",
                unix,
                cutiValue);
        dossiersRestitues = countScopedConsultationDemandes(
                em,
                filiale,
                legacyFiliale,
                "dd.DATE_APPROUVE IS NOT NULL AND dd.DATE_RESTITUTION IS NOT NULL",
                unix,
                cutiValue);
        dossiersNonRestitues = countScopedConsultationDemandes(
                em,
                filiale,
                legacyFiliale,
                "dd.DATE_APPROUVE IS NOT NULL AND dd.DATE_RESTITUTION IS NULL",
                unix,
                cutiValue);
    }

    private void resetStats() {
        totalDossiers = 0L;
        totalBoites = 0L;
        totalDemandes = 0L;
        demandesEnAttente = 0L;
        demandesApprouvees = 0L;
        demandesRefusees = 0L;
        dossiersRestitues = 0L;
        dossiersNonRestitues = 0L;
        totalUtilisateurs = 0L;
        utilisateursActifs = 0L;
        utilisateursInactifs = 0L;
        refreshedAt = null;
    }

    private long countScopedDossiers(EntityManager em, String filiale, String legacyFiliale) {
        return countWithParameters(
                em,
                "SELECT COUNT(*) " +
                        "FROM ARCH_DOSSIER d " +
                        "WHERE (LOWER(TRIM(d.FILIALE)) = :sessionFiliale " +
                        "OR LOWER(TRIM(d.FILIALE)) = :sessionLegacyFiliale " +
                        "OR (d.FILIALE IS NULL AND (" +
                        "LOWER(TRIM(d.ID_FILIALE)) = :sessionFiliale " +
                        "OR LOWER(TRIM(d.ID_FILIALE)) = :sessionLegacyFiliale)))",
                filiale,
                legacyFiliale
        );
    }

    private long countScopedBoites(EntityManager em, String filiale, String legacyFiliale) {
        return countWithParameters(
                em,
                "SELECT COUNT(*) " +
                        "FROM ARCH_BOITE b " +
                        "WHERE LOWER(TRIM(b.FILIALE)) = :sessionFiliale " +
                        "OR LOWER(TRIM(b.FILIALE)) = :sessionLegacyFiliale",
                filiale,
                legacyFiliale
        );
    }

    private long countScopedDemandes(EntityManager em, String filiale, String legacyFiliale, String additionalWhere) {
        Query query = createScopedDemandeQuery(em, "SELECT COUNT(*)", additionalWhere, filiale, legacyFiliale);
        try {
            Object raw = query.getSingleResult();
            return toLong(raw);
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    private long countScopedConsultationDemandes(EntityManager em,
                                                 String filiale,
                                                 String legacyFiliale,
                                                 String additionalWhere,
                                                 String unix,
                                                 String cutiValue) {
        Query query = createScopedConsultationDemandeQuery(
                em,
                "SELECT COUNT(*)",
                additionalWhere,
                filiale,
                legacyFiliale,
                unix,
                cutiValue);
        try {
            Object raw = query.getSingleResult();
            return toLong(raw);
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    private long countScopedUsers(EntityManager em, String filiale, String legacyFiliale, boolean activeOnly) {
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) " +
                        "FROM ARCH_UTILISATEURS u " +
                        "WHERE (LOWER(TRIM(u.PUTI)) = :sessionFiliale " +
                        "OR LOWER(TRIM(u.PUTI)) = :sessionLegacyFiliale)");

        if (activeOnly) {
            sql.append(" AND UPPER(TRIM(NVL(TO_CHAR(u.ACTIVE), '1'))) ")
               .append("IN ('1', 'TRUE', 'Y', 'YES', 'O', 'OUI', 'ACTIF', 'ACTIVE')");
        }

        return countWithParameters(em, sql.toString(), filiale, legacyFiliale);
    }

    private long countWithParameters(EntityManager em, String sql, String filiale, String legacyFiliale) {
        try {
            Object raw = em.createNativeQuery(sql)
                    .setParameter("sessionFiliale", normalizeLower(filiale))
                    .setParameter("sessionLegacyFiliale", normalizeLower(legacyFiliale))
                    .getSingleResult();
            return toLong(raw);
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    private Query createScopedDemandeQuery(EntityManager em,
                                           String selectClause,
                                           String additionalWhere,
                                           String filiale,
                                           String legacyFiliale) {
        String filialePredicate = DemandeFilialeUtil.buildPredicate(em, "dd", filiale, legacyFiliale);

        StringBuilder sql = new StringBuilder(selectClause)
                .append(" FROM DEMANDE_DOSSIER dd ")
                .append("WHERE ")
                .append(filialePredicate);

        if (additionalWhere != null && !additionalWhere.isBlank()) {
            sql.append(" AND ").append(additionalWhere);
        }

        Query query = em.createNativeQuery(sql.toString());
        DemandeFilialeUtil.bindParameters(query, filiale, legacyFiliale);
        return query;
    }

    private Query createScopedConsultationDemandeQuery(EntityManager em,
                                                       String selectClause,
                                                       String additionalWhere,
                                                       String filiale,
                                                       String legacyFiliale,
                                                       String unix,
                                                       String cutiValue) {
        String filialePredicate = DemandeFilialeUtil.buildPredicate(em, "dd", filiale, legacyFiliale);

        StringBuilder sql = new StringBuilder(selectClause)
                .append(" FROM DEMANDE_DOSSIER dd ")
                .append("WHERE ")
                .append(filialePredicate)
                .append(" AND UPPER(TRIM(dd.EMETTEUR)) IN (UPPER(TRIM(:emetteurUnix)), UPPER(TRIM(:emetteurCuti)))");

        if (additionalWhere != null && !additionalWhere.isBlank()) {
            sql.append(" AND ").append(additionalWhere);
        }

        Query query = em.createNativeQuery(sql.toString())
                .setParameter("emetteurUnix", unix)
                .setParameter("emetteurCuti", cutiValue);
        DemandeFilialeUtil.bindParameters(query, filiale, legacyFiliale);
        return query;
    }

    private long toLong(Object raw) {
        if (raw instanceof Number) {
            return ((Number) raw).longValue();
        }
        if (raw != null) {
            return Long.parseLong(String.valueOf(raw));
        }
        return 0L;
    }

    private boolean hasActiveColumn(EntityManager em) {
        try {
            Object raw = em.createNativeQuery(
                            "SELECT COUNT(*) FROM USER_TAB_COLUMNS " +
                            "WHERE TABLE_NAME = 'ARCH_UTILISATEURS' AND COLUMN_NAME = 'ACTIVE'")
                    .getSingleResult();
            return toLong(raw) > 0L;
        } catch (RuntimeException e) {
            return false;
        }
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

    private String normalizeLower(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private String normalizeIdentifier(String value) {
        return value == null ? "" : value.trim();
    }

    private static synchronized EntityManagerFactory getEMF() {
        if (emf == null || !emf.isOpen()) {
            emf = Persistence.createEntityManagerFactory("btk");
        }
        return emf;
    }

    public long getTotalDossiers() {
        return totalDossiers;
    }

    public long getTotalBoites() {
        return totalBoites;
    }

    public long getTotalDemandes() {
        return totalDemandes;
    }

    public long getDemandesEnAttente() {
        return demandesEnAttente;
    }

    public long getDemandesApprouvees() {
        return demandesApprouvees;
    }

    public long getDemandesRefusees() {
        return demandesRefusees;
    }

    public long getDossiersRestitues() {
        return dossiersRestitues;
    }

    public long getDossiersNonRestitues() {
        return dossiersNonRestitues;
    }

    public long getTotalUtilisateurs() {
        return totalUtilisateurs;
    }

    public long getUtilisateursActifs() {
        return utilisateursActifs;
    }

    public long getUtilisateursInactifs() {
        return utilisateursInactifs;
    }

    public long getDemandesTraitees() {
        if (loginBean != null && loginBean.isConsultationRole() && !loginBean.isAdminRole()) {
            return demandesApprouvees + demandesRefusees;
        }
        return demandesApprouvees + demandesRefusees + dossiersRestitues;
    }

    public int getTauxTraitement() {
        if (totalDemandes <= 0L) {
            return 0;
        }
        double ratio = ((double) getDemandesTraitees() / (double) totalDemandes) * 100d;
        return (int) Math.round(ratio);
    }

    public Date getRefreshedAt() {
        return refreshedAt;
    }

    public String getCurrentFilialeLabel() {
        String label = loginBean == null ? "" : loginBean.getCurrentFilialeLabel();
        if (label == null || label.isBlank()) {
            return "Session";
        }
        return label;
    }

    public boolean isVisible() {
        return loginBean != null && (loginBean.isAdminRole() || loginBean.isConsultationRole());
    }

    public boolean isConsultationScopedStats() {
        return loginBean != null && loginBean.isConsultationRole() && !loginBean.isAdminRole();
    }
}
