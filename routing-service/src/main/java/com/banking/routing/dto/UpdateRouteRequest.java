package com.banking.routing.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateRouteRequest {

    @NotBlank(message = "topic must not be blank")
    private String topic;

    private String reason;      // optional — stored in audit log
    private String changedBy;   // optional — e.g. "ops-team", "admin-ui"
}
