package com.banking.payment.dto;

import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentResponse {
    private String id;
    private String paymentReference;
    private String fromAccountId;
    private String toAccountId;
    private BigDecimal amount;
    private String description;
    private String status;
    private String failureReason;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
}
