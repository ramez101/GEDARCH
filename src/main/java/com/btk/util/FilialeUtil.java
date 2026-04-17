package com.btk.util;

public final class FilialeUtil {

    private FilialeUtil() {
    }

    public static String normalizeKey(String value) {
        if (value == null) {
            return "";
        }

        String normalized = value.trim().toLowerCase();
        if (normalized.isBlank()) {
            return "";
        }

        if ("btk-bank".equals(normalized) || "bank".equals(normalized)) {
            return "bank";
        }
        if ("btk-finance".equals(normalized) || "finance".equals(normalized)) {
            return "finance";
        }
        return normalized;
    }

    public static String toLegacyId(String value) {
        String normalized = normalizeKey(value);
        if ("bank".equals(normalized)) {
            return "btk-bank";
        }
        if ("finance".equals(normalized)) {
            return "btk-finance";
        }
        return normalized;
    }

    public static String toLabel(String value) {
        String normalized = normalizeKey(value);
        if ("bank".equals(normalized)) {
            return "BTK Bank";
        }
        if ("finance".equals(normalized)) {
            return "BTK Finance";
        }
        return value == null ? "" : value.trim();
    }

    public static boolean matches(String filiale, String legacyFiliale, String expectedFiliale) {
        String expected = normalizeKey(expectedFiliale);
        if (expected.isBlank()) {
            return true;
        }

        String current = normalizeKey(filiale);
        if (current.isBlank()) {
            current = normalizeKey(legacyFiliale);
        }
        return expected.equals(current);
    }
}
