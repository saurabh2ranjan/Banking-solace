package com.banking.payment.routing;

/**
 * Canonical event type identifiers — match the primary key in routing_rules table.
 * Used as keys for TopicRoutingCache lookups.
 */
public final class EventType {

    public static final String PAYMENT_INITIATED = "PAYMENT_INITIATED";

    private EventType() {}
}
