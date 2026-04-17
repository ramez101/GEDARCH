package com.btk.bean;

import com.btk.util.FilialeUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory store used to send reminder notifications between active sessions.
 * This avoids requiring DB schema changes for reminder events.
 */
public final class RappelNotificationStore {

    private static final ConcurrentMap<String, List<RappelNotification>> BY_RECIPIENT = new ConcurrentHashMap<>();
    private static final AtomicLong SEQ = new AtomicLong(1L);
    private static final int MAX_PER_RECIPIENT = 200;

    private RappelNotificationStore() {
    }

    public static RappelNotification publishReminder(Long idDemande,
                                                     String pin,
                                                     String boite,
                                                     String emetteur,
                                                     String recepteur,
                                                     String sentBy,
                                                     String filiale) {
        String recipientKey = buildRecipientKey(filiale, emetteur);
        if (recipientKey.isBlank()) {
            return null;
        }

        RappelNotification event = new RappelNotification(
                SEQ.getAndIncrement(),
                idDemande,
                safe(pin),
                safe(boite),
                safe(emetteur),
                safe(recepteur),
                safe(sentBy),
                normalizeFiliale(filiale),
                new Date()
        );

        List<RappelNotification> list = BY_RECIPIENT.computeIfAbsent(
                recipientKey,
                key -> Collections.synchronizedList(new ArrayList<>())
        );

        synchronized (list) {
            list.add(0, event);
            if (list.size() > MAX_PER_RECIPIENT) {
                list.subList(MAX_PER_RECIPIENT, list.size()).clear();
            }
        }

        return event;
    }

    public static List<RappelNotification> findForRecipient(String unix, String cuti, String filiale) {
        Map<Long, RappelNotification> merged = new LinkedHashMap<>();
        mergeByKey(merged, buildRecipientKey(filiale, unix));
        mergeByKey(merged, buildRecipientKey(filiale, cuti));

        List<RappelNotification> result = new ArrayList<>(merged.values());
        result.sort((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()));
        return result;
    }

    private static void mergeByKey(Map<Long, RappelNotification> out, String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        List<RappelNotification> list = BY_RECIPIENT.get(key);
        if (list == null || list.isEmpty()) {
            return;
        }

        synchronized (list) {
            for (RappelNotification item : list) {
                if (item == null) {
                    continue;
                }
                out.putIfAbsent(item.getId(), item);
            }
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private static String normalizeFiliale(String value) {
        return normalize(FilialeUtil.normalizeKey(value));
    }

    private static String buildRecipientKey(String filiale, String recipient) {
        String normalizedRecipient = normalize(recipient);
        if (normalizedRecipient.isBlank()) {
            return "";
        }

        String normalizedFiliale = normalizeFiliale(filiale);
        if (normalizedFiliale.isBlank()) {
            return normalizedRecipient;
        }
        return normalizedFiliale + "|" + normalizedRecipient;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    public static final class RappelNotification {
        private final long id;
        private final Long idDemande;
        private final String pin;
        private final String boite;
        private final String emetteur;
        private final String recepteur;
        private final String sentBy;
        private final String filiale;
        private final Date createdAt;

        private RappelNotification(long id,
                                   Long idDemande,
                                   String pin,
                                   String boite,
                                   String emetteur,
                                   String recepteur,
                                   String sentBy,
                                   String filiale,
                                   Date createdAt) {
            this.id = id;
            this.idDemande = idDemande;
            this.pin = pin;
            this.boite = boite;
            this.emetteur = emetteur;
            this.recepteur = recepteur;
            this.sentBy = sentBy;
            this.filiale = filiale;
            this.createdAt = createdAt;
        }

        public long getId() {
            return id;
        }

        public Long getIdDemande() {
            return idDemande;
        }

        public String getPin() {
            return pin;
        }

        public String getBoite() {
            return boite;
        }

        public String getEmetteur() {
            return emetteur;
        }

        public String getRecepteur() {
            return recepteur;
        }

        public String getSentBy() {
            return sentBy;
        }

        public String getFiliale() {
            return filiale;
        }

        public Date getCreatedAt() {
            return createdAt;
        }
    }
}
