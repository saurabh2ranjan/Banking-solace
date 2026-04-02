package com.banking.routing.event;

import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

public class RoutingEvents {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class RoutingUpdatedEvent {
        private String eventId;
        private String eventType;           // always "ROUTING_UPDATED"
        private String affectedEventType;   // e.g. ACCOUNT_CREATED
        private String oldTopic;
        private String newTopic;
        private String changedBy;
        private LocalDateTime timestamp;
        private String source;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class RoutingBulkUpdatedEvent {
        private String eventId;
        private String eventType;           // always "ROUTING_BULK_UPDATED"
        private List<RouteChange> changes;  // only routes whose topic actually changed
        private int totalRequested;         // how many routes were in the request
        private String changedBy;
        private String reason;
        private LocalDateTime timestamp;
        private String source;

        @Data
        @NoArgsConstructor
        @AllArgsConstructor
        @Builder
        public static class RouteChange {
            private String affectedEventType;
            private String oldTopic;
            private String newTopic;
        }
    }
}
