package com.banking.account.routing;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Subscribes to routing change events from routing-service.
 *
 * Two event types handled:
 *   banking/v1/routing/updated      → single route change  → refresh()
 *   banking/v1/routing/bulk-updated → bulk route change    → refreshAll() + one file write
 *
 * No consumer group on either binding — every running instance receives updates
 * independently so all replicas stay in sync.
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

    @Bean
    public Consumer<RoutingBulkUpdatedEvent> handleRoutingBulkUpdated() {
        return event -> {
            log.info("▸ [RoutingCacheRefresher] Bulk route change received: {}/{} routes changed, changedBy={}",
                    event.getChanges().size(), event.getTotalRequested(), event.getChangedBy());

            Map<String, String> updatedRoutes = event.getChanges().stream()
                    .collect(Collectors.toMap(
                            RoutingBulkUpdatedEvent.RouteChange::getAffectedEventType,
                            RoutingBulkUpdatedEvent.RouteChange::getNewTopic));

            topicRoutingCache.refreshAll(updatedRoutes);
            log.info("✅ [RoutingCacheRefresher] L1 cache bulk-updated, L3 file written once");
        };
    }
}
