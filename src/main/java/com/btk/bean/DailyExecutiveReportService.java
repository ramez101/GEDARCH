package com.btk.bean;

import com.btk.util.FilialeUtil;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;

import javax.naming.InitialContext;
import javax.naming.NamingException;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

@ApplicationScoped
public class DailyExecutiveReportService implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final Logger LOGGER = Logger.getLogger(DailyExecutiveReportService.class.getName());

    private static final String CONFIG_FILE = "daily-report.properties";

    private static final String KEY_ENABLED = "btk.daily.report.enabled";
    private static final String KEY_HOUR = "btk.daily.report.schedule.hour";
    private static final String KEY_MINUTE = "btk.daily.report.schedule.minute";
    private static final String KEY_RECIPIENTS = "btk.daily.report.recipients";
    private static final String KEY_FROM = "btk.daily.report.from";
    private static final String KEY_SUBJECT_PREFIX = "btk.daily.report.subject.prefix";
    private static final String KEY_TIMEZONE = "btk.daily.report.timezone";
    private static final String KEY_MAIL_JNDI = "btk.daily.report.mail.jndi";
    private static final String KEY_SMTP_HOST = "btk.daily.report.mail.smtp.host";
    private static final String KEY_SMTP_PORT = "btk.daily.report.mail.smtp.port";
    private static final String KEY_SMTP_USERNAME = "btk.daily.report.mail.smtp.username";
    private static final String KEY_SMTP_PASSWORD = "btk.daily.report.mail.smtp.password";
    private static final String KEY_SMTP_TLS = "btk.daily.report.mail.smtp.starttls";

    private static final Properties FILE_CONFIG = loadFileConfig();
    private static EntityManagerFactory emf;

    public boolean isDailyReportEnabled() {
        return readBoolean(KEY_ENABLED, "BTK_DAILY_REPORT_ENABLED", true);
    }

    public int getScheduleHour() {
        return clamp(readInt(KEY_HOUR, "BTK_DAILY_REPORT_SCHEDULE_HOUR", 7), 0, 23);
    }

    public int getScheduleMinute() {
        return clamp(readInt(KEY_MINUTE, "BTK_DAILY_REPORT_SCHEDULE_MINUTE", 30), 0, 59);
    }

    public void sendDailyReport() {
        if (!isDailyReportEnabled()) {
            LOGGER.info("Daily executive report is disabled.");
            return;
        }

        List<String> recipients = resolveRecipients();
        if (recipients.isEmpty()) {
            LOGGER.warning("Daily executive report skipped: no recipients configured.");
            return;
        }

        EntityManager em = getEMF().createEntityManager();
        try {
            ExecutiveSnapshot snapshot = loadSnapshot(em);
            Session mailSession = resolveMailSession();

            MimeMessage message = new MimeMessage(mailSession);
            message.setFrom(new InternetAddress(resolveFromAddress()));
            for (String recipient : recipients) {
                message.addRecipient(Message.RecipientType.TO, new InternetAddress(recipient));
            }

            String subject = buildSubject(snapshot.generatedAt);
            message.setSubject(subject, StandardCharsets.UTF_8.name());
            message.setSentDate(new Date());
            message.setContent(buildHtmlBody(snapshot), "text/html; charset=UTF-8");

            Transport.send(message);
            LOGGER.info("Daily executive report sent to " + recipients.size() + " recipient(s).");
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Unable to send daily executive report.", e);
        } finally {
            em.close();
        }
    }

    private ExecutiveSnapshot loadSnapshot(EntityManager em) {
        long totalDossiers = toLong(em.createNativeQuery("select count(*) from ARCH_DOSSIER").getSingleResult());
        long totalBoites = toLong(em.createNativeQuery("select count(distinct BOITE) from ARCH_EMPLACEMENT where BOITE is not null").getSingleResult());
        long totalDocuments = toLong(em.createNativeQuery("select count(*) from ARCH_DOCUMENT").getSingleResult());
        long pendingDemandes = toLong(em.createNativeQuery(
                "select count(*) from DEMANDE_DOSSIER where DATE_APPROUVE is null and DATE_RESTITUTION is null").getSingleResult());
        long overdueDemandes = toLong(em.createNativeQuery(
                "select count(*) from DEMANDE_DOSSIER " +
                        "where DATE_APPROUVE is not null " +
                        "and DATE_RESTITUTION is null " +
                        "and trunc(sysdate) - trunc(DATE_APPROUVE) >= 10").getSingleResult());
        long restitutionsToday = toLong(em.createNativeQuery(
                "select count(*) from DEMANDE_DOSSIER where DATE_RESTITUTION is not null and trunc(DATE_RESTITUTION) = trunc(sysdate)").getSingleResult());

        List<FilialeStat> filialeStats = loadFilialeStats(em);
        List<OverdueStat> overdueStats = loadOverdueStats(em);

        return new ExecutiveSnapshot(
                LocalDateTime.now(resolveZone()),
                totalDossiers,
                totalBoites,
                totalDocuments,
                pendingDemandes,
                overdueDemandes,
                restitutionsToday,
                filialeStats,
                overdueStats
        );
    }

    private List<FilialeStat> loadFilialeStats(EntityManager em) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                        "select lower(trim(nvl(FILIALE, ID_FILIALE))) as FILIALE_KEY, count(*) as TOTAL " +
                                "from ARCH_DOSSIER " +
                                "group by lower(trim(nvl(FILIALE, ID_FILIALE))) " +
                                "order by FILIALE_KEY")
                .getResultList();

        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }

        List<FilialeStat> stats = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            String filialeKey = safeString(row[0]);
            stats.add(new FilialeStat(
                    FilialeUtil.toLabel(filialeKey),
                    toLong(row[1])
            ));
        }
        return stats;
    }

    private List<OverdueStat> loadOverdueStats(EntityManager em) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                        "select ID_DEMANDE, PIN, BOITE, DATE_APPROUVE, JOURS_RETARD from (" +
                                "select ID_DEMANDE, PIN, BOITE, DATE_APPROUVE, " +
                                "(trunc(sysdate) - trunc(DATE_APPROUVE)) as JOURS_RETARD " +
                                "from DEMANDE_DOSSIER " +
                                "where DATE_APPROUVE is not null " +
                                "and DATE_RESTITUTION is null " +
                                "and trunc(sysdate) - trunc(DATE_APPROUVE) >= 10 " +
                                "order by DATE_APPROUVE asc" +
                                ") where rownum <= 10")
                .getResultList();

        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }

        List<OverdueStat> stats = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            stats.add(new OverdueStat(
                    toLong(row[0]),
                    safeString(row[1]),
                    toInteger(row[2]),
                    toDate(row[3]),
                    toLong(row[4])
            ));
        }
        return stats;
    }

    private String buildSubject(LocalDateTime generatedAt) {
        String prefix = readString(KEY_SUBJECT_PREFIX, "BTK_DAILY_REPORT_SUBJECT_PREFIX", "[BTK]");
        return prefix + " Rapport quotidien archives - " + generatedAt.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
    }

    private String buildHtmlBody(ExecutiveSnapshot snapshot) {
        String generatedAt = snapshot.generatedAt.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));

        StringBuilder html = new StringBuilder(2600);
        html.append("<html><body style='font-family:Segoe UI,Arial,sans-serif;color:#133047;'>");
        html.append("<h2 style='margin-bottom:4px;'>Rapport quotidien BTK - Archives</h2>");
        html.append("<p style='margin-top:0;color:#4b6680;'>Generation: ").append(generatedAt).append(" (")
                .append(escapeHtml(resolveZone().toString())).append(")</p>");

        html.append("<table cellpadding='8' cellspacing='0' style='border-collapse:collapse;border:1px solid #d7e3ef;min-width:520px;'>");
        appendMetricRow(html, "Total dossiers", snapshot.totalDossiers);
        appendMetricRow(html, "Total boites actives", snapshot.totalBoites);
        appendMetricRow(html, "Total documents scannes", snapshot.totalDocuments);
        appendMetricRow(html, "Demandes en attente", snapshot.pendingDemandes);
        appendMetricRow(html, "Demandes en retard (>= 10 jours)", snapshot.overdueDemandes);
        appendMetricRow(html, "Restitutions aujourd'hui", snapshot.restitutionsToday);
        html.append("</table>");

        if (!snapshot.filialeStats.isEmpty()) {
            html.append("<h3 style='margin-top:20px;'>Repartition dossiers par filiale</h3>");
            html.append("<table cellpadding='8' cellspacing='0' style='border-collapse:collapse;border:1px solid #d7e3ef;min-width:340px;'>");
            html.append("<tr style='background:#edf4fb;'><th align='left'>Filiale</th><th align='right'>Dossiers</th></tr>");
            for (FilialeStat stat : snapshot.filialeStats) {
                html.append("<tr>")
                        .append("<td style='border-top:1px solid #e4edf6;'>").append(escapeHtml(stat.filiale)).append("</td>")
                        .append("<td align='right' style='border-top:1px solid #e4edf6;'>").append(stat.total).append("</td>")
                        .append("</tr>");
            }
            html.append("</table>");
        }

        if (!snapshot.overdueStats.isEmpty()) {
            html.append("<h3 style='margin-top:20px;color:#993a3a;'>Top dossiers en retard</h3>");
            html.append("<table cellpadding='8' cellspacing='0' style='border-collapse:collapse;border:1px solid #e8c9c9;min-width:620px;'>");
            html.append("<tr style='background:#fff2f2;'><th align='left'>Demande</th><th align='left'>PIN</th><th align='left'>Boite</th><th align='left'>Date sortie</th><th align='right'>Jours</th></tr>");
            DateTimeFormatter dateFmt = DateTimeFormatter.ofPattern("dd/MM/yyyy");
            for (OverdueStat stat : snapshot.overdueStats) {
                String sortie = "-";
                if (stat.dateSortie != null) {
                    sortie = stat.dateSortie.toInstant().atZone(resolveZone()).toLocalDate().format(dateFmt);
                }
                html.append("<tr>")
                        .append("<td style='border-top:1px solid #f0d9d9;'>").append(stat.idDemande == 0L ? "-" : String.valueOf(stat.idDemande)).append("</td>")
                        .append("<td style='border-top:1px solid #f0d9d9;'>").append(escapeHtml(stat.pin)).append("</td>")
                        .append("<td style='border-top:1px solid #f0d9d9;'>").append(stat.boite == null ? "-" : stat.boite).append("</td>")
                        .append("<td style='border-top:1px solid #f0d9d9;'>").append(escapeHtml(sortie)).append("</td>")
                        .append("<td align='right' style='border-top:1px solid #f0d9d9;'>").append(stat.daysOverdue).append("</td>")
                        .append("</tr>");
            }
            html.append("</table>");
        }

        html.append("<p style='margin-top:18px;color:#72879b;font-size:12px;'>Message automatique - ne pas repondre.</p>");
        html.append("</body></html>");

        return html.toString();
    }

    private void appendMetricRow(StringBuilder html, String label, long value) {
        html.append("<tr>")
                .append("<td style='border-top:1px solid #e4edf6;'>").append(escapeHtml(label)).append("</td>")
                .append("<td align='right' style='border-top:1px solid #e4edf6;font-weight:700;'>").append(value).append("</td>")
                .append("</tr>");
    }

    private Session resolveMailSession() throws NamingException {
        String jndiName = readString(KEY_MAIL_JNDI, "BTK_DAILY_REPORT_MAIL_JNDI", "java:jboss/mail/Default");
        if (!jndiName.isBlank()) {
            try {
                return (Session) new InitialContext().lookup(jndiName);
            } catch (NamingException e) {
                LOGGER.warning("Mail session JNDI unavailable (" + jndiName + "). Fallback to SMTP properties.");
            }
        }
        return buildSmtpSession();
    }

    private Session buildSmtpSession() {
        String host = readString(KEY_SMTP_HOST, "BTK_DAILY_REPORT_SMTP_HOST", "");
        if (host.isBlank()) {
            throw new IllegalStateException("SMTP host is not configured and JNDI mail session was not found.");
        }

        String port = readString(KEY_SMTP_PORT, "BTK_DAILY_REPORT_SMTP_PORT", "25");
        String username = readString(KEY_SMTP_USERNAME, "BTK_DAILY_REPORT_SMTP_USERNAME", "");
        String password = readString(KEY_SMTP_PASSWORD, "BTK_DAILY_REPORT_SMTP_PASSWORD", "");
        boolean startTls = readBoolean(KEY_SMTP_TLS, "BTK_DAILY_REPORT_SMTP_STARTTLS", true);

        Properties props = new Properties();
        props.setProperty("mail.smtp.host", host);
        props.setProperty("mail.smtp.port", port);
        props.setProperty("mail.smtp.starttls.enable", String.valueOf(startTls));

        if (!username.isBlank()) {
            props.setProperty("mail.smtp.auth", "true");
            return Session.getInstance(props, new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(username, password);
                }
            });
        }

        props.setProperty("mail.smtp.auth", "false");
        return Session.getInstance(props);
    }

    private List<String> resolveRecipients() {
        String raw = readString(KEY_RECIPIENTS, "BTK_DAILY_REPORT_RECIPIENTS", "");
        if (raw.isBlank()) {
            return Collections.emptyList();
        }

        String[] tokens = raw.split("[,;\\s]+");
        List<String> recipients = new ArrayList<>();
        for (String token : tokens) {
            if (token == null) {
                continue;
            }
            String clean = token.trim();
            if (!clean.isBlank()) {
                recipients.add(clean);
            }
        }
        return recipients;
    }

    private String resolveFromAddress() {
        String configured = readString(KEY_FROM, "BTK_DAILY_REPORT_FROM", "no-reply@btk.local");
        return configured.isBlank() ? "no-reply@btk.local" : configured;
    }

    private ZoneId resolveZone() {
        String zone = readString(KEY_TIMEZONE, "BTK_DAILY_REPORT_TIMEZONE", "Africa/Tunis");
        try {
            return ZoneId.of(zone);
        } catch (Exception e) {
            return ZoneId.systemDefault();
        }
    }

    private static synchronized EntityManagerFactory getEMF() {
        if (emf == null || !emf.isOpen()) {
            emf = Persistence.createEntityManagerFactory("btk");
        }
        return emf;
    }

    private String readString(String key, String envKey, String fallback) {
        String sys = trimToNull(System.getProperty(key));
        if (sys != null) {
            return sys;
        }

        String env = trimToNull(System.getenv(envKey));
        if (env != null) {
            return env;
        }

        String fromFile = trimToNull(FILE_CONFIG.getProperty(key));
        if (fromFile != null) {
            return fromFile;
        }

        return fallback == null ? "" : fallback;
    }

    private boolean readBoolean(String key, String envKey, boolean fallback) {
        String value = readString(key, envKey, String.valueOf(fallback));
        return "true".equalsIgnoreCase(value)
                || "1".equals(value)
                || "yes".equalsIgnoreCase(value)
                || "oui".equalsIgnoreCase(value);
    }

    private int readInt(String key, String envKey, int fallback) {
        String value = readString(key, envKey, String.valueOf(fallback));
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    private static Properties loadFileConfig() {
        Properties props = new Properties();
        try (InputStream in = DailyExecutiveReportService.class.getClassLoader().getResourceAsStream(CONFIG_FILE)) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Unable to read " + CONFIG_FILE, e);
        }
        return props;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String safeString(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private long toLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    private Integer toInteger(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Date toDate(Object value) {
        if (value instanceof Date) {
            return (Date) value;
        }
        if (value instanceof java.sql.Timestamp) {
            return new Date(((java.sql.Timestamp) value).getTime());
        }
        return null;
    }

    private String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        String escaped = value;
        escaped = escaped.replace("&", "&amp;");
        escaped = escaped.replace("<", "&lt;");
        escaped = escaped.replace(">", "&gt;");
        escaped = escaped.replace("\"", "&quot;");
        return escaped;
    }

    private static class ExecutiveSnapshot {
        final LocalDateTime generatedAt;
        final long totalDossiers;
        final long totalBoites;
        final long totalDocuments;
        final long pendingDemandes;
        final long overdueDemandes;
        final long restitutionsToday;
        final List<FilialeStat> filialeStats;
        final List<OverdueStat> overdueStats;

        ExecutiveSnapshot(LocalDateTime generatedAt,
                          long totalDossiers,
                          long totalBoites,
                          long totalDocuments,
                          long pendingDemandes,
                          long overdueDemandes,
                          long restitutionsToday,
                          List<FilialeStat> filialeStats,
                          List<OverdueStat> overdueStats) {
            this.generatedAt = generatedAt;
            this.totalDossiers = totalDossiers;
            this.totalBoites = totalBoites;
            this.totalDocuments = totalDocuments;
            this.pendingDemandes = pendingDemandes;
            this.overdueDemandes = overdueDemandes;
            this.restitutionsToday = restitutionsToday;
            this.filialeStats = filialeStats == null ? Collections.emptyList() : filialeStats;
            this.overdueStats = overdueStats == null ? Collections.emptyList() : overdueStats;
        }
    }

    private static class FilialeStat {
        final String filiale;
        final long total;

        FilialeStat(String filiale, long total) {
            this.filiale = filiale == null || filiale.isBlank() ? "N/A" : filiale;
            this.total = total;
        }
    }

    private static class OverdueStat {
        final long idDemande;
        final String pin;
        final Integer boite;
        final Date dateSortie;
        final long daysOverdue;

        OverdueStat(long idDemande, String pin, Integer boite, Date dateSortie, long daysOverdue) {
            this.idDemande = idDemande;
            this.pin = pin == null ? "" : pin;
            this.boite = boite;
            this.dateSortie = dateSortie;
            this.daysOverdue = daysOverdue;
        }
    }
}
