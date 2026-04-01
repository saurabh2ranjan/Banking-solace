package com.banking.payment.routing;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * L2 cache — reads routing rules from Redis hash written by routing-service.
 *
 * Key:   routing:rules  (Hash)
 * Field: eventType      e.g. PAYMENT_INITIATED
 * Value: topic          e.g. banking/v1/payment/initiated
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RedisRoutingCache {

    static final String ROUTING_RULES_KEY = "routing:rules";

    private final StringRedisTemplate redisTemplate;

    /**
     * Reads all routing rules from Redis.
     *
     * @return map of eventType → topic, or empty map if Redis has no data
     * @throws RuntimeException if Redis is unreachable
     */
    @SuppressWarnings("unchecked")
    public Map<String, String> getAll() {
        Map<Object, Object> raw = redisTemplate.opsForHash().entries(ROUTING_RULES_KEY);
        Map<String, String> result = (Map<String, String>) (Map<?, ?>) raw;
        log.debug("[RedisRoutingCache] Read {} routes from Redis", result.size());
        return result;
    }

    public boolean hasRoutes() {
        Long size = redisTemplate.opsForHash().size(ROUTING_RULES_KEY);
        return size != null && size > 0;
    }
}
