package com.banking.notification.config;

import com.banking.events.AccountEvents.*;
import com.banking.events.PaymentEvents.*;
import com.banking.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.function.Consumer;

/**
 * Solace PubSub+ consumer bindings for the Notification Service.
 * Subscribes to account and payment events to send notifications.
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class SolaceBindingConfig {

    private final NotificationService notificationService;

    /**
     * Subscribes to: banking/v1/account/closed
     * Sends account closure notification to the customer.
     */
    @Bean
    public Consumer<AccountClosedEvent> onAccountClosed() {
        return event -> {
            log.info("▸ [Solace] Received AccountClosedEvent: account={}, customer={}",
                    event.getAccountNumber(), event.getCustomerName());
            notificationService.handleAccountClosed(event);
        };
    }

    /**
     * Subscribes to: banking/account/created
     * Sends welcome email/SMS to new customers.
     */
    @Bean
    public Consumer<AccountCreatedEvent> onAccountCreated() {
        return event -> {
            log.info("▸ [Solace] Received AccountCreatedEvent: customer={}",
                    event.getCustomerName());
            notificationService.handleAccountCreated(event);
        };
    }

    /**
     * Subscribes to: banking/payment/completed
     * Sends payment confirmation to sender and receiver.
     */
    @Bean
    public Consumer<PaymentCompletedEvent> onPaymentCompleted() {
        return event -> {
            log.info("▸ [Solace] Received PaymentCompletedEvent: paymentId={}",
                    event.getPaymentId());
            notificationService.handlePaymentCompleted(event);
        };
    }

    /**
     * Subscribes to: banking/payment/failed
     * Sends failure alert to the payment initiator.
     */
    @Bean
    public Consumer<PaymentFailedEvent> onPaymentFailed() {
        return event -> {
            log.warn("▸ [Solace] Received PaymentFailedEvent: paymentId={}, reason={}",
                    event.getPaymentId(), event.getReason());
            notificationService.handlePaymentFailed(event);
        };
    }
}
