package io.driftless.auth.api;

import io.driftless.common.error.DomainException;
import java.util.UUID;

/**
 * Thrown when a saga operation is attempted from a state that does not permit it — e.g. capturing an
 * authorization that is not {@link AuthorizationStatus#AUTHORIZED}, or reversing one that is already
 * terminal in a non-reversible way. The web layer maps this to {@code 409 Conflict}.
 *
 * <p>This is the state-machine guard: it changes nothing and names both the current state and the
 * attempted operation so a caller can tell a genuine conflict from a transient race.
 */
public class IllegalAuthorizationState extends DomainException {

    public IllegalAuthorizationState(UUID id, AuthorizationStatus current, String operation) {
        super("Authorization '%s' cannot %s from state %s".formatted(id, operation, current));
    }
}
