package com.banking.payment.routing;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Validates that all required event types have routes in the cache after startup.
 * Logs WARN for any missing routes — does NOT block startup.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RoutingValidator {

    private final TopicRoutingCache topicRoutingCache;
    private final RoutingProperties properties;

    @EventListener(ApplicationReadyEvent.class)
    public void validate() {
        log.info("━━━ [RoutingValidator] Validating required routing rules ━━━");

        boolean allPresent = true;
        for (String eventType : properties.getRequiredEventTypes()) {
            String topic = topicRoutingCache.get(eventType);
            boolean isFallback = topic.contains("/unknown/");
            if (isFallback) {
                log.warn("[RoutingValidator] WARN: No route for '{}' — service will use fallback topic: {}",
                        eventType, topic);
                allPresent = false;
            } else {
                log.info("[RoutingValidator]  OK  {} → {}", eventType, topic);
            }
        }

        if (allPresent) {
            log.info("━━━ [RoutingValidator] All required routes present ✓ ━━━");
        } else {
            log.warn("━━━ [RoutingValidator] Some routes missing — check routing-service ━━━");
        }
    }
}
