package com.banking.routing.publisher;

import com.banking.routing.event.RoutingEvents.RoutingUpdatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Publishes route-change notifications to Solace so all services
 * can invalidate their in-memory and file caches immediately.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RoutingEventPublisher {

    private static final String BINDING     = "routingUpdatedPublisher-out-0";
    private static final String DESTINATION = "banking/v1/routing/updated";

    private final StreamBridge streamBridge;

    public void publishRoutingUpdated(RoutingUpdatedEvent event) {
        log.info("▸ [ROUTING] Publishing RoutingUpdatedEvent: affectedEventType={}, {} → {}",
                event.getAffectedEventType(), event.getOldTopic(), event.getNewTopic());

        boolean sent = streamBridge.send(BINDING,
                MessageBuilder.createMessage(event,
                        new MessageHeaders(Map.of("solace_destination", DESTINATION))));

        log.debug("RoutingUpdatedEvent sent={}", sent);
    }
}
