package com.banking.account.event;

import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public class BankingEvents {

    // ── Published by Account Service ───────────────────────────────
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
        private String email;           // needed by notification-service for closure alert
        private String reason;
        private BigDecimal finalBalance;
        private LocalDateTime timestamp;
        private String source;
    }

    // ── Consumed from Payment Service ──────────────────────────────
    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class PaymentInitiatedEvent {
        private String eventId;
        private String paymentId;
        private String fromAccountId;
        private String toAccountId;
        private BigDecimal amount;
        private String description;
        private LocalDateTime timestamp;
        private String source;
    }

    // ── Published after processing payment ─────────────────────────
    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class PaymentCompletedEvent {
        private String eventId;
        private String paymentId;
        private String fromAccountId;
        private String toAccountId;
        private BigDecimal amount;
        private BigDecimal fromNewBalance;
        private BigDecimal toNewBalance;
        private LocalDateTime timestamp;
        private String source;
    }

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class PaymentFailedEvent {
        private String eventId;
        private String paymentId;
        private String fromAccountId;
        private String toAccountId;
        private BigDecimal amount;
        private String reason;
        private LocalDateTime timestamp;
        private String source;
    }
}
