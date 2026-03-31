package com.banking.audit.controller;

import com.banking.audit.model.AuditLog;
import com.banking.audit.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * REST API for querying audit logs.
 * Supports filtering by event type, source service, severity, and date range.
 */
@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditService auditService;

    /** Get the 50 most recent audit log entries */
    @GetMapping
    public ResponseEntity<List<AuditLog>> getRecentLogs() {
        return ResponseEntity.ok(auditService.getRecentLogs());
    }

    /** Filter by event type (e.g., ACCOUNT_CREATED, PAYMENT_COMPLETED) */
    @GetMapping("/type/{eventType}")
    public ResponseEntity<List<AuditLog>> getByEventType(@PathVariable String eventType) {
        return ResponseEntity.ok(auditService.getLogsByEventType(eventType));
    }

    /** Filter by source service (e.g., account-service, payment-service) */
    @GetMapping("/service/{service}")
    public ResponseEntity<List<AuditLog>> getByService(@PathVariable String service) {
        return ResponseEntity.ok(auditService.getLogsByService(service));
    }

    /** Filter by severity (INFO, WARN, ERROR, CRITICAL) */
    @GetMapping("/severity/{severity}")
    public ResponseEntity<List<AuditLog>> getBySeverity(@PathVariable String severity) {
        return ResponseEntity.ok(auditService.getLogsBySeverity(severity.toUpperCase()));
    }

    /** Filter by date range */
    @GetMapping("/range")
    public ResponseEntity<List<AuditLog>> getByDateRange(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end) {
        return ResponseEntity.ok(auditService.getLogsByDateRange(start, end));
    }

    /** Get total count and breakdown by event type */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        return ResponseEntity.ok(Map.of(
                "totalCount", auditService.getTotalCount(),
                "countByEventType", auditService.getCountByEventType()
        ));
    }
}
