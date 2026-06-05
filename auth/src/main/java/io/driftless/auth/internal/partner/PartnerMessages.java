package io.driftless.auth.internal.partner;

import java.util.List;

/**
 * The wire shapes the saga exchanges with the partner-simulator over HTTP. These mirror the partner's
 * published contract (Spec 04) but are declared here so the auth module keeps no compile dependency on
 * that separate service — integration is HTTP only.
 */
public final class PartnerMessages {

    private PartnerMessages() {}

    /** POST {@code /partner/authorize} body. */
    public record AuthorizeRequest(
            String requestId, String cardRef, long amountMinor, String currency, String mcc, String merchantId) {}

    /** POST {@code /partner/authorize} response. */
    public record AuthorizeResponse(
            String requestId, String partnerRef, boolean approved, String declineReason, boolean duplicate) {}

    /** POST {@code /partner/capture} body. */
    public record CaptureRequest(String requestId, String partnerRef, long amountMinor) {}

    /** POST {@code /partner/capture} response. */
    public record CaptureResponse(String requestId, String partnerRef, boolean captured, boolean duplicate) {}

    /** POST {@code /partner/reverse} body. */
    public record ReverseRequest(String requestId, String partnerRef) {}

    /** POST {@code /partner/reverse} response. */
    public record ReverseResponse(String requestId, String partnerRef, boolean reversed, boolean duplicate) {}

    /** A recorded server-side side effect from {@code GET /partner/state/{requestId}}. */
    public record SideEffect(String partnerRef, boolean delivered) {}

    /**
     * GET {@code /partner/state/{requestId}} projection — enough of it to discover a partnerRef the
     * timeout may have hidden. Unknown extra fields are ignored on deserialization.
     */
    public record StateView(
            String requestId, boolean known, List<SideEffect> sideEffects, AuthorizeResponse response) {}
}
