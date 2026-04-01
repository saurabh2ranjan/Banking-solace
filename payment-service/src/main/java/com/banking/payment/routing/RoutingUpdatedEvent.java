package com.banking.payment.routing;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Received from banking/v1/routing/updated when routing-service changes a rule.
 * Triggers L1 cache invalidation in this service.
 */
@Data
@NoArgsConstructor
public class RoutingUpdatedEvent {
    private String eventId;
    private String eventType;           // always "ROUTING_UPDATED"
    private String affectedEventType;   // e.g. "PAYMENT_INITIATED"
    private String oldTopic;
    private String newTopic;
    private String changedBy;
    private LocalDateTime timestamp;
    private String source;
}
