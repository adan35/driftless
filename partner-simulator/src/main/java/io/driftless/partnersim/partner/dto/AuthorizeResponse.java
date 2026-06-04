package io.driftless.partnersim.partner.dto;

/**
 * Response from the partner's {@code authorize} leg.
 *
 * @param requestId echoes the request's idempotency key
 * @param partnerRef partner-assigned reference for the authorization (stable across replays)
 * @param approved whether the partner approved the authorization
 * @param declineReason populated when {@code approved == false}, otherwise {@code null}
 * @param duplicate {@code true} on a duplicate emission injected by the {@code DUPLICATE} fault
 */
public record AuthorizeResponse(
        String requestId, String partnerRef, boolean approved, String declineReason, boolean duplicate) {

    public static AuthorizeResponse approved(String requestId, String partnerRef) {
        return new AuthorizeResponse(requestId, partnerRef, true, null, false);
    }

    public static AuthorizeResponse declined(String requestId, String partnerRef, String reason) {
        return new AuthorizeResponse(requestId, partnerRef, false, reason, false);
    }

    /** A copy marked as a duplicate emission (same partnerRef, flagged for the caller/test). */
    public AuthorizeResponse asDuplicate() {
        return new AuthorizeResponse(requestId, partnerRef, approved, declineReason, true);
    }
}
