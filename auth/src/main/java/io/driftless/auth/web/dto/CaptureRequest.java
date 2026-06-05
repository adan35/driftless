package io.driftless.auth.web.dto;

/**
 * Request body for {@code POST /authorizations/{id}/capture}. {@code amountMinor} is optional: when
 * absent (or null) the full authorized amount is captured; when present it must be a positive partial
 * amount not exceeding the authorized amount.
 *
 * @param amountMinor optional partial capture amount in minor units
 */
public record CaptureRequest(Long amountMinor) {}
