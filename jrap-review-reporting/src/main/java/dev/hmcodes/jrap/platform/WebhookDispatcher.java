package dev.hmcodes.jrap.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hmcodes.jrap.registry.domain.Audit;
import dev.hmcodes.jrap.registry.domain.Finding;
import dev.hmcodes.jrap.registry.platform.SettingsService;
import dev.hmcodes.jrap.registry.platform.Webhook;
import dev.hmcodes.jrap.registry.platform.WebhookDelivery;
import dev.hmcodes.jrap.registry.platform.WebhookDeliveryRepository;
import dev.hmcodes.jrap.registry.platform.WebhookRepository;
import dev.hmcodes.jrap.registry.repo.AuditRepository;
import dev.hmcodes.jrap.registry.repo.FindingRepository;
import dev.hmcodes.jrap.tenancy.service.TenantTx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * §3.2.2 webhooks: polls for newly completed audits and newly created critical findings
 * past a persisted watermark, and POSTs signed JSON (HMAC-SHA256, X-JRAP-Signature) to
 * each subscribed endpoint. Every attempt lands in the webhook_delivery log. Poll-based
 * on purpose: producers (crawl/analysis) stay decoupled from delivery.
 */
@Component
public class WebhookDispatcher {

    static final String WATERMARK_AUDITS = "webhooks.watermark.audits";
    static final String WATERMARK_FINDINGS = "webhooks.watermark.findings";

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);

    private final WebhookRepository webhooks;
    private final WebhookDeliveryRepository deliveries;
    private final AuditRepository audits;
    private final FindingRepository findings;
    private final SettingsService settings;
    private final TenantTx tenantTx;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final HttpClient http;

    public WebhookDispatcher(WebhookRepository webhooks, WebhookDeliveryRepository deliveries,
                             AuditRepository audits, FindingRepository findings,
                             SettingsService settings, TenantTx tenantTx,
                             ObjectMapper objectMapper, Clock clock) {
        this.webhooks = webhooks;
        this.deliveries = deliveries;
        this.audits = audits;
        this.findings = findings;
        this.settings = settings;
        this.tenantTx = tenantTx;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Scheduled(fixedDelayString = "${jrap.platform.webhook-poll-ms:30000}")
    public void poll() {
        try {
            runOnce();
        } catch (Exception e) {
            log.error("Webhook dispatch iteration failed", e);
        }
    }

    /** Processes one round of pending events. Returns delivery attempts made. */
    public synchronized int runOnce() {
        Instant now = clock.instant();
        int attempts = 0;

        Instant auditMark = watermark(WATERMARK_AUDITS);
        List<Audit> completed = tenantTx.asSystem(() ->
                audits.findByStatusAndFinishedAtAfter(Audit.Status.COMPLETE, auditMark));
        for (Audit audit : completed) {
            attempts += dispatch(audit.getOrganisationId(), "audit.completed", Map.of(
                    "auditId", audit.getId().toString(),
                    "journalId", audit.getJournalId().toString(),
                    "status", audit.getStatus().name(),
                    "stage", audit.getStage().name(),
                    "pagesFetched", audit.getPagesFetched(),
                    "finishedAt", String.valueOf(audit.getFinishedAt())));
        }
        Instant newAuditMark = completed.stream().map(Audit::getFinishedAt)
                .max(Instant::compareTo).orElse(auditMark);
        if (!newAuditMark.equals(auditMark)) {
            advance(WATERMARK_AUDITS, newAuditMark);
        }

        Instant findingMark = watermark(WATERMARK_FINDINGS);
        List<Finding> critical = tenantTx.asSystem(() ->
                findings.findBySeverityAndCreatedAtAfter(Finding.Severity.CRITICAL, findingMark));
        for (Finding finding : critical) {
            attempts += dispatch(finding.getOrganisationId(), "finding.critical", Map.of(
                    "findingId", finding.getId().toString(),
                    "journalId", finding.getJournalId().toString(),
                    "auditId", finding.getAuditId() == null ? "" : finding.getAuditId().toString(),
                    "code", finding.getCode(),
                    "title", finding.getTitle(),
                    "severity", finding.getSeverity().name()));
        }
        Instant newFindingMark = critical.stream().map(Finding::getCreatedAt)
                .max(Instant::compareTo).orElse(findingMark);
        if (!newFindingMark.equals(findingMark)) {
            advance(WATERMARK_FINDINGS, newFindingMark);
        }
        return attempts;
    }

    private int dispatch(UUID organisationId, String event, Map<String, Object> data) {
        List<Webhook> targets = tenantTx.asSystem(() -> webhooks.findByActiveTrue()).stream()
                .filter(w -> w.getOrganisationId().equals(organisationId))
                .filter(w -> w.subscribes(event))
                .toList();
        int attempts = 0;
        for (Webhook webhook : targets) {
            attempts++;
            String body;
            try {
                body = objectMapper.writeValueAsString(Map.of("event", event, "data", data,
                        "sentAt", String.valueOf(clock.instant())));
            } catch (Exception e) {
                continue;
            }
            Integer status = null;
            boolean ok = false;
            try {
                HttpResponse<Void> response = http.send(HttpRequest.newBuilder(URI.create(webhook.getUrl()))
                                .timeout(Duration.ofSeconds(10))
                                .header("Content-Type", "application/json")
                                .header("X-JRAP-Event", event)
                                .header("X-JRAP-Signature", "sha256=" + hmac(webhook.getSecret(), body))
                                .POST(HttpRequest.BodyPublishers.ofString(body))
                                .build(),
                        HttpResponse.BodyHandlers.discarding());
                status = response.statusCode();
                ok = status >= 200 && status < 300;
            } catch (Exception e) {
                log.info("Webhook {} delivery failed: {}", webhook.getId(), e.getMessage());
            }
            Integer statusFinal = status;
            boolean okFinal = ok;
            tenantTx.asSystem(() -> {
                deliveries.save(new WebhookDelivery(UUID.randomUUID(), webhook.getOrganisationId(),
                        webhook.getId(), event, body, statusFinal, okFinal, clock.instant()));
                webhooks.findById(webhook.getId())
                        .ifPresent(w -> w.recordDelivery(statusFinal, clock.instant()));
                return null;
            });
        }
        return attempts;
    }

    public static String hmac(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    private Instant watermark(String key) {
        return settings.raw(key).map(json -> {
            try {
                return Instant.parse(objectMapper.readTree(json).asText());
            } catch (Exception e) {
                return Instant.EPOCH;
            }
        }).orElse(Instant.EPOCH);
    }

    private void advance(String key, Instant to) {
        if (to == null) {
            return;
        }
        tenantTx.asSystem(() -> {
            settings.put(key, "\"" + to + "\"", null);
            return null;
        });
    }
}
