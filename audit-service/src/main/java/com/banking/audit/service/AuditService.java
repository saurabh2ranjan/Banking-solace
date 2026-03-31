package com.banking.audit.service;

import com.banking.audit.event.AuditEvents.*;
import com.banking.audit.model.AuditLog;
import com.banking.audit.repository.AuditLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Core audit service that persists ALL banking events to MongoDB.
 * Acts as the compliance and forensic analysis backbone.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditService {

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    // ── Event Handlers ─────────────────────────────────────────────

    public void auditAccountCreated(AccountCreatedEvent event) {
        logAuditEntry(
                event.getEventId(),
                "banking/account/created",
                "ACCOUNT_CREATED",
                event.getSource(),
                "INFO",
                toMap(event),
                String.format("Account created: %s (%s) for %s with balance $%s",
                        event.getAccountNumber(), event.getAccountType(),
                        event.getCustomerName(), event.getBalance()),
                event.getTimestamp()
        );
    }

    public void auditAccountUpdated(AccountUpdatedEvent event) {
        logAuditEntry(
                event.getEventId(),
                "banking/account/updated",
                "ACCOUNT_UPDATED",
                event.getSource(),
                "INFO",
                toMap(event),
                String.format("Account %s updated: %s changed from '%s' to '%s'",
                        event.getAccountNumber(), event.getField(),
                        event.getOldValue(), event.getNewValue()),
                event.getTimestamp()
        );
    }

    public void auditPaymentInitiated(PaymentInitiatedEvent event) {
        logAuditEntry(
                event.getEventId(),
                "banking/payment/initiated",
                "PAYMENT_INITIATED",
                event.getSource(),
                "INFO",
                toMap(event),
                String.format("Payment initiated: $%s from %s to %s (%s)",
                        event.getAmount(), event.getFromAccountId(),
                        event.getToAccountId(), event.getDescription()),
                event.getTimestamp()
        );
    }

    public void auditPaymentCompleted(PaymentCompletedEvent event) {
        logAuditEntry(
                event.getEventId(),
                "banking/payment/completed",
                "PAYMENT_COMPLETED",
                event.getSource(),
                "INFO",
                toMap(event),
                String.format("Payment completed: $%s from %s (bal: $%s) to %s (bal: $%s)",
                        event.getAmount(), event.getFromAccountId(),
                        event.getFromNewBalance(), event.getToAccountId(),
                        event.getToNewBalance()),
                event.getTimestamp()
        );
    }

    public void auditPaymentFailed(PaymentFailedEvent event) {
        logAuditEntry(
                event.getEventId(),
                "banking/payment/failed",
                "PAYMENT_FAILED",
                event.getSource(),
                "WARN",
                toMap(event),
                String.format("Payment FAILED: $%s from %s to %s — Reason: %s",
                        event.getAmount(), event.getFromAccountId(),
                        event.getToAccountId(), event.getReason()),
                event.getTimestamp()
        );
    }

    // ── Query Methods ──────────────────────────────────────────────

    public List<AuditLog> getRecentLogs() {
        return auditLogRepository.findTop50ByOrderByReceivedAtDesc();
    }

    public List<AuditLog> getLogsByEventType(String eventType) {
        return auditLogRepository.findByEventType(eventType);
    }

    public List<AuditLog> getLogsByService(String service) {
        return auditLogRepository.findBySourceService(service);
    }

    public List<AuditLog> getLogsBySeverity(String severity) {
        return auditLogRepository.findBySeverity(severity);
    }

    public List<AuditLog> getLogsByDateRange(LocalDateTime start, LocalDateTime end) {
        return auditLogRepository.findByReceivedAtBetween(start, end);
    }

    public long getTotalCount() {
        return auditLogRepository.count();
    }

    public Map<String, Long> getCountByEventType() {
        List<AuditLog> all = auditLogRepository.findAll();
        Map<String, Long> counts = new java.util.LinkedHashMap<>();
        all.stream()
                .map(AuditLog::getEventType)
                .distinct()
                .sorted()
                .forEach(type -> counts.put(type, auditLogRepository.countByEventType(type)));
        return counts;
    }

    // ── Internal ───────────────────────────────────────────────────

    private void logAuditEntry(String eventId, String topic, String eventType,
                               String sourceService, String severity,
                               Map<String, Object> payload, String summary,
                               LocalDateTime eventTimestamp) {

        String nodeName;
        try {
            nodeName = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            nodeName = "unknown";
        }

        AuditLog auditLog = AuditLog.builder()
                .eventId(eventId)
                .topic(topic)
                .eventType(eventType)
                .sourceService(sourceService)
                .severity(severity)
                .payload(payload)
                .summary(summary)
                .eventTimestamp(eventTimestamp)
                .receivedAt(LocalDateTime.now())
                .processingNode(nodeName)
                .build();

        AuditLog saved = auditLogRepository.save(auditLog);

        log.info("┌─────────────────────────────────────────────────────────┐");
        log.info("│ 📋 AUDIT LOG RECORDED                                  │");
        log.info("│ ID:       {}",   saved.getId());
        log.info("│ Type:     {}",   eventType);
        log.info("│ Topic:    {}",   topic);
        log.info("│ Source:   {}",   sourceService);
        log.info("│ Severity: {}",   severity);
        log.info("│ Summary:  {}",   summary);
        log.info("└─────────────────────────────────────────────────────────┘");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(Object obj) {
        return objectMapper.convertValue(obj, Map.class);
    }
}
