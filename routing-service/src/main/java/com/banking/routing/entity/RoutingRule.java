package com.banking.routing.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "routing_rules")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RoutingRule {

    @Id
    @Column(name = "event_type", length = 100)
    private String eventType;

    @Column(name = "topic", nullable = false, length = 255)
    private String topic;

    @Column(name = "owner_service", nullable = false, length = 100)
    private String ownerService;

    @Column(name = "direction", nullable = false, length = 10)
    private String direction;           // PUBLISH | SUBSCRIBE

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "active", nullable = false)
    private Boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
