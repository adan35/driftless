package io.driftless.partnersim;

import io.driftless.partnersim.support.SimulatorIntegrationTest;
import org.junit.jupiter.api.Test;

/**
 * Spec 08: the simulator must expose a Prometheus scrape endpoint so the stack's Prometheus can
 * collect its {@code http.server.requests} timings — where the partner-leg latency is measured. This
 * proves {@code /actuator/prometheus} is reachable and serves Micrometer's exposition over real HTTP.
 */
class PartnerSimulatorMetricsEndpointTest extends SimulatorIntegrationTest {

    @Test
    void prometheusEndpointIsExposed() {
        client.get()
                .uri("/actuator/prometheus")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .value(body -> org.assertj.core.api.Assertions.assertThat(body).contains("jvm_memory_used_bytes"));
    }
}
