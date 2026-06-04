package io.driftless.partnersim.partner.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request the auth saga (Spec 03) sends to the partner's {@code authorize} leg. Fields are opaque to
 * the simulator — {@code amountMinor}/{@code currency} are passed through and echoed, never summed or
 * interpreted as money (this service holds no ledger logic).
 *
 * @param requestId caller-chosen idempotency key for this request (required)
 * @param cardRef opaque card reference / token — never a raw PAN (required)
 * @param amountMinor amount in minor units, opaque to the simulator (must be {@code >= 0})
 * @param currency ISO-4217 currency code, opaque to the simulator (required)
 * @param mcc optional merchant category code
 * @param merchantId optional merchant identifier
 */
public record AuthorizeRequest(
        @NotBlank String requestId,
        @NotBlank String cardRef,
        @Min(0) long amountMinor,
        @NotBlank @Size(min = 3, max = 3) String currency,
        String mcc,
        String merchantId) {}
