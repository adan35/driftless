package io.driftless.partnersim.partner.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Request the saga sends to the partner's {@code capture} leg to confirm a prior authorization.
 *
 * @param requestId caller-chosen idempotency key for this capture (required)
 * @param partnerRef the {@code partnerRef} returned by the original authorize (required)
 * @param amountMinor amount to capture in minor units, opaque to the simulator (must be {@code >= 0})
 */
public record CaptureRequest(@NotBlank String requestId, @NotBlank String partnerRef, @Min(0) long amountMinor) {}
