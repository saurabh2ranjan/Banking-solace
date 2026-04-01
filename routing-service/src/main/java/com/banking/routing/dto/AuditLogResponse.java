package com.banking.routing.dto;

import lombok.*;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLogResponse {
    private Long id;
    private String eventType;
    private String oldTopic;
    private String newTopic;
    private String changedBy;
    private LocalDateTime changedAt;
    private String reason;
}
