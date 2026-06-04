package io.driftless.partnersim.partner.dto;

/**
 * Response from the partner's {@code reverse} leg.
 *
 * @param requestId echoes the request's idempotency key
 * @param partnerRef the authorization reference that was reversed
 * @param reversed whether the reversal succeeded
 * @param duplicate {@code true} on a duplicate emission injected by the {@code DUPLICATE} fault
 */
public record ReverseResponse(String requestId, String partnerRef, boolean reversed, boolean duplicate) {

    public static ReverseResponse reversed(String requestId, String partnerRef) {
        return new ReverseResponse(requestId, partnerRef, true, false);
    }

    public ReverseResponse asDuplicate() {
        return new ReverseResponse(requestId, partnerRef, reversed, true);
    }
}
