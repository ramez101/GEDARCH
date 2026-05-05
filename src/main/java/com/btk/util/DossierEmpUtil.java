package com.btk.util;

import com.btk.model.ArchDossier;
import com.btk.model.ArchEmplacement;
import com.btk.model.DossierEmp;
import jakarta.persistence.EntityManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class DossierEmpUtil {

    private DossierEmpUtil() {
    }

    public static boolean boiteExists(EntityManager em, Integer boite) {
        return boiteExists(em, boite, null);
    }

    public static boolean boiteExists(EntityManager em, Integer boite, String filiale) {
        if (boite == null) {
            return false;
        }

        return findEmplacementByBoite(em, boite, filiale) != null;
    }

    public static List<Integer> normalizeBoites(Collection<Integer> rawBoites) {
        if (rawBoites == null || rawBoites.isEmpty()) {
            return Collections.emptyList();
        }

        LinkedHashSet<Integer> unique = new LinkedHashSet<>();
        for (Integer boite : rawBoites) {
            if (boite != null) {
                unique.add(boite);
            }
        }

        List<Integer> normalized = new ArrayList<>(unique);
        Collections.sort(normalized);
        return normalized;
    }

    public static List<Integer> findBoitesByDossierId(EntityManager em, Long idDossier) {
        if (idDossier == null) {
            return Collections.emptyList();
        }

        return em.createQuery(
                        "select de.boite from " + DossierEmp.class.getSimpleName() + " de " +
                                "where de.idDossier = :idDossier " +
                                "order by de.boite",
                        Integer.class)
                .setParameter("idDossier", idDossier)
                .getResultList();
    }

    public static List<Integer> findBoitesByReference(EntityManager em, String pin, String relation) {
        String normalizedPin = normalizeReferenceValue(pin);
        if (normalizedPin.isBlank()) {
            return Collections.emptyList();
        }

        String normalizedRelation = normalizeReferenceValue(relation);
        if (!normalizedRelation.isBlank()) {
            List<Integer> byPinAndRelation = em.createQuery(
                            "select distinct de.boite from " + DossierEmp.class.getSimpleName() + " de " +
                                    "where de.boite is not null " +
                                    "and upper(trim(de.pin)) = :pin " +
                                    "and upper(trim(de.relation)) = :relation " +
                                    "order by de.boite",
                            Integer.class)
                    .setParameter("pin", normalizedPin)
                    .setParameter("relation", normalizedRelation)
                    .getResultList();
            if (!byPinAndRelation.isEmpty()) {
                return byPinAndRelation;
            }
        }

        return em.createQuery(
                        "select distinct de.boite from " + DossierEmp.class.getSimpleName() + " de " +
                                "where de.boite is not null and upper(trim(de.pin)) = :pin " +
                                "order by de.boite",
                        Integer.class)
                .setParameter("pin", normalizedPin)
                .getResultList();
    }

    public static List<Integer> findBoitesByDossierIdOrReference(EntityManager em, Long idDossier, String pin, String relation) {
        List<Integer> byDossierId = findBoitesByDossierId(em, idDossier);
        if (!byDossierId.isEmpty()) {
            return byDossierId;
        }
        return findBoitesByReference(em, pin, relation);
    }

    public static Map<Long, List<Integer>> findBoitesByDossierIds(EntityManager em, Collection<Long> dossierIds) {
        if (dossierIds == null || dossierIds.isEmpty()) {
            return Collections.emptyMap();
        }

        List<Long> ids = new ArrayList<>();
        for (Long dossierId : dossierIds) {
            if (dossierId != null) {
                ids.add(dossierId);
            }
        }
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }

        List<Object[]> rows = em.createQuery(
                        "select de.idDossier, de.boite from " + DossierEmp.class.getSimpleName() + " de " +
                                "where de.idDossier in :ids " +
                                "order by de.idDossier, de.boite",
                        Object[].class)
                .setParameter("ids", ids)
                .getResultList();

        Map<Long, List<Integer>> grouped = new LinkedHashMap<>();
        for (Object[] row : rows) {
            Long dossierId = row[0] instanceof Number ? ((Number) row[0]).longValue() : null;
            Integer boite = row[1] instanceof Number ? ((Number) row[1]).intValue() : null;
            if (dossierId == null || boite == null) {
                continue;
            }
            grouped.computeIfAbsent(dossierId, ignored -> new ArrayList<>()).add(boite);
        }
        return grouped;
    }

    public static Map<Long, List<Integer>> findBoitesByDossiers(EntityManager em, Collection<ArchDossier> dossiers) {
        if (dossiers == null || dossiers.isEmpty()) {
            return Collections.emptyMap();
        }

        List<Long> dossierIds = new ArrayList<>();
        for (ArchDossier dossier : dossiers) {
            if (dossier != null && dossier.getIdDossier() != null) {
                dossierIds.add(dossier.getIdDossier());
            }
        }
        if (dossierIds.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<Long, List<Integer>> boitesByDossier = new LinkedHashMap<>(findBoitesByDossierIds(em, dossierIds));

        List<ArchDossier> unresolved = new ArrayList<>();
        LinkedHashSet<String> pins = new LinkedHashSet<>();
        for (ArchDossier dossier : dossiers) {
            if (dossier == null || dossier.getIdDossier() == null) {
                continue;
            }
            List<Integer> current = boitesByDossier.get(dossier.getIdDossier());
            if (current != null && !current.isEmpty()) {
                continue;
            }

            String pin = normalizeReferenceValue(dossier.getPin());
            if (pin.isBlank()) {
                continue;
            }
            unresolved.add(dossier);
            pins.add(pin);
        }
        if (unresolved.isEmpty() || pins.isEmpty()) {
            return boitesByDossier;
        }

        List<Object[]> rows = em.createQuery(
                        "select upper(trim(de.pin)), upper(trim(de.relation)), de.boite " +
                                "from " + DossierEmp.class.getSimpleName() + " de " +
                                "where de.boite is not null and upper(trim(de.pin)) in :pins " +
                                "order by upper(trim(de.pin)), upper(trim(de.relation)), de.boite",
                        Object[].class)
                .setParameter("pins", new ArrayList<>(pins))
                .getResultList();

        Map<String, List<Integer>> boitesByPin = new LinkedHashMap<>();
        Map<String, List<Integer>> boitesByPinRelation = new LinkedHashMap<>();
        for (Object[] row : rows) {
            String pin = normalizeReferenceValue(toStringValue(row[0]));
            String relation = normalizeReferenceValue(toStringValue(row[1]));
            Integer boite = row[2] instanceof Number ? ((Number) row[2]).intValue() : null;
            if (pin.isBlank() || boite == null) {
                continue;
            }

            appendBoite(boitesByPin, pin, boite);
            if (!relation.isBlank()) {
                appendBoite(boitesByPinRelation, buildPinRelationKey(pin, relation), boite);
            }
        }

        for (ArchDossier dossier : unresolved) {
            Long dossierId = dossier.getIdDossier();
            if (dossierId == null) {
                continue;
            }

            String pin = normalizeReferenceValue(dossier.getPin());
            if (pin.isBlank()) {
                continue;
            }

            String relation = normalizeReferenceValue(dossier.getRelation());
            List<Integer> resolved = relation.isBlank()
                    ? Collections.emptyList()
                    : boitesByPinRelation.getOrDefault(buildPinRelationKey(pin, relation), Collections.emptyList());
            if (resolved.isEmpty()) {
                resolved = boitesByPin.getOrDefault(pin, Collections.emptyList());
            }
            if (!resolved.isEmpty()) {
                boitesByDossier.put(dossierId, new ArrayList<>(resolved));
            }
        }

        return boitesByDossier;
    }

    public static Integer findPrimaryBoite(EntityManager em, Long idDossier) {
        List<Integer> boites = findBoitesByDossierIdOrReference(em, idDossier, null, null);
        return boites.isEmpty() ? null : boites.get(0);
    }

    public static Integer findPrimaryBoite(EntityManager em, Long idDossier, String pin, String relation) {
        List<Integer> boites = findBoitesByDossierIdOrReference(em, idDossier, pin, relation);
        return boites.isEmpty() ? null : boites.get(0);
    }

    public static ArchEmplacement findPrimaryEmplacement(EntityManager em, Long idDossier) {
        if (idDossier == null) {
            return null;
        }
        ArchDossier dossier = em.find(ArchDossier.class, idDossier);
        return findPrimaryEmplacement(em, dossier);
    }

    public static ArchEmplacement findPrimaryEmplacement(EntityManager em, ArchDossier dossier) {
        if (dossier == null) {
            return null;
        }

        String filiale = resolveDossierFiliale(dossier);
        return findEmplacementByBoite(
                em,
                findPrimaryBoite(em, dossier.getIdDossier(), dossier.getPin(), dossier.getRelation()),
                filiale
        );
    }

    public static ArchEmplacement findEmplacementByBoite(EntityManager em, Integer boite) {
        return findEmplacementByBoite(em, boite, null);
    }

    public static ArchEmplacement findEmplacementByBoite(EntityManager em, Integer boite, String filiale) {
        if (boite == null) {
            return null;
        }

        String normalizedFiliale = FilialeUtil.normalizeKey(filiale);
        String jpql = "select e from " + ArchEmplacement.class.getSimpleName() + " e " +
                "where e.boite = :boite ";
        if (!normalizedFiliale.isBlank()) {
            jpql += "and lower(trim(e.filiale)) = :filiale ";
        }
        jpql += "order by e.idEmplacement";

        var query = em.createQuery(jpql, ArchEmplacement.class)
                .setParameter("boite", boite)
                .setMaxResults(1);
        if (!normalizedFiliale.isBlank()) {
            query.setParameter("filiale", normalizedFiliale);
        }

        List<ArchEmplacement> rows = query.getResultList();
        return rows.isEmpty() ? null : rows.get(0);
    }

    public static List<Integer> findBoitesByFiliale(EntityManager em, String filiale) {
        String normalizedFiliale = FilialeUtil.normalizeKey(filiale);
        if (normalizedFiliale.isBlank()) {
            return Collections.emptyList();
        }

        return em.createQuery(
                        "select distinct e.boite from " + ArchEmplacement.class.getSimpleName() + " e " +
                                "where e.boite is not null and lower(trim(e.filiale)) = :filiale " +
                                "order by e.boite",
                        Integer.class)
                .setParameter("filiale", normalizedFiliale)
                .getResultList();
    }

    public static void replaceBoites(EntityManager em, Long idDossier, String pin, String relation, Collection<Integer> rawBoites) {
        if (idDossier == null) {
            return;
        }

        em.createQuery("delete from " + DossierEmp.class.getSimpleName() + " de where de.idDossier = :idDossier")
                .setParameter("idDossier", idDossier)
                .executeUpdate();

        String cleanPin = safeTrim(pin);
        String cleanRelation = safeTrim(relation);
        for (Integer boite : normalizeBoites(rawBoites)) {
            DossierEmp row = new DossierEmp();
            row.setIdDossier(idDossier);
            row.setBoite(boite);
            row.setPin(cleanPin);
            row.setRelation(cleanRelation);
            em.persist(row);
        }
    }

    public static void syncReferenceFields(EntityManager em, Long idDossier, String pin, String relation) {
        if (idDossier == null) {
            return;
        }

        em.createQuery(
                        "update " + DossierEmp.class.getSimpleName() + " de " +
                                "set de.pin = :pin, de.relation = :relation " +
                                "where de.idDossier = :idDossier")
                .setParameter("pin", safeTrim(pin))
                .setParameter("relation", safeTrim(relation))
                .setParameter("idDossier", idDossier)
                .executeUpdate();
    }

    public static String formatBoites(Collection<Integer> rawBoites) {
        List<Integer> boites = normalizeBoites(rawBoites);
        if (boites.isEmpty()) {
            return "";
        }

        List<String> parts = new ArrayList<>(boites.size());
        for (Integer boite : boites) {
            parts.add(String.valueOf(boite));
        }
        return String.join(", ", parts);
    }

    public static String findBoitesSummary(EntityManager em, Long idDossier) {
        return formatBoites(findBoitesByDossierIdOrReference(em, idDossier, null, null));
    }

    public static String findBoitesSummary(EntityManager em, Long idDossier, String pin, String relation) {
        return formatBoites(findBoitesByDossierIdOrReference(em, idDossier, pin, relation));
    }

    public static boolean matchesSessionFiliale(ArchDossier dossier, String filiale) {
        return dossier != null && FilialeUtil.matches(dossier.getFiliale(), dossier.getIdFiliale(), filiale);
    }

    public static boolean matchesSessionFiliale(ArchEmplacement emplacement, String filiale) {
        return emplacement != null && FilialeUtil.matches(emplacement.getFiliale(), null, filiale);
    }

    private static String resolveDossierFiliale(ArchDossier dossier) {
        if (dossier == null) {
            return "";
        }
        String current = FilialeUtil.normalizeKey(dossier.getFiliale());
        if (!current.isBlank()) {
            return current;
        }
        return FilialeUtil.normalizeKey(dossier.getIdFiliale());
    }

    private static String safeTrim(String value) {
        return value == null ? null : value.trim();
    }

    private static String normalizeReferenceValue(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static String toStringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String buildPinRelationKey(String pin, String relation) {
        return pin + "||" + relation;
    }

    private static void appendBoite(Map<String, List<Integer>> target, String key, Integer boite) {
        if (key == null || key.isBlank() || boite == null) {
            return;
        }
        List<Integer> boites = target.computeIfAbsent(key, ignored -> new ArrayList<>());
        if (!boites.contains(boite)) {
            boites.add(boite);
        }
    }
}
