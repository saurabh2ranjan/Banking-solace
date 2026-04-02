package com.banking.common.event;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * ─────────────────────────────────────────────────────────────────
 * Shared Event Contracts for Banking Microservices
 * ─────────────────────────────────────────────────────────────────
 *
 * CANONICAL SOURCE: banking-events-common/src/main/java/com/banking/events/
 *   AccountEvents.java  → AccountCreatedEvent, AccountUpdatedEvent, AccountClosedEvent
 *   PaymentEvents.java  → PaymentInitiatedEvent, PaymentCompletedEvent, PaymentFailedEvent
 *
 * This file is a human-readable reference only. All services depend on
 * banking-events-common at compile time. Do NOT edit event fields here
 * without updating the canonical source classes.
 * ─────────────────────────────────────────────────────────────────
 *
 * TOPIC MAPPING:
 *
 *   banking/account/created   → AccountCreatedEvent
 *   banking/account/updated   → AccountUpdatedEvent
 *   banking/account/closed    → AccountClosedEvent
 *   banking/payment/initiated → PaymentInitiatedEvent
 *   banking/payment/completed → PaymentCompletedEvent
 *   banking/payment/failed    → PaymentFailedEvent
 *
 * PUBLISHERS & SUBSCRIBERS:
 *
 *   Account Service  → publishes: v1/account/created, v1/account/updated,
 *                                  v1/account/closed,
 *                                  v1/payment/completed, v1/payment/failed
 *                    → subscribes: v1/payment/initiated
 *
 *   Payment Service  → publishes: v1/payment/initiated
 *                    → subscribes: v1/payment/completed, v1/payment/failed
 *
 *   Notification Svc → subscribes: v1/account/closed, v1/account/created,
 *                                   v1/payment/completed, v1/payment/failed
 *
 *   Audit Service    → subscribes: v1/account/closed, v1/account/created,
 *                                   v1/account/updated, v1/payment/initiated,
 *                                   v1/payment/completed, v1/payment/failed
 */
public class EventContracts {

    // ── Base envelope fields (present in every event) ──────────
    //   eventId    : UUID - unique identifier for this event instance
    //   timestamp  : ISO-8601 - when the event was produced
    //   source     : String - name of the publishing service

    // ── Account Domain Events ──────────────────────────────────

    /**
     * Published when a new bank account is opened.
     * Topic: banking/account/created
     */
    public record AccountCreatedEvent(
        String eventId,
        String accountId,
        String accountNumber,
        String customerName,
        String email,
        String accountType,     // SAVINGS, CHECKING, BUSINESS, FIXED_DEPOSIT
        BigDecimal balance,
        LocalDateTime timestamp,
        String source
    ) {}

    /**
     * Published when an account field is modified (balance, status, etc.).
     * Topic: banking/account/updated
     */
    public record AccountUpdatedEvent(
        String eventId,
        String accountId,
        String accountNumber,
        String field,           // "balance", "status", "email", etc.
        String oldValue,
        String newValue,
        LocalDateTime timestamp,
        String source
    ) {}

    /**
     * Published when an account is permanently closed.
     * Topic: banking/account/closed
     */
    public record AccountClosedEvent(
        String eventId,
        String accountId,
        String accountNumber,
        String customerName,
        String email,           // needed by notification-service for closure alert
        String reason,
        BigDecimal finalBalance,
        LocalDateTime timestamp,
        String source
    ) {}

    // ── Payment Domain Events ──────────────────────────────────

    /**
     * Published when a payment is submitted for processing.
     * Topic: banking/payment/initiated
     *
     * Flow: Payment Service → Solace → Account Service
     * Account Service validates, debits sender, credits receiver.
     */
    public record PaymentInitiatedEvent(
        String eventId,
        String paymentId,
        String fromAccountId,
        String toAccountId,
        BigDecimal amount,
        String description,
        LocalDateTime timestamp,
        String source
    ) {}

    /**
     * Published after Account Service successfully processes a payment.
     * Topic: banking/payment/completed
     *
     * Flow: Account Service → Solace → Payment Service (status update)
     *                                → Notification Service (confirmation)
     *                                → Audit Service (compliance log)
     */
    public record PaymentCompletedEvent(
        String eventId,
        String paymentId,
        String fromAccountId,
        String toAccountId,
        BigDecimal amount,
        BigDecimal fromNewBalance,
        BigDecimal toNewBalance,
        LocalDateTime timestamp,
        String source
    ) {}

    /**
     * Published when Account Service rejects a payment.
     * Topic: banking/payment/failed
     *
     * Common reasons: insufficient funds, account suspended, account not found.
     */
    public record PaymentFailedEvent(
        String eventId,
        String paymentId,
        String fromAccountId,
        String toAccountId,
        BigDecimal amount,
        String reason,
        LocalDateTime timestamp,
        String source
    ) {}
}
