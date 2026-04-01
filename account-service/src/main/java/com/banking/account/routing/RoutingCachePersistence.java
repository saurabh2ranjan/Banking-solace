package com.banking.account.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

/**
 * L3 cache — persists routing rules to a local JSON file.
 *
 * Survives Redis outages. Written on every successful load from routing-service or Redis.
 * Read at startup only when both routing-service and Redis are unavailable.
 *
 * File format:
 * {
 *   "savedAt": "2026-04-01T10:00:00",
 *   "routes": { "ACCOUNT_CREATED": "banking/v1/account/created", ... }
 * }
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RoutingCachePersistence {

    private final RoutingProperties properties;
    private final ObjectMapper objectMapper;

    /** Writes current routes to the cache file. Silently ignores write failures. */
    public void save(Map<String, String> routes) {
        String path = properties.getCacheFile();
        if (path == null || path.isBlank()) {
            log.debug("[RoutingCachePersistence] cache-file not configured — skipping file save");
            return;
        }
        try {
            File file = new File(path);
            file.getParentFile().mkdirs();
            objectMapper.writeValue(file, new CachedRoutes(LocalDateTime.now(), routes));
            log.debug("[RoutingCachePersistence] Saved {} routes to {}", routes.size(), path);
        } catch (Exception e) {
            log.warn("[RoutingCachePersistence] Failed to write cache file {}: {}", path, e.getMessage());
        }
    }

    /**
     * Reads routes from the cache file.
     *
     * @return present if file exists and is readable; empty if not found or corrupt
     */
    public Optional<CachedRoutes> load() {
        String path = properties.getCacheFile();
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        File file = new File(path);
        if (!file.exists()) {
            log.debug("[RoutingCachePersistence] Cache file not found at {}", path);
            return Optional.empty();
        }
        try {
            CachedRoutes cached = objectMapper.readValue(file, CachedRoutes.class);
            log.debug("[RoutingCachePersistence] Read {} routes from cache file (savedAt={})",
                    cached.getRoutes().size(), cached.getSavedAt());
            return Optional.of(cached);
        } catch (Exception e) {
            log.warn("[RoutingCachePersistence] Cache file corrupt or unreadable at {}: {}", path, e.getMessage());
            return Optional.empty();
        }
    }

    // ── Inner model ───────────────────────────────────────────────────

    @Data
    @NoArgsConstructor
    public static class CachedRoutes {
        private LocalDateTime savedAt;
        private Map<String, String> routes;

        public CachedRoutes(LocalDateTime savedAt, Map<String, String> routes) {
            this.savedAt = savedAt;
            this.routes = routes;
        }
    }
}
