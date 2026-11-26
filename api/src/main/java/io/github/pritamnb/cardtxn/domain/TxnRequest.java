package io.github.pritamnb.cardtxn.domain;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Incoming card swipe. The client sends an idempotency key so a retried request
 * (e.g. after a network timeout) never creates a second charge.
 */
public record TxnRequest(
        @NotBlank @Size(max = 64) String idempotencyKey,
        @NotBlank @Pattern(regexp = "\\d{16}", message = "must be 16 digits") String cardId,
        @NotNull @DecimalMin(value = "0.01") @Digits(integer = 7, fraction = 2) BigDecimal amount,
        @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "must be an ISO 4217 code") String currency,
        @NotBlank @Size(max = 100) String merchant) {
}
