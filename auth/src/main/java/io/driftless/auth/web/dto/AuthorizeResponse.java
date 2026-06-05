package io.driftless.auth.web.dto;

import io.driftless.auth.api.AuthorizationStatus;
import io.driftless.auth.api.AuthorizationView;
import java.util.UUID;

/** Response for {@code POST /authorizations}: the new authorization id and its resulting state. */
public record AuthorizeResponse(UUID authId, AuthorizationStatus status) {

    public static AuthorizeResponse from(AuthorizationView view) {
        return new AuthorizeResponse(view.id(), view.status());
    }
}
