package com.banking.account.routing;

/**
 * Canonical event type identifiers — match the primary key in routing_rules table.
 * Used as keys for TopicRoutingCache lookups.
 */
public final class EventType {

    public static final String ACCOUNT_CREATED   = "ACCOUNT_CREATED";
    public static final String ACCOUNT_UPDATED   = "ACCOUNT_UPDATED";
    public static final String ACCOUNT_CLOSED    = "ACCOUNT_CLOSED";
    public static final String PAYMENT_COMPLETED = "PAYMENT_COMPLETED";
    public static final String PAYMENT_FAILED    = "PAYMENT_FAILED";

    private EventType() {}
}
