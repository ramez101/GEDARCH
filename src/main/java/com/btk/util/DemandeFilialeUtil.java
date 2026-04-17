package com.btk.util;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

public final class DemandeFilialeUtil {

    private static final Map<String, Boolean> COLUMN_CACHE = new ConcurrentHashMap<>();

    private DemandeFilialeUtil() {
    }

    public static boolean hasFilialeColumn(EntityManager em) {
        return hasColumn(em, "DEMANDE_DOSSIER", "FILIALE");
    }

    public static String buildPredicate(EntityManager em, String demandeReference, String sessionFiliale, String sessionLegacyFiliale) {
        String normalizedFiliale = normalizeSessionFiliale(sessionFiliale);
        if (normalizedFiliale.isBlank()) {
            return "1 = 1";
        }

        String fallbackPredicate = buildArchiveFallbackPredicate(demandeReference);
        if (hasFilialeColumn(em)) {
            String demandeFiliale = qualified(demandeReference, "FILIALE");
            return "(" +
                    "LOWER(TRIM(" + demandeFiliale + ")) = :sessionFiliale " +
                    "OR LOWER(TRIM(" + demandeFiliale + ")) = :sessionLegacyFiliale " +
                    "OR (" + demandeFiliale + " IS NULL AND " + fallbackPredicate + ")" +
                    ")";
        }
        return fallbackPredicate;
    }

    public static void bindParameters(Query query, String sessionFiliale, String sessionLegacyFiliale) {
        if (query == null) {
            return;
        }

        String normalizedFiliale = normalizeSessionFiliale(sessionFiliale);
        if (normalizedFiliale.isBlank()) {
            return;
        }

        query.setParameter("sessionFiliale", normalizedFiliale);
        query.setParameter("sessionLegacyFiliale", normalizeLegacyFiliale(sessionLegacyFiliale, normalizedFiliale));
    }

    private static String buildArchiveFallbackPredicate(String demandeReference) {
        String pinColumn = "UPPER(TRIM(" + qualified(demandeReference, "PIN") + "))";
        String boiteColumn = "TRIM(" + qualified(demandeReference, "BOITE") + ")";

        return "EXISTS (" +
                "SELECT 1 " +
                "FROM ARCH_DOSSIER ad " +
                "WHERE UPPER(TRIM(ad.PIN)) = " + pinColumn + " " +
                "AND (" +
                    "LOWER(TRIM(ad.FILIALE)) = :sessionFiliale " +
                    "OR LOWER(TRIM(ad.FILIALE)) = :sessionLegacyFiliale " +
                    "OR (" +
                        "ad.FILIALE IS NULL " +
                        "AND (" +
                            "LOWER(TRIM(ad.ID_FILIALE)) = :sessionFiliale " +
                            "OR LOWER(TRIM(ad.ID_FILIALE)) = :sessionLegacyFiliale" +
                        ")" +
                    ")" +
                ") " +
                "AND (" +
                    "NVL(" + boiteColumn + ", '') = '' " +
                    "OR NVL((" +
                        "SELECT LISTAGG(TO_CHAR(de.BOITE), ', ') WITHIN GROUP (ORDER BY de.BOITE) " +
                        "FROM DOSSIER_EMP de " +
                        "WHERE de.ID_DOSSIER = ad.ID_DOSSIER" +
                    "), '') = NVL(" + boiteColumn + ", '')" +
                ")" +
                ")";
    }

    private static boolean hasColumn(EntityManager em, String tableName, String columnName) {
        if (em == null) {
            return false;
        }

        String cacheKey = (tableName + "." + columnName).toUpperCase(Locale.ROOT);
        Boolean cached = COLUMN_CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        Number count = (Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM USER_TAB_COLUMNS " +
                        "WHERE TABLE_NAME = :tableName " +
                        "AND COLUMN_NAME = :columnName")
                .setParameter("tableName", tableName.toUpperCase(Locale.ROOT))
                .setParameter("columnName", columnName.toUpperCase(Locale.ROOT))
                .getSingleResult();

        boolean exists = count != null && count.longValue() > 0L;
        COLUMN_CACHE.put(cacheKey, exists);
        return exists;
    }

    private static String qualified(String reference, String columnName) {
        if (reference == null || reference.isBlank()) {
            return columnName;
        }
        return reference + "." + columnName;
    }

    private static String normalizeSessionFiliale(String value) {
        return normalizeLower(FilialeUtil.normalizeKey(value));
    }

    private static String normalizeLegacyFiliale(String value, String normalizedSessionFiliale) {
        String normalizedLegacy = normalizeLower(value);
        if (!normalizedLegacy.isBlank()) {
            return normalizedLegacy;
        }
        return normalizeLower(FilialeUtil.toLegacyId(normalizedSessionFiliale));
    }

    private static String normalizeLower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
