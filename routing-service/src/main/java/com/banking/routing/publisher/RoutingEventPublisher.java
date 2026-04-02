package com.banking.routing.publisher;

import com.banking.routing.event.RoutingEvents.RoutingBulkUpdatedEvent;
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

    private static final String SINGLE_BINDING      = "routingUpdatedPublisher-out-0";
    private static final String SINGLE_DESTINATION  = "banking/v1/routing/updated";

    private static final String BULK_BINDING        = "routingBulkUpdatedPublisher-out-0";
    private static final String BULK_DESTINATION    = "banking/v1/routing/bulk-updated";

    private final StreamBridge streamBridge;

    public void publishRoutingUpdated(RoutingUpdatedEvent event) {
        log.info("▸ [ROUTING] Publishing RoutingUpdatedEvent: affectedEventType={}, {} → {}",
                event.getAffectedEventType(), event.getOldTopic(), event.getNewTopic());

        boolean sent = streamBridge.send(SINGLE_BINDING,
                MessageBuilder.createMessage(event,
                        new MessageHeaders(Map.of("solace_destination", SINGLE_DESTINATION))));

        log.debug("RoutingUpdatedEvent sent={}", sent);
    }

    public void publishRoutingBulkUpdated(RoutingBulkUpdatedEvent event) {
        log.info("▸ [ROUTING] Publishing RoutingBulkUpdatedEvent: {} routes changed (requested={})",
                event.getChanges().size(), event.getTotalRequested());

        boolean sent = streamBridge.send(BULK_BINDING,
                MessageBuilder.createMessage(event,
                        new MessageHeaders(Map.of("solace_destination", BULK_DESTINATION))));

        log.debug("RoutingBulkUpdatedEvent sent={}", sent);
    }
}
