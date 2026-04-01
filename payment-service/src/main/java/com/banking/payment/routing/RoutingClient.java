package com.banking.payment.routing;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Calls routing-service REST API to fetch all active routing rules.
 * This is the primary source — tried first at every startup.
 */
@Component
@Slf4j
public class RoutingClient {

    private final RestClient restClient;
    private final RoutingProperties properties;

    public RoutingClient(RoutingProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.builder()
                .baseUrl(properties.getServiceUrl())
                .build();
    }

    /**
     * Fetches all active routes from routing-service.
     *
     * @return map of eventType → topic
     * @throws RuntimeException if routing-service is unreachable or returns an error
     */
    public Map<String, String> fetchRoutes() {
        log.debug("[RoutingClient] Calling GET {}/api/routes", properties.getServiceUrl());

        List<Map<String, Object>> routes = restClient.get()
                .uri("/api/routes")
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});

        if (routes == null || routes.isEmpty()) {
            throw new RuntimeException("routing-service returned empty routes list");
        }

        Map<String, String> result = routes.stream()
                .filter(r -> Boolean.TRUE.equals(r.get("active")))
                .collect(Collectors.toMap(
                        r -> (String) r.get("eventType"),
                        r -> (String) r.get("topic")
                ));

        log.info("[RoutingClient] Fetched {} routes from routing-service", result.size());
        return result;
    }
}
