package io.driftless.partnersim.partner.dto;

/**
 * Response from the partner's {@code capture} leg.
 *
 * @param requestId echoes the request's idempotency key
 * @param partnerRef the authorization reference that was captured
 * @param captured whether the capture succeeded
 * @param duplicate {@code true} on a duplicate emission injected by the {@code DUPLICATE} fault
 */
public record CaptureResponse(String requestId, String partnerRef, boolean captured, boolean duplicate) {

    public static CaptureResponse captured(String requestId, String partnerRef) {
        return new CaptureResponse(requestId, partnerRef, true, false);
    }

    public CaptureResponse asDuplicate() {
        return new CaptureResponse(requestId, partnerRef, captured, true);
    }
}
