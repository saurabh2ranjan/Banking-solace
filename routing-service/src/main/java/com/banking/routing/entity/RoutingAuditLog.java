package com.banking.routing.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "routing_audit_log")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RoutingAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_type", length = 100)
    private String eventType;

    @Column(name = "old_topic", length = 255)
    private String oldTopic;

    @Column(name = "new_topic", length = 255)
    private String newTopic;

    @Column(name = "changed_by", length = 100)
    private String changedBy;

    @Column(name = "changed_at", nullable = false)
    private LocalDateTime changedAt;

    @Column(name = "reason", length = 500)
    private String reason;
}
