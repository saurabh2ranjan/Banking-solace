package com.banking.notification.routing;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "app.routing")
@Data
public class RoutingProperties {

    /** Base URL of routing-service. Overridden per profile. */
    private String serviceUrl = "http://localhost:8085";

    /** HTTP connect timeout when calling routing-service (seconds). */
    private int connectTimeoutSeconds = 3;

    /**
     * Maps event type → subscribed topic as configured in application.yml bindings.
     * RoutingValidator checks these match what routing-service currently says.
     */
    private Map<String, String> consumerTopics = new HashMap<>();
}
