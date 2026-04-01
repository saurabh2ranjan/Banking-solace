package com.banking.routing.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Manages the routing:rules Redis hash.
 *
 * Structure:
 *   Key:   routing:rules   (Hash)
 *   Field: eventType       e.g. ACCOUNT_CREATED
 *   Value: topic           e.g. banking/v1/account/created
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RoutingRedisService {

    static final String ROUTING_RULES_KEY = "routing:rules";

    private final StringRedisTemplate redisTemplate;

    /** Write a single route to Redis. */
    public void put(String eventType, String topic) {
        redisTemplate.opsForHash().put(ROUTING_RULES_KEY, eventType, topic);
        log.debug("[Redis] SET routing:rules[{}] = {}", eventType, topic);
    }

    /** Bulk-load all routes into Redis (called at startup). */
    public void putAll(Map<String, String> routes) {
        redisTemplate.opsForHash().putAll(ROUTING_RULES_KEY, routes);
        log.info("[Redis] Loaded {} routes into routing:rules", routes.size());
    }

    /** Read all routes from Redis. Returns empty map if key does not exist. */
    @SuppressWarnings("unchecked")
    public Map<String, String> getAll() {
        Map<Object, Object> raw = redisTemplate.opsForHash().entries(ROUTING_RULES_KEY);
        return (Map<String, String>) (Map<?, ?>) raw;
    }

    public boolean hasRoutes() {
        Long size = redisTemplate.opsForHash().size(ROUTING_RULES_KEY);
        return size != null && size > 0;
    }
}
