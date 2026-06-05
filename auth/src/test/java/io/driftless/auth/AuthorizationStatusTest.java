package io.driftless.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.driftless.auth.api.AuthorizationStatus;
import org.junit.jupiter.api.Test;

/** Unit checks for the lifecycle state classification (no Spring context). */
class AuthorizationStatusTest {

    @Test
    void terminalStatesAreDeclinedCapturedReversed() {
        assertThat(AuthorizationStatus.DECLINED.isTerminal()).isTrue();
        assertThat(AuthorizationStatus.CAPTURED.isTerminal()).isTrue();
        assertThat(AuthorizationStatus.REVERSED.isTerminal()).isTrue();
        assertThat(AuthorizationStatus.AUTHORIZING.isTerminal()).isFalse();
        assertThat(AuthorizationStatus.AUTHORIZED.isTerminal()).isFalse();
        assertThat(AuthorizationStatus.COMPENSATING.isTerminal()).isFalse();
    }

    @Test
    void inFlightStatesAreAuthorizingAndCompensating() {
        assertThat(AuthorizationStatus.AUTHORIZING.isInFlight()).isTrue();
        assertThat(AuthorizationStatus.COMPENSATING.isInFlight()).isTrue();
        assertThat(AuthorizationStatus.AUTHORIZED.isInFlight()).isFalse();
        assertThat(AuthorizationStatus.CAPTURED.isInFlight()).isFalse();
    }
}
