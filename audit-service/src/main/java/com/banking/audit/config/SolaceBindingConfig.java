package com.banking.audit.config;

import com.banking.audit.event.AuditEvents.*;
import com.banking.audit.service.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.function.Consumer;

/**
 * Solace PubSub+ consumer bindings for the Audit Service.
 *
 * The Audit Service is the COMPLIANCE BACKBONE — it subscribes to every
 * banking event topic to maintain a complete, immutable audit trail.
 *
 * In production with Solace, you'd use a wildcard subscription (banking/>)
 * on the queue. Here we bind each topic explicitly via Spring Cloud Stream
 * for type-safe deserialization.
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class SolaceBindingConfig {

    private final AuditService auditService;

    // ── Account Events ─────────────────────────────────────────────

    @Bean
    public Consumer<AccountCreatedEvent> auditAccountCreated() {
        return event -> {
            log.debug("▸ [AUDIT] Received AccountCreatedEvent from Solace");
            auditService.auditAccountCreated(event);
        };
    }

    @Bean
    public Consumer<AccountUpdatedEvent> auditAccountUpdated() {
        return event -> {
            log.debug("▸ [AUDIT] Received AccountUpdatedEvent from Solace");
            auditService.auditAccountUpdated(event);
        };
    }

    // ── Payment Events ─────────────────────────────────────────────

    @Bean
    public Consumer<PaymentInitiatedEvent> auditPaymentInitiated() {
        return event -> {
            log.debug("▸ [AUDIT] Received PaymentInitiatedEvent from Solace");
            auditService.auditPaymentInitiated(event);
        };
    }

    @Bean
    public Consumer<PaymentCompletedEvent> auditPaymentCompleted() {
        return event -> {
            log.debug("▸ [AUDIT] Received PaymentCompletedEvent from Solace");
            auditService.auditPaymentCompleted(event);
        };
    }

    @Bean
    public Consumer<PaymentFailedEvent> auditPaymentFailed() {
        return event -> {
            log.warn("▸ [AUDIT] Received PaymentFailedEvent from Solace");
            auditService.auditPaymentFailed(event);
        };
    }
}
