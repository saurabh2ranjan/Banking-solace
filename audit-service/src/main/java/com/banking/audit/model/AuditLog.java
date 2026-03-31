package com.banking.audit.model;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Audit log entry stored in MongoDB.
 * Every banking event received via Solace is persisted here
 * for compliance and forensic analysis.
 */
@Document(collection = "audit_logs")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog {

    @Id
    private String id;

    /** Original event ID from the publishing service */
    @Indexed
    private String eventId;

    /** Solace topic the event was received on */
    @Indexed
    private String topic;

    /** Type of event: ACCOUNT_CREATED, PAYMENT_INITIATED, etc. */
    @Indexed
    private String eventType;

    /** Service that published the event */
    @Indexed
    private String sourceService;

    /** Severity: INFO, WARN, ERROR, CRITICAL */
    private String severity;

    /** Full event payload stored as a flexible map */
    private Map<String, Object> payload;

    /** Human-readable summary of the event */
    private String summary;

    /** Timestamp from the original event */
    private LocalDateTime eventTimestamp;

    /** When the audit log entry was created */
    @Indexed
    private LocalDateTime receivedAt;

    /** IP or identifier of the processing node */
    private String processingNode;
}
