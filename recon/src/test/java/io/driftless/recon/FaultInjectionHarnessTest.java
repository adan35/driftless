package io.driftless.recon;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.driftless.recon.internal.fault.FaultInjectionHarness;
import io.driftless.recon.internal.fault.FaultKind;
import io.driftless.recon.internal.fault.FaultRequest;
import io.driftless.recon.internal.fault.FaultRoute;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * The fault-injection harness drives the partner-simulator's control plane: it POSTs well-formed
 * {@code FaultProfile} bodies to {@code /control/faults} and resets via {@code /control/faults/reset}.
 * Verified here against a tiny in-test control-plane stub (the real cross-service run is the demo's
 * job); this proves the wire contract without standing up the separate service.
 */
class FaultInjectionHarnessTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> appliedBodies = new CopyOnWriteArrayList<>();
    private final List<String> resetPaths = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private FaultInjectionHarness harness;

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/control/faults/reset", exchange -> {
            resetPaths.add(exchange.getRequestURI().toString());
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.createContext("/control/faults", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            appliedBodies.add(new String(body));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        RestClient client = RestClient.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .build();
        harness = new FaultInjectionHarness(client);
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    @Test
    void injectsEachFaultModeWithTheExpectedWireBody() throws Exception {
        harness.inject(FaultRequest.timeout(FaultRoute.AUTHORIZE, 2_000, 3));
        harness.inject(FaultRequest.failBeforeResponse(FaultRoute.AUTHORIZE, 1));
        harness.inject(FaultRequest.duplicate(FaultRoute.CAPTURE, 2));
        harness.inject(FaultRequest.decline(FaultRoute.AUTHORIZE, 0.5, 7));
        harness.inject(FaultRequest.errorRate(FaultRoute.REVERSE, 0.25, 9));
        harness.inject(FaultRequest.latency(FaultRoute.CAPTURE, 150));

        assertThat(appliedBodies).hasSize(6);
        List<JsonNode> nodes = new ArrayList<>();
        for (String body : appliedBodies) {
            nodes.add(mapper.readTree(body));
        }
        // Field names match the simulator's FaultProfile so the body binds without a shared type.
        JsonNode timeout = nodes.get(0);
        assertThat(timeout.get("route").asText()).isEqualTo(FaultRoute.AUTHORIZE.name());
        assertThat(timeout.get("mode").asText()).isEqualTo(FaultKind.TIMEOUT.name());
        assertThat(timeout.get("delayMillis").asLong()).isEqualTo(2_000L);
        assertThat(timeout.get("applyToNext").asLong()).isEqualTo(3L);
        assertThat(nodes.get(1).get("mode").asText()).isEqualTo(FaultKind.FAIL_BEFORE_RESPONSE.name());
        assertThat(nodes.get(2).get("mode").asText()).isEqualTo(FaultKind.DUPLICATE.name());
        assertThat(nodes.get(3).get("probability").asDouble()).isEqualTo(0.5);
        assertThat(nodes.get(4).get("mode").asText()).isEqualTo(FaultKind.ERROR_RATE.name());
        assertThat(nodes.get(5).get("mode").asText()).isEqualTo(FaultKind.LATENCY.name());
    }

    @Test
    void resetAndResetAllHitTheControlPlane() {
        harness.reset();
        harness.resetAll();

        assertThat(resetPaths).hasSize(2);
        assertThat(resetPaths.get(0)).isEqualTo("/control/faults/reset");
        assertThat(resetPaths.get(1)).contains("state=true");
    }
}
