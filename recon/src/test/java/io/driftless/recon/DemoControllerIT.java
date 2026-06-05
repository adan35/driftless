package io.driftless.recon;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * The thin, seed-only demo endpoint over HTTP: it drives the live saga and returns the load summary +
 * reconciliation proof. Both the with-body and no-body forms resolve to a zero-drift proof. A tiny
 * local control stub stands in for the partner-simulator's control plane.
 */
@AutoConfigureRestTestClient
class DemoControllerIT extends AbstractReconIT {

    private static final HttpServer CONTROL_STUB = startControlStub();

    @Autowired
    RestTestClient client;

    private final ObjectMapper mapper = new ObjectMapper();

    @DynamicPropertySource
    static void controlPlane(DynamicPropertyRegistry registry) {
        // The demo controller is disabled by default (matchIfMissing=false); enable it for this IT.
        registry.add("driftless.demo.enabled", () -> "true");
        registry.add(
                "driftless.recon.partner.control-base-url",
                () -> "http://127.0.0.1:" + CONTROL_STUB.getAddress().getPort());
    }

    @Test
    void runsTheDemoWithAnExplicitBodyAndProvesZeroDrift() throws Exception {
        PARTNER.setAuthorizeMode(ControllablePartner.AuthorizeMode.APPROVE);
        Map<String, Object> body = Map.of(
                "count",
                16,
                "concurrency",
                4,
                "accounts",
                2,
                "fundingMinor",
                1_000_000L,
                "faultMix",
                "NONE",
                "seed",
                3);

        String json = client.post()
                .uri("/demo/run")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        JsonNode result = mapper.readTree(json);
        assertThat(result.get("accountsSeeded").asInt()).isEqualTo(2);
        assertThat(result.get("load").get("totalOperations").asInt()).isEqualTo(16);
        assertThat(result.get("reconciliation").get("passed").asBoolean()).isTrue();
        assertThat(result.get("reconciliation").get("totalDriftMinor").asLong()).isZero();
        assertThat(globalSignedSum()).isZero();
    }

    @Test
    void runsTheDemoWithNoBodyUsingDefaults() throws Exception {
        PARTNER.setAuthorizeMode(ControllablePartner.AuthorizeMode.APPROVE);

        String json = client.post()
                .uri("/demo/run")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        JsonNode result = mapper.readTree(json);
        // Defaults: 4 accounts, 40 operations.
        assertThat(result.get("accountsSeeded").asInt()).isEqualTo(4);
        assertThat(result.get("load").get("totalOperations").asInt()).isEqualTo(40);
        assertThat(result.get("reconciliation").get("passed").asBoolean()).isTrue();
        assertThat(globalSignedSum()).isZero();
    }

    private static HttpServer startControlStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/control/faults", DemoControllerIT::handleControl);
            server.setExecutor(Executors.newFixedThreadPool(4));
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException("failed to start control stub", e);
        }
    }

    private static void handleControl(HttpExchange exchange) throws IOException {
        exchange.getRequestBody().readAllBytes();
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
    }
}
