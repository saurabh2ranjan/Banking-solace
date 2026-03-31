package com.banking.payment.event;

import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

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
