package com.banking.account.routing;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "app.routing")
@Data
public class RoutingProperties {

    /** Base URL of routing-service. Overridden per profile. */
    private String serviceUrl = "http://localhost:8085";

    /** Absolute path for the last-known-good cache file (L3). */
    private String cacheFile;

    /** Hours after which a file cache entry is considered stale (still used, but with loud WARN). */
    private int cacheMaxAgeHours = 24;

    /** HTTP connect timeout when calling routing-service (seconds). */
    private int connectTimeoutSeconds = 3;

    /** How many times to retry routing-service before falling back. */
    private int retryAttempts = 2;

    /**
     * Event types this service MUST have routes for.
     * RoutingValidator warns at startup if any are missing.
     */
    private List<String> requiredEventTypes = new ArrayList<>();

    /**
     * L4 fallback topics — used only if routing-service, Redis, and file cache
     * are all unavailable at startup.
     */
    private Map<String, String> fallbackTopics = new HashMap<>();
}
