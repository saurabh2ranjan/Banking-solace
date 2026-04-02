package com.banking.payment.service;

import com.banking.events.PaymentEvents.PaymentInitiatedEvent;
import com.banking.payment.routing.EventType;
import com.banking.payment.routing.TopicRoutingCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Publishes payment events to Solace PubSub+ topics via Spring Cloud Stream.
 * Topic destinations are resolved dynamically from TopicRoutingCache (DB-backed).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentEventPublisher {

    private final StreamBridge streamBridge;
    private final TopicRoutingCache topicRoutingCache;

    public void publishPaymentInitiated(PaymentInitiatedEvent event) {
        String destination = topicRoutingCache.get(EventType.PAYMENT_INITIATED);
        log.info("▸ Publishing PaymentInitiatedEvent: paymentId={}, from={}, to={}, amount={}, destination={}",
                event.getPaymentId(), event.getFromAccountId(),
                event.getToAccountId(), event.getAmount(), destination);

        boolean sent = streamBridge.send("paymentInitiatedPublisher-out-0",
                MessageBuilder.createMessage(event,
                        new MessageHeaders(Map.of("solace_destination", destination))));

        log.debug("PaymentInitiatedEvent sent={}", sent);
    }
}
