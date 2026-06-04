package io.driftless.tokens.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * Unit coverage for the defensive {@code IllegalArgumentException} → {@code 400} mapping (Spec 06
 * review fix I3): a future code path that surfaces a raw {@link IllegalArgumentException} from the
 * service must become a {@code 400 Bad Request}, never an unhandled {@code 500}.
 */
class TokenExceptionHandlerTest {

    private final TokenExceptionHandler handler = new TokenExceptionHandler();

    @Test
    void illegalArgumentMapsToBadRequestWithItsMessage() {
        ProblemDetail problem =
                handler.handleIllegalArgument(new IllegalArgumentException("idempotencyKey must not be blank"));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getDetail()).isEqualTo("idempotencyKey must not be blank");
    }
}
