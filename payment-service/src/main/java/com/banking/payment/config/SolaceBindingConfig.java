package com.banking.payment.config;

import com.banking.payment.event.PaymentEvents.*;
import com.banking.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.function.Consumer;

@Configuration
@RequiredArgsConstructor
@Slf4j
public class SolaceBindingConfig {

    private final PaymentService paymentService;

    /**
     * Subscribes to: banking/payment/completed
     * When Account Service successfully processes a payment,
     * this consumer updates the payment status to COMPLETED.
     */
    @Bean
    public Consumer<PaymentCompletedEvent> handlePaymentCompleted() {
        return event -> {
            log.info("▸ Received PaymentCompletedEvent from Solace: paymentId={}", event.getPaymentId());
            paymentService.handlePaymentCompleted(event);
        };
    }

    /**
     * Subscribes to: banking/payment/failed
     * When Account Service fails to process a payment,
     * this consumer updates the payment status to FAILED.
     */
    @Bean
    public Consumer<PaymentFailedEvent> handlePaymentFailed() {
        return event -> {
            log.warn("▸ Received PaymentFailedEvent from Solace: paymentId={}, reason={}",
                    event.getPaymentId(), event.getReason());
            paymentService.handlePaymentFailed(event);
        };
    }
}
