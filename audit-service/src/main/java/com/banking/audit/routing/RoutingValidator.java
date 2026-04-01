package com.banking.audit.routing;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Validates at startup that the topics configured in application.yml bindings
 * match what routing-service currently says they should be.
 *
 * Consumer services cannot change subscriptions at runtime — a mismatch means
 * a restart is required after updating routes in routing-service.
 *
 * Does NOT block startup — logs WARN on mismatch or routing-service unavailability.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RoutingValidator {

    private final RoutingProperties properties;

    @EventListener(ApplicationReadyEvent.class)
    public void validate() {
        log.info("━━━ [RoutingValidator] Validating consumer topic bindings ━━━");

        if (properties.getConsumerTopics().isEmpty()) {
            log.warn("[RoutingValidator] No consumer-topics configured — skipping validation");
            return;
        }

        Map<String, String> routingServiceRules = fetchFromRoutingService();
        if (routingServiceRules == null) {
            log.warn("[RoutingValidator] WARN: routing-service unreachable — skipping topic validation");
            log.warn("[RoutingValidator] Cannot confirm application.yml topics are current");
            return;
        }

        boolean allMatch = true;
        for (Map.Entry<String, String> entry : properties.getConsumerTopics().entrySet()) {
            String eventType  = entry.getKey();
            String localTopic = entry.getValue();
            String remoteTopic = routingServiceRules.get(eventType);

            if (remoteTopic == null) {
                log.warn("[RoutingValidator] WARN: routing-service has no rule for '{}' — using application.yml value: {}",
                        eventType, localTopic);
                allMatch = false;
            } else if (!localTopic.equals(remoteTopic)) {
                log.warn("┌─────────────────────────────────────────────────────────┐");
                log.warn("│ [RoutingValidator] TOPIC MISMATCH DETECTED              │");
                log.warn("│ EventType   : {}",  eventType);
                log.warn("│ application : {}",  localTopic);
                log.warn("│ routing-svc : {}",  remoteTopic);
                log.warn("│ ACTION: Update application.yml and restart to apply     │");
                log.warn("└─────────────────────────────────────────────────────────┘");
                allMatch = false;
            } else {
                log.info("[RoutingValidator]  OK  {} → {}", eventType, localTopic);
            }
        }

        if (allMatch) {
            log.info("━━━ [RoutingValidator] All consumer topics are current ✓ ━━━");
        } else {
            log.warn("━━━ [RoutingValidator] Topic mismatches found — restart may be required ━━━");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> fetchFromRoutingService() {
        try {
            RestClient client = RestClient.builder()
                    .baseUrl(properties.getServiceUrl())
                    .build();

            List<Map<String, Object>> routes = client.get()
                    .uri("/api/routes")
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});

            if (routes == null) return Map.of();

            return routes.stream()
                    .filter(r -> Boolean.TRUE.equals(r.get("active")))
                    .collect(Collectors.toMap(
                            r -> (String) r.get("eventType"),
                            r -> (String) r.get("topic")
                    ));
        } catch (RestClientException e) {
            log.warn("[RoutingValidator] Could not reach routing-service at {}: {}",
                    properties.getServiceUrl(), e.getMessage());
            return null;
        }
    }
}
