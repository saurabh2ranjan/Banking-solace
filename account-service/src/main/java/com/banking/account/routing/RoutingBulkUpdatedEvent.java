package com.banking.account.routing;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Received from banking/v1/routing/bulk-updated when routing-service performs
 * a bulk route replacement. Triggers a single atomic L1 cache refresh and
 * one L3 file write covering all changed routes.
 */
@Data
@NoArgsConstructor
public class RoutingBulkUpdatedEvent {
    private String eventId;
    private String eventType;           // always "ROUTING_BULK_UPDATED"
    private List<RouteChange> changes;  // only routes whose topic actually changed
    private int totalRequested;
    private String changedBy;
    private String reason;
    private LocalDateTime timestamp;
    private String source;

    @Data
    @NoArgsConstructor
    public static class RouteChange {
        private String affectedEventType;
        private String oldTopic;
        private String newTopic;
    }
}
