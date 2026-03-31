package com.banking.account.dto;

import jakarta.validation.constraints.*;
import lombok.*;
import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateAccountRequest {

    @NotBlank(message = "Customer name is required")
    private String customerName;

    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    private String email;

    @NotNull(message = "Account type is required")
    private String accountType;

    @NotNull(message = "Initial balance is required")
    @DecimalMin(value = "0.0", message = "Initial balance cannot be negative")
    private BigDecimal initialBalance;
}
