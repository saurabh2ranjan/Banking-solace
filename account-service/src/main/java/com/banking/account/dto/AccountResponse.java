package com.banking.account.dto;

import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AccountResponse {
    private String id;
    private String accountNumber;
    private String customerName;
    private String email;
    private String accountType;
    private BigDecimal balance;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
