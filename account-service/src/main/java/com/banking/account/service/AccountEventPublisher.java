package com.banking.account.service;

import com.banking.account.event.BankingEvents.*;
import com.banking.account.routing.EventType;
import com.banking.account.routing.TopicRoutingCache;
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
 * Topic destinations are resolved dynamically from TopicRoutingCache (DB-backed).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AccountEventPublisher {

    private final StreamBridge streamBridge;
    private final ObjectMapper objectMapper;
    private final TopicRoutingCache topicRoutingCache;

    public void publishAccountClosed(AccountClosedEvent event) {
        String destination = topicRoutingCache.get(EventType.ACCOUNT_CLOSED);
        log.info("Publishing AccountClosedEvent: accountId={}, destination={}",
                event.getAccountId(), destination);
        streamBridge.send("accountClosedPublisher-out-0",
                MessageBuilder.createMessage(event,
                        new MessageHeaders(Map.of("solace_destination", destination))));
    }

    public void publishAccountCreated(AccountCreatedEvent event) {
        String destination = topicRoutingCache.get(EventType.ACCOUNT_CREATED);
        log.info("Publishing AccountCreatedEvent: accountId={}, destination={}",
                event.getAccountId(), destination);
        streamBridge.send("accountCreatedPublisher-out-0",
                MessageBuilder.createMessage(event,
                        new MessageHeaders(Map.of("solace_destination", destination))));
    }

    public void publishAccountUpdated(AccountUpdatedEvent event) {
        String destination = topicRoutingCache.get(EventType.ACCOUNT_UPDATED);
        log.info("Publishing AccountUpdatedEvent: accountId={}, field={}, destination={}",
                event.getAccountId(), event.getField(), destination);
        streamBridge.send("accountUpdatedPublisher-out-0",
                MessageBuilder.createMessage(event,
                        new MessageHeaders(Map.of("solace_destination", destination))));
    }

    public void publishPaymentCompleted(PaymentCompletedEvent event) {
        String destination = topicRoutingCache.get(EventType.PAYMENT_COMPLETED);
        log.info("Publishing PaymentCompletedEvent: paymentId={}, amount={}, destination={}",
                event.getPaymentId(), event.getAmount(), destination);
        streamBridge.send("paymentCompletedPublisher-out-0",
                MessageBuilder.createMessage(event,
                        new MessageHeaders(Map.of("solace_destination", destination))));
    }

    public void publishPaymentFailed(PaymentFailedEvent event) {
        String destination = topicRoutingCache.get(EventType.PAYMENT_FAILED);
        log.warn("Publishing PaymentFailedEvent: paymentId={}, reason={}, destination={}",
                event.getPaymentId(), event.getReason(), destination);
        streamBridge.send("paymentFailedPublisher-out-0",
                MessageBuilder.createMessage(event,
                        new MessageHeaders(Map.of("solace_destination", destination))));
    }
}
