package io.driftless.auth.api;

import io.driftless.common.error.DomainException;
import java.util.UUID;

/**
 * Thrown when an authorization id does not resolve to a stored authorization. The web layer maps this
 * to {@code 404 Not Found}.
 */
public class AuthorizationNotFound extends DomainException {

    public AuthorizationNotFound(UUID id) {
        super("No authorization found with id '%s'".formatted(id));
    }
}
