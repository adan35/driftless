package io.driftless.auth.web.dto;

import io.driftless.auth.api.AuthorizationStatus;
import io.driftless.auth.api.AuthorizationView;

/** Response for capture/reverse: the authorization's resulting status. */
public record StatusResponse(AuthorizationStatus status) {

    public static StatusResponse from(AuthorizationView view) {
        return new StatusResponse(view.status());
    }
}
