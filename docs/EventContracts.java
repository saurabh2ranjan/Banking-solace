package com.banking.common.event;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * ─────────────────────────────────────────────────────────────────
 * Shared Event Contracts for Banking Microservices
 * ─────────────────────────────────────────────────────────────────
 *
 * These record definitions document the canonical event schemas
 * published and consumed via Solace PubSub+ topics.
 *
 * In a production setup, this would be a shared Maven module
 * (banking-events-common) that all services depend on.
 *
 * For this demo, each service has its own copy of these DTOs.
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
 *   Account Service  → publishes: account/created, account/updated,
 *                                  payment/completed, payment/failed
 *                    → subscribes: payment/initiated
 *
 *   Payment Service  → publishes: payment/initiated
 *                    → subscribes: payment/completed, payment/failed
 *
 *   Notification Svc → subscribes: account/created,
 *                                   payment/completed, payment/failed
 *
 *   Audit Service    → subscribes: banking/> (ALL events via wildcard)
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
