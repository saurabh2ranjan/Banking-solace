package com.banking.payment.service;

import com.banking.payment.event.PaymentEvents.PaymentInitiatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentEventPublisher {

    private final StreamBridge streamBridge;

    /**
     * Publishes PaymentInitiatedEvent to Solace topic: banking/payment/initiated
     * Account Service subscribes to this topic to validate and process the payment.
     */
    public void publishPaymentInitiated(PaymentInitiatedEvent event) {
        log.info("▸ Publishing PaymentInitiatedEvent to Solace: paymentId={}, from={}, to={}, amount={}",
                event.getPaymentId(), event.getFromAccountId(),
                event.getToAccountId(), event.getAmount());

        boolean sent = streamBridge.send("paymentInitiatedPublisher-out-0",
                MessageBuilder.createMessage(event,
                        new MessageHeaders(Map.of("solace_destination", "banking/payment/initiated"))));

        log.debug("PaymentInitiatedEvent sent={}", sent);
    }
}
