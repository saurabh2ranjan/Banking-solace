package com.banking.account.config;

import com.banking.account.event.BankingEvents.PaymentInitiatedEvent;
import com.banking.account.service.AccountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.function.Consumer;

/**
 * Spring Cloud Stream function bindings for Solace PubSub+.
 * Each @Bean maps to a binding in application.yml.
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class SolaceBindingConfig {

    private final AccountService accountService;

    /**
     * Subscribes to: banking/payment/initiated
     * When Payment Service publishes a new payment request,
     * this consumer validates and processes it.
     */
    @Bean
    public Consumer<PaymentInitiatedEvent> processPayment() {
        return event -> {
            log.info("▸ Received PaymentInitiatedEvent from Solace: paymentId={}, amount={}",
                    event.getPaymentId(), event.getAmount());
            accountService.processPayment(event);
        };
    }
}
