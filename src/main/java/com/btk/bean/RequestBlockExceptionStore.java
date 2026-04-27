package com.btk.bean;

import com.btk.util.FilialeUtil;

import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory store for one-shot admin exceptions that bypass dossier blocking once.
 */
public final class RequestBlockExceptionStore {

    private static final ConcurrentMap<String, BlockExceptionGrant> ACTIVE_BY_USER = new ConcurrentHashMap<>();
    private static final AtomicLong SEQ = new AtomicLong(1L);

    private RequestBlockExceptionStore() {
    }

    public static BlockExceptionGrant grantOneShot(String filiale,
                                                   String userIdentifier,
                                                   String grantedBy,
                                                   Long blockedRequestId,
                                                   String blockedPin,
                                                   String blockedBoite) {
        String key = buildKey(filiale, userIdentifier);
        if (key.isBlank()) {
            return null;
        }

        BlockExceptionGrant grant = new BlockExceptionGrant(
                SEQ.getAndIncrement(),
                safe(userIdentifier),
                normalizeFiliale(filiale),
                safe(grantedBy),
                blockedRequestId,
                safe(blockedPin),
                safe(blockedBoite),
                new Date()
        );
        ACTIVE_BY_USER.put(key, grant);
        return grant;
    }

    public static boolean hasActiveGrant(String filiale, String userIdentifier) {
        String key = buildKey(filiale, userIdentifier);
        return !key.isBlank() && ACTIVE_BY_USER.containsKey(key);
    }

    public static boolean hasActiveGrant(String filiale, String unix, String cuti) {
        for (String key : buildCandidateKeys(filiale, unix, cuti)) {
            if (ACTIVE_BY_USER.containsKey(key)) {
                return true;
            }
        }
        return false;
    }

    public static BlockExceptionGrant consumeGrant(String filiale, String unix, String cuti) {
        for (String key : buildCandidateKeys(filiale, unix, cuti)) {
            BlockExceptionGrant grant = ACTIVE_BY_USER.remove(key);
            if (grant != null) {
                return grant;
            }
        }
        return null;
    }

    private static Set<String> buildCandidateKeys(String filiale, String unix, String cuti) {
        Set<String> keys = new LinkedHashSet<>();
        String unixKey = buildKey(filiale, unix);
        if (!unixKey.isBlank()) {
            keys.add(unixKey);
        }
        String cutiKey = buildKey(filiale, cuti);
        if (!cutiKey.isBlank()) {
            keys.add(cutiKey);
        }
        return keys;
    }

    private static String buildKey(String filiale, String userIdentifier) {
        String normalizedUser = normalize(userIdentifier);
        if (normalizedUser.isBlank()) {
            return "";
        }

        String normalizedFiliale = normalizeFiliale(filiale);
        if (normalizedFiliale.isBlank()) {
            return normalizedUser;
        }
        return normalizedFiliale + "|" + normalizedUser;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private static String normalizeFiliale(String value) {
        return normalize(FilialeUtil.normalizeKey(value));
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    public static final class BlockExceptionGrant {
        private final long id;
        private final String userIdentifier;
        private final String filiale;
        private final String grantedBy;
        private final Long blockedRequestId;
        private final String blockedPin;
        private final String blockedBoite;
        private final Date createdAt;

        private BlockExceptionGrant(long id,
                                    String userIdentifier,
                                    String filiale,
                                    String grantedBy,
                                    Long blockedRequestId,
                                    String blockedPin,
                                    String blockedBoite,
                                    Date createdAt) {
            this.id = id;
            this.userIdentifier = userIdentifier;
            this.filiale = filiale;
            this.grantedBy = grantedBy;
            this.blockedRequestId = blockedRequestId;
            this.blockedPin = blockedPin;
            this.blockedBoite = blockedBoite;
            this.createdAt = createdAt;
        }

        public long getId() {
            return id;
        }

        public String getUserIdentifier() {
            return userIdentifier;
        }

        public String getFiliale() {
            return filiale;
        }

        public String getGrantedBy() {
            return grantedBy;
        }

        public Long getBlockedRequestId() {
            return blockedRequestId;
        }

        public String getBlockedPin() {
            return blockedPin;
        }

        public String getBlockedBoite() {
            return blockedBoite;
        }

        public Date getCreatedAt() {
            return createdAt;
        }
    }
}
