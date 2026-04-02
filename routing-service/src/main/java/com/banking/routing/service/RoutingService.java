package com.banking.routing.service;

import com.banking.routing.dto.AuditLogResponse;
import com.banking.routing.dto.BulkUpdateRequest;
import com.banking.routing.dto.RouteResponse;
import com.banking.routing.dto.UpdateRouteRequest;
import com.banking.routing.entity.RoutingAuditLog;
import com.banking.routing.entity.RoutingRule;
import com.banking.routing.event.RoutingEvents.RoutingBulkUpdatedEvent;
import com.banking.routing.event.RoutingEvents.RoutingBulkUpdatedEvent.RouteChange;
import com.banking.routing.event.RoutingEvents.RoutingUpdatedEvent;
import com.banking.routing.publisher.RoutingEventPublisher;
import com.banking.routing.repository.RoutingAuditLogRepository;
import com.banking.routing.repository.RoutingRuleRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class RoutingService {

    private final RoutingRuleRepository    routingRuleRepository;
    private final RoutingAuditLogRepository auditLogRepository;
    private final RoutingRedisService      redisService;
    private final RoutingEventPublisher    eventPublisher;

    // ── Startup: warm Redis from DB ───────────────────────────────────

    @PostConstruct
    public void warmRedisCache() {
        List<RoutingRule> active = routingRuleRepository.findByActiveTrue();
        Map<String, String> routes = active.stream()
                .collect(Collectors.toMap(RoutingRule::getEventType, RoutingRule::getTopic));
        redisService.putAll(routes);
        log.info("━━━ [routing-service] Redis warmed with {} active routes ━━━", routes.size());
        routes.forEach((k, v) -> log.info("  {} → {}", k, v));
    }

    // ── Queries ───────────────────────────────────────────────────────

    public List<RouteResponse> getAllActiveRoutes() {
        return routingRuleRepository.findByActiveTrue().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    public RouteResponse getRoute(String eventType) {
        return routingRuleRepository.findById(eventType)
                .filter(RoutingRule::getActive)
                .map(this::toResponse)
                .orElseThrow(() -> new IllegalStateException("No active route for event type: " + eventType));
    }

    // ── Update ────────────────────────────────────────────────────────

    @Transactional
    public RouteResponse updateRoute(String eventType, UpdateRouteRequest request) {
        RoutingRule rule = routingRuleRepository.findById(eventType)
                .orElseThrow(() -> new IllegalArgumentException("Unknown event type: " + eventType));

        String oldTopic = rule.getTopic();
        String newTopic = request.getTopic();

        if (oldTopic.equals(newTopic)) {
            log.info("[ROUTING] No change — topic already set to {}", newTopic);
            return toResponse(rule);
        }

        // 1. Update DB
        rule.setTopic(newTopic);
        routingRuleRepository.save(rule);

        // 2. Update Redis
        redisService.put(eventType, newTopic);

        // 3. Write audit log
        auditLogRepository.save(RoutingAuditLog.builder()
                .eventType(eventType)
                .oldTopic(oldTopic)
                .newTopic(newTopic)
                .changedBy(request.getChangedBy() != null ? request.getChangedBy() : "system")
                .changedAt(LocalDateTime.now())
                .reason(request.getReason())
                .build());

        log.info("━━━ [ROUTING] Route updated ━━━");
        log.info("  EventType : {}", eventType);
        log.info("  Old topic : {}", oldTopic);
        log.info("  New topic : {}", newTopic);
        log.info("  Changed by: {}", request.getChangedBy());

        // 4. Publish event so all services invalidate their caches
        eventPublisher.publishRoutingUpdated(RoutingUpdatedEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType("ROUTING_UPDATED")
                .affectedEventType(eventType)
                .oldTopic(oldTopic)
                .newTopic(newTopic)
                .changedBy(request.getChangedBy() != null ? request.getChangedBy() : "system")
                .timestamp(LocalDateTime.now())
                .source("routing-service")
                .build());

        return toResponse(rule);
    }

    // ── Bulk Update ───────────────────────────────────────────────────

    @Transactional
    public List<RouteResponse> bulkUpdateRoutes(BulkUpdateRequest request) {
        List<RouteChange> changes    = new ArrayList<>();
        List<RouteResponse> results  = new ArrayList<>();
        String actor = request.getChangedBy() != null ? request.getChangedBy() : "system";

        for (BulkUpdateRequest.RouteUpdate update : request.getRoutes()) {
            RoutingRule rule = routingRuleRepository.findById(update.getEventType())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Unknown event type: " + update.getEventType()));

            String oldTopic = rule.getTopic();
            String newTopic = update.getTopic();

            if (!oldTopic.equals(newTopic)) {
                // 1. Update DB
                rule.setTopic(newTopic);
                routingRuleRepository.save(rule);

                // 2. Update Redis
                redisService.put(update.getEventType(), newTopic);

                // 3. Write audit entry
                auditLogRepository.save(RoutingAuditLog.builder()
                        .eventType(update.getEventType())
                        .oldTopic(oldTopic)
                        .newTopic(newTopic)
                        .changedBy(actor)
                        .changedAt(LocalDateTime.now())
                        .reason(request.getReason())
                        .build());

                changes.add(RouteChange.builder()
                        .affectedEventType(update.getEventType())
                        .oldTopic(oldTopic)
                        .newTopic(newTopic)
                        .build());
            }

            results.add(toResponse(rule));
        }

        log.info("━━━ [ROUTING] Bulk update complete — {}/{} routes changed ━━━",
                changes.size(), request.getRoutes().size());
        changes.forEach(c -> log.info("  {} : {} → {}", c.getAffectedEventType(), c.getOldTopic(), c.getNewTopic()));

        // 4. Publish one event for all changes (only if something actually changed)
        if (!changes.isEmpty()) {
            eventPublisher.publishRoutingBulkUpdated(RoutingBulkUpdatedEvent.builder()
                    .eventId(UUID.randomUUID().toString())
                    .eventType("ROUTING_BULK_UPDATED")
                    .changes(changes)
                    .totalRequested(request.getRoutes().size())
                    .changedBy(actor)
                    .reason(request.getReason())
                    .timestamp(LocalDateTime.now())
                    .source("routing-service")
                    .build());
        }

        return results;
    }

    // ── Audit ─────────────────────────────────────────────────────────

    public List<AuditLogResponse> getAuditLog() {
        return auditLogRepository.findAllByOrderByChangedAtDesc().stream()
                .map(this::toAuditResponse)
                .collect(Collectors.toList());
    }

    public List<AuditLogResponse> getAuditLogForEventType(String eventType) {
        return auditLogRepository.findByEventTypeOrderByChangedAtDesc(eventType).stream()
                .map(this::toAuditResponse)
                .collect(Collectors.toList());
    }

    // ── Mappers ───────────────────────────────────────────────────────

    private RouteResponse toResponse(RoutingRule r) {
        return RouteResponse.builder()
                .eventType(r.getEventType())
                .topic(r.getTopic())
                .ownerService(r.getOwnerService())
                .direction(r.getDirection())
                .description(r.getDescription())
                .active(r.getActive())
                .updatedAt(r.getUpdatedAt())
                .build();
    }

    private AuditLogResponse toAuditResponse(RoutingAuditLog log) {
        return AuditLogResponse.builder()
                .id(log.getId())
                .eventType(log.getEventType())
                .oldTopic(log.getOldTopic())
                .newTopic(log.getNewTopic())
                .changedBy(log.getChangedBy())
                .changedAt(log.getChangedAt())
                .reason(log.getReason())
                .build();
    }
}
