package com.banking.events;

import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Canonical account event contracts published by account-service.
 *
 * Topics:
 *   banking/v1/account/created  → AccountCreatedEvent
 *   banking/v1/account/updated  → AccountUpdatedEvent
 *   banking/v1/account/closed   → AccountClosedEvent
 *
 * Consumers: see infra/TOPIC_ARCHITECTURE.md
 */
public class AccountEvents {

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class AccountCreatedEvent {
        private String eventId;
        private String accountId;
        private String accountNumber;
        private String customerName;
        private String email;
        private String accountType;
        private BigDecimal balance;
        private LocalDateTime timestamp;
        private String source;
    }

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class AccountUpdatedEvent {
        private String eventId;
        private String accountId;
        private String accountNumber;
        private String field;
        private String oldValue;
        private String newValue;
        private LocalDateTime timestamp;
        private String source;
    }

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class AccountClosedEvent {
        private String eventId;
        private String accountId;
        private String accountNumber;
        private String customerName;
        private String email;           // carried so notification-service needs no DB lookup
        private String reason;
        private BigDecimal finalBalance;
        private LocalDateTime timestamp;
        private String source;
    }
}
