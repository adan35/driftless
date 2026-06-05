package io.driftless.recon;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Proves the seed-only demo surface is OFF by default. With {@code driftless.demo.enabled} unset (the
 * normal/prod boot), the {@link io.driftless.recon.web.DemoController} bean must not be registered, so
 * {@code POST /demo/run} resolves to no handler and returns {@code 404}. This is the regression guard
 * for review C1 / QA defect #2 — a future flip back to {@code matchIfMissing = true} would fail here.
 *
 * <p>Deliberately does not extend the demo-enabling ITs and does not set the flag, so it gets its own
 * application context with the demo endpoint genuinely absent.
 */
@AutoConfigureRestTestClient
class DemoControllerDisabledIT extends AbstractReconIT {

    @Autowired
    RestTestClient client;

    @Test
    void demoEndpointIsAbsentWhenTheFlagIsUnset() {
        client.post()
                .uri("/demo/run")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("count", 4, "concurrency", 2, "accounts", 1, "faultMix", "NONE"))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
