package io.driftless.partnersim.partner.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request the saga sends to the partner's {@code reverse} leg to reverse a prior authorization
 * (the compensating action the saga runs on timeout/failure).
 *
 * @param requestId caller-chosen idempotency key for this reversal (required)
 * @param partnerRef the {@code partnerRef} of the authorization being reversed (required)
 */
public record ReverseRequest(@NotBlank String requestId, @NotBlank String partnerRef) {}
