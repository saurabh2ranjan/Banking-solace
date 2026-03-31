package com.banking.notification.event;

import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public class NotificationEvents {

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
