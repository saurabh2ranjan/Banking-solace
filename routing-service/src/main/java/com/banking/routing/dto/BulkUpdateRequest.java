package com.banking.routing.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BulkUpdateRequest {

    /** Full set of routes to replace. Every entry in the list will be processed. */
    @NotEmpty(message = "routes must not be empty")
    @Valid
    private List<RouteUpdate> routes;

    private String changedBy;
    private String reason;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RouteUpdate {
        @NotBlank(message = "eventType is required")
        private String eventType;
        @NotBlank(message = "topic is required")
        private String topic;
    }
}
