package com.banking.routing.event;

import lombok.*;

import java.time.LocalDateTime;

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
}
