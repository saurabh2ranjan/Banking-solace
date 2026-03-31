package com.banking.notification.model;

import lombok.*;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Notification {
    private String id;
    private String recipientEmail;
    private String recipientName;
    private NotificationType type;
    private NotificationChannel channel;
    private String subject;
    private String body;
    private String status;
    private String relatedEventId;
    private LocalDateTime sentAt;

    public enum NotificationType {
        ACCOUNT_WELCOME, PAYMENT_CONFIRMATION, PAYMENT_FAILURE_ALERT,
        BALANCE_UPDATE, ACCOUNT_CLOSURE
    }

    public enum NotificationChannel {
        EMAIL, SMS, PUSH
    }
}
