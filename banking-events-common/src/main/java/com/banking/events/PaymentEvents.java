package com.banking.events;

import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Canonical payment event contracts.
 *
 * Topics:
 *   banking/v1/payment/initiated  → PaymentInitiatedEvent   (published by payment-service)
 *   banking/v1/payment/completed  → PaymentCompletedEvent   (published by account-service)
 *   banking/v1/payment/failed     → PaymentFailedEvent      (published by account-service)
 *
 * Consumers: see infra/TOPIC_ARCHITECTURE.md
 */
public class PaymentEvents {

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
