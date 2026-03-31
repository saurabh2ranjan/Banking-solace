package com.banking.account.service;

import com.banking.account.event.BankingEvents.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Publishes banking events to Solace PubSub+ topics via Spring Cloud Stream.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AccountEventPublisher {

    private final StreamBridge streamBridge;
    private final ObjectMapper objectMapper;

    public void publishAccountCreated(AccountCreatedEvent event) {
        log.info("Publishing AccountCreatedEvent: accountId={}, accountNumber={}",
                event.getAccountId(), event.getAccountNumber());
        boolean sent = streamBridge.send("accountCreatedPublisher-out-0",
                MessageBuilder.createMessage(event,
                        new MessageHeaders(Map.of("solace_destination", "banking/account/created"))));
        log.debug("AccountCreatedEvent sent={}", sent);
    }

    public void publishAccountUpdated(AccountUpdatedEvent event) {
        log.info("Publishing AccountUpdatedEvent: accountId={}, field={}",
                event.getAccountId(), event.getField());
        streamBridge.send("accountUpdatedPublisher-out-0",
                MessageBuilder.createMessage(event,
                        new MessageHeaders(Map.of("solace_destination", "banking/account/updated"))));
    }

    public void publishPaymentCompleted(PaymentCompletedEvent event) {
        log.info("Publishing PaymentCompletedEvent: paymentId={}, amount={}",
                event.getPaymentId(), event.getAmount());
        streamBridge.send("paymentCompletedPublisher-out-0",
                MessageBuilder.createMessage(event,
                        new MessageHeaders(Map.of("solace_destination", "banking/payment/completed"))));
    }

    public void publishPaymentFailed(PaymentFailedEvent event) {
        log.warn("Publishing PaymentFailedEvent: paymentId={}, reason={}",
                event.getPaymentId(), event.getReason());
        streamBridge.send("paymentFailedPublisher-out-0",
                MessageBuilder.createMessage(event,
                        new MessageHeaders(Map.of("solace_destination", "banking/payment/failed"))));
    }
}
