package com.banking.account.routing;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.function.Consumer;

/**
 * Subscribes to banking/v1/routing/updated.
 *
 * When routing-service changes a topic, this consumer invalidates the L1 cache
 * entry for the affected event type and overwrites the L3 file cache.
 *
 * No consumer group is set — each running instance receives the update independently,
 * ensuring all replicas stay in sync.
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class RoutingCacheRefresher {

    private final TopicRoutingCache topicRoutingCache;

    @Bean
    public Consumer<RoutingUpdatedEvent> handleRoutingUpdated() {
        return event -> {
            log.info("▸ [RoutingCacheRefresher] Route change received: {} → {} (was: {})",
                    event.getAffectedEventType(), event.getNewTopic(), event.getOldTopic());
            topicRoutingCache.refresh(event.getAffectedEventType(), event.getNewTopic());
            log.info("✅ [RoutingCacheRefresher] L1 cache updated for {}", event.getAffectedEventType());
        };
    }
}
