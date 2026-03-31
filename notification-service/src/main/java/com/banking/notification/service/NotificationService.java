package com.banking.notification.service;

import com.banking.notification.event.NotificationEvents.*;
import com.banking.notification.model.Notification;
import com.banking.notification.model.Notification.NotificationChannel;
import com.banking.notification.model.Notification.NotificationType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Processes banking events received via Solace PubSub+ and dispatches
 * notifications (email/SMS/push). Uses in-memory store for demo purposes.
 */
@Service
@Slf4j
public class NotificationService {

    private final Map<String, Notification> notificationStore = new ConcurrentHashMap<>();

    // ── Account Created → Welcome Notification ─────────────────────

    public void handleAccountCreated(AccountCreatedEvent event) {
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log.info("📧 SENDING WELCOME NOTIFICATION");
        log.info("   To:      {} <{}>", event.getCustomerName(), event.getEmail());
        log.info("   Account: {} ({})", event.getAccountNumber(), event.getAccountType());
        log.info("   Balance: ${}", event.getBalance());
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

        String subject = "Welcome to Our Bank! Your " + event.getAccountType() + " Account is Ready";
        String body = String.format(
                "Dear %s,\n\n" +
                "Your new %s account (%s) has been created successfully.\n" +
                "Opening balance: $%s\n\n" +
                "Thank you for choosing our bank!\n\n" +
                "Best regards,\nBanking Services Team",
                event.getCustomerName(),
                event.getAccountType(),
                event.getAccountNumber(),
                event.getBalance()
        );

        Notification notification = Notification.builder()
                .id(UUID.randomUUID().toString())
                .recipientEmail(event.getEmail())
                .recipientName(event.getCustomerName())
                .type(NotificationType.ACCOUNT_WELCOME)
                .channel(NotificationChannel.EMAIL)
                .subject(subject)
                .body(body)
                .status("SENT")
                .relatedEventId(event.getEventId())
                .sentAt(LocalDateTime.now())
                .build();

        notificationStore.put(notification.getId(), notification);
        log.info("✅ Welcome notification dispatched: id={}", notification.getId());
    }

    // ── Payment Completed → Confirmation Notification ──────────────

    public void handlePaymentCompleted(PaymentCompletedEvent event) {
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log.info("📧 SENDING PAYMENT CONFIRMATION");
        log.info("   Payment:     {}", event.getPaymentId());
        log.info("   From:        {} → To: {}", event.getFromAccountId(), event.getToAccountId());
        log.info("   Amount:      ${}", event.getAmount());
        log.info("   New Balance: ${} (sender)", event.getFromNewBalance());
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

        String subject = "Payment Confirmed - $" + event.getAmount();
        String body = String.format(
                "Your payment of $%s from account %s to account %s has been processed successfully.\n\n" +
                "Your updated balance: $%s\n\n" +
                "Transaction ID: %s",
                event.getAmount(),
                event.getFromAccountId(),
                event.getToAccountId(),
                event.getFromNewBalance(),
                event.getPaymentId()
        );

        // Notification for sender
        Notification senderNotif = Notification.builder()
                .id(UUID.randomUUID().toString())
                .recipientEmail("sender@bank.com") // In real app, look up from account
                .recipientName("Sender")
                .type(NotificationType.PAYMENT_CONFIRMATION)
                .channel(NotificationChannel.EMAIL)
                .subject(subject)
                .body(body)
                .status("SENT")
                .relatedEventId(event.getEventId())
                .sentAt(LocalDateTime.now())
                .build();

        // Notification for receiver
        Notification receiverNotif = Notification.builder()
                .id(UUID.randomUUID().toString())
                .recipientEmail("receiver@bank.com")
                .recipientName("Receiver")
                .type(NotificationType.PAYMENT_CONFIRMATION)
                .channel(NotificationChannel.PUSH)
                .subject("Payment Received - $" + event.getAmount())
                .body("You received $" + event.getAmount() + " from account " + event.getFromAccountId())
                .status("SENT")
                .relatedEventId(event.getEventId())
                .sentAt(LocalDateTime.now())
                .build();

        notificationStore.put(senderNotif.getId(), senderNotif);
        notificationStore.put(receiverNotif.getId(), receiverNotif);
        log.info("✅ Payment confirmation dispatched: sender={}, receiver={}",
                senderNotif.getId(), receiverNotif.getId());
    }

    // ── Payment Failed → Alert Notification ────────────────────────

    public void handlePaymentFailed(PaymentFailedEvent event) {
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log.info("🚨 SENDING PAYMENT FAILURE ALERT");
        log.info("   Payment: {}", event.getPaymentId());
        log.info("   From:    {} → To: {}", event.getFromAccountId(), event.getToAccountId());
        log.info("   Amount:  ${}", event.getAmount());
        log.info("   Reason:  {}", event.getReason());
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

        String subject = "Payment Failed - $" + event.getAmount();
        String body = String.format(
                "Your payment of $%s from account %s to account %s has FAILED.\n\n" +
                "Reason: %s\n\n" +
                "Please check your account and try again.\n" +
                "Transaction ID: %s",
                event.getAmount(),
                event.getFromAccountId(),
                event.getToAccountId(),
                event.getReason(),
                event.getPaymentId()
        );

        Notification notification = Notification.builder()
                .id(UUID.randomUUID().toString())
                .recipientEmail("sender@bank.com")
                .recipientName("Sender")
                .type(NotificationType.PAYMENT_FAILURE_ALERT)
                .channel(NotificationChannel.EMAIL)
                .subject(subject)
                .body(body)
                .status("SENT")
                .relatedEventId(event.getEventId())
                .sentAt(LocalDateTime.now())
                .build();

        notificationStore.put(notification.getId(), notification);
        log.warn("✅ Payment failure alert dispatched: id={}", notification.getId());
    }

    // ── Query notifications (REST) ─────────────────────────────────

    public List<Notification> getAllNotifications() {
        return new ArrayList<>(notificationStore.values());
    }

    public Optional<Notification> getNotification(String id) {
        return Optional.ofNullable(notificationStore.get(id));
    }

    public long getNotificationCount() {
        return notificationStore.size();
    }
}
