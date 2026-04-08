package com.banking.routing.controller;

import com.banking.routing.dto.AuditLogResponse;
import com.banking.routing.dto.BulkUpdateRequest;
import com.banking.routing.dto.RouteResponse;
import com.banking.routing.dto.UpdateRouteRequest;
import com.banking.routing.service.RoutingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/routes")
@RequiredArgsConstructor
@Slf4j
public class RoutingController {

    private final RoutingService routingService;

    /** All active routing rules — used by services at the startup to load their cache. */
    @GetMapping
    public ResponseEntity<List<RouteResponse>> getAllRoutes() {
        return ResponseEntity.ok(routingService.getAllActiveRoutes());
    }

    /** Single route by event type. */
    @GetMapping("/{eventType}")
    public ResponseEntity<RouteResponse> getRoute(@PathVariable String eventType) {
        return ResponseEntity.ok(routingService.getRoute(eventType));
    }

    /**
     * Update the topic for a given event type.
     * Triggers: DB update → Redis update → audit log → Solace event.
     */
    @PutMapping("/{eventType}")
    public ResponseEntity<RouteResponse> updateRoute(
            @PathVariable String eventType,
            @Valid @RequestBody UpdateRouteRequest request) {
        log.info("▸ PUT /api/routes/{} → topic={}", eventType, request.getTopic());
        return ResponseEntity.ok(routingService.updateRoute(eventType, request));
    }

    /**
     * Bulk replace all supplied routes in one atomic transaction.
     * Only routes whose topic actually changed are written to DB/Redis/audit.
     * Publishes one RoutingBulkUpdatedEvent covering all changes.
     */
    @PutMapping("/bulk")
    public ResponseEntity<List<RouteResponse>> bulkUpdateRoutes(
            @Valid @RequestBody BulkUpdateRequest request) {
        log.info("▸ PUT /api/routes/bulk — {} routes, changedBy={}", request.getRoutes().size(), request.getChangedBy());
        return ResponseEntity.ok(routingService.bulkUpdateRoutes(request));
    }

    /** Full audit history — all route changes across all event types. */
    @GetMapping("/audit")
    public ResponseEntity<List<AuditLogResponse>> getAuditLog() {
        return ResponseEntity.ok(routingService.getAuditLog());
    }

    /** Audit history scoped to a single event type. */
    @GetMapping("/{eventType}/audit")
    public ResponseEntity<List<AuditLogResponse>> getAuditLogForEventType(
            @PathVariable String eventType) {
        return ResponseEntity.ok(routingService.getAuditLogForEventType(eventType));
    }
}
