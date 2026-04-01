package com.banking.routing.dto;

import lombok.*;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RouteResponse {
    private String eventType;
    private String topic;
    private String ownerService;
    private String direction;
    private String description;
    private Boolean active;
    private LocalDateTime updatedAt;
}
