package com.banking.account.routing;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * L1 in-memory routing cache — the single lookup point for all publishers.
 *
 * Fallback chain on startup:
 *   routing-service REST (primary)
 *     → Redis hash routing:rules (L2)
 *       → local JSON file (L3)
 *         → application.yml fallback-topics (L4)
 *
 * At runtime, receives cache-invalidation updates via RoutingCacheRefresher
 * when routing-service publishes a banking/v1/routing/updated event.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TopicRoutingCache {

    private final RoutingClient          routingClient;
    private final RedisRoutingCache      redisRoutingCache;
    private final RoutingCachePersistence cachePersistence;
    private final RoutingProperties      properties;

    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();

    // ── Startup initialization ────────────────────────────────────────

    @PostConstruct
    public void initialize() {
        log.info("━━━ [TopicRoutingCache] Initializing routing cache ━━━");

        int attempt = 0;
        Exception lastError = null;

        while (attempt < properties.getMaxAttempts()) {
            try {
                Map<String, String> routes = routingClient.fetchRoutes();
                populateL1(routes);
                redisRoutingCache.getAll(); // validate Redis reachability
                cachePersistence.save(routes);
                log.info("━━━ [TopicRoutingCache] Loaded from routing-service ({} routes) ━━━", routes.size());
                logCurrentRoutes();
                return;
            } catch (Exception e) {
                lastError = e;
                attempt++;
                if (attempt < properties.getMaxAttempts()) {
                    log.warn("[TopicRoutingCache] routing-service attempt {}/{} failed: {}",
                            attempt, properties.getMaxAttempts(), e.getMessage());
                }
            }
        }

        log.warn("[TopicRoutingCache] routing-service unreachable after {} attempts: {}",
                properties.getMaxAttempts(), lastError.getMessage());
        tryRedisOrFile();
    }

    // ── Public API ────────────────────────────────────────────────────

    /**
     * Resolves the Solace topic destination for a given event type.
     * Always returns a non-null value (falls back to L4 defaults at worst).
     */
    public String get(String eventType) {
        String topic = cache.get(eventType);
        if (topic == null) {
            topic = properties.getFallbackTopics().getOrDefault(eventType,
                    "banking/v1/unknown/" + eventType.toLowerCase().replace('_', '/'));
            log.error("[TopicRoutingCache] No route for '{}' — using emergency fallback: {}. " +
                    "Check routing-service.", eventType, topic);
        }
        return topic;
    }

    /**
     * Updates a single L1 cache entry and persists to file.
     * Called by RoutingCacheRefresher on a banking/v1/routing/updated event.
     */
    public void refresh(String eventType, String newTopic) {
        String oldTopic = cache.put(eventType, newTopic);
        cachePersistence.save(new HashMap<>(cache));
        log.info("[TopicRoutingCache] Cache refreshed: {} → {} (was: {})", eventType, newTopic, oldTopic);
    }

    /**
     * Applies all changed routes from a bulk update into L1 and writes the file once.
     * Called by RoutingCacheRefresher on a banking/v1/routing/bulk-updated event.
     *
     * Uses putAll (not clear+putAll) because the bulk event contains only changed routes —
     * unchanged routes remain correct in L1 and must not be evicted.
     */
    public void refreshAll(Map<String, String> updatedRoutes) {
        cache.putAll(updatedRoutes);
        cachePersistence.save(new HashMap<>(cache));
        log.info("[TopicRoutingCache] Bulk cache refresh: {}/{} routes updated",
                updatedRoutes.size(), cache.size());
        updatedRoutes.forEach((k, v) -> log.info("  {} → {}", k, v));
    }

    // ── Fallback chain (L2 → L3 → L4) ───────────────────────────────

    private void tryRedisOrFile() {
        // L2: Redis
        try {
            Map<String, String> redisRoutes = redisRoutingCache.getAll();
            if (!redisRoutes.isEmpty()) {
                populateL1(redisRoutes);
                cachePersistence.save(redisRoutes);
                log.warn("┌─────────────────────────────────────────────────────────┐");
                log.warn("│ [TopicRoutingCache] WARN: routing-service unreachable   │");
                log.warn("│ Loaded {} routes from Redis (L2 cache)                  │",
                        String.format("%-3d", redisRoutes.size()));
                log.warn("└─────────────────────────────────────────────────────────┘");
                logCurrentRoutes();
                return;
            }
            log.warn("[TopicRoutingCache] Redis reachable but routing:rules is empty — trying file cache");
        } catch (Exception e) {
            log.warn("[TopicRoutingCache] Redis also unreachable: {} — trying file cache", e.getMessage());
        }

        // L3: file cache
        RoutingCachePersistence.CachedRoutes fileCache = cachePersistence.load().orElse(null);
        if (fileCache != null) {
            populateL1(fileCache.getRoutes());
            long ageHours = ChronoUnit.HOURS.between(fileCache.getSavedAt(),
                    java.time.LocalDateTime.now());
            boolean stale = ageHours > properties.getCacheMaxAgeHours();

            log.warn("┌─────────────────────────────────────────────────────────┐");
            log.warn("│ [TopicRoutingCache] WARN: routing-service + Redis down  │");
            if (stale) {
                log.warn("│ !! STALE FILE CACHE (age={}h > threshold={}h) !!         │",
                        ageHours, properties.getCacheMaxAgeHours());
                log.warn("│    Topics may be incorrect — verify routing-service!    │");
            } else {
                log.warn("│ Loaded {} routes from file cache (age={}h)              │",
                        String.format("%-3d", fileCache.getRoutes().size()), ageHours);
            }
            log.warn("└─────────────────────────────────────────────────────────┘");
            logCurrentRoutes();
            return;
        }

        // L4: application.yml defaults
        Map<String, String> defaults = properties.getFallbackTopics();
        populateL1(defaults);
        log.error("┌─────────────────────────────────────────────────────────┐");
        log.error("│ [TopicRoutingCache] ERROR: ALL fallbacks exhausted      │");
        log.error("│ routing-service, Redis, and file cache all unavailable  │");
        log.error("│ Using hardcoded application.yml defaults ({} routes)    │",
                String.format("%-3d", defaults.size()));
        log.error("│ !! Manual verification of topic routing required !!     │");
        log.error("└─────────────────────────────────────────────────────────┘");
        logCurrentRoutes();
    }

    private void populateL1(Map<String, String> routes) {
        cache.clear();
        cache.putAll(routes);
    }

    private void logCurrentRoutes() {
        cache.forEach((k, v) -> log.info("  {} → {}", k, v));
    }
}
