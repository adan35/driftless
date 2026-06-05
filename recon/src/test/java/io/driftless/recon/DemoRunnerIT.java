package io.driftless.recon;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.driftless.recon.api.DemoResult;
import io.driftless.recon.internal.demo.DemoRunner;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The Spec 09 demo orchestration, driven against the real saga + Testcontainers ledger: it seeds
 * funded accounts, drives a load mix, (optionally) injects partner faults over a control plane, drains
 * the recovery sweep and reconciles — and the proof is always zero drift. A tiny local control stub
 * stands in for the partner-simulator's control plane (the in-process {@link ControllablePartner}
 * produces the actual partner misbehaviour the saga survives).
 */
class DemoRunnerIT extends AbstractReconIT {

    private static final AtomicInteger CONTROL_CALLS = new AtomicInteger();
    private static final HttpServer CONTROL_STUB = startControlStub();

    @Autowired
    DemoRunner demoRunner;

    @DynamicPropertySource
    static void controlPlane(DynamicPropertyRegistry registry) {
        // DemoRunner is an always-present @Component, but set the demo flag explicitly to mirror the
        // composed stack (and stay green now that the demo controller is disabled by default).
        registry.add("driftless.demo.enabled", () -> "true");
        registry.add(
                "driftless.recon.partner.control-base-url",
                () -> "http://127.0.0.1:" + CONTROL_STUB.getAddress().getPort());
    }

    @Test
    void seedsLoadsAndReconcilesToZeroDriftWithoutFaults() {
        PARTNER.setAuthorizeMode(ControllablePartner.AuthorizeMode.APPROVE);

        DemoResult result = demoRunner.run(24, 4, 3, 1_000_000L, "NONE", 11L);

        assertThat(result.accountsSeeded()).isEqualTo(3);
        assertThat(result.fundingMinor()).isEqualTo(1_000_000L);
        assertThat(result.faultsInjected()).isEmpty();
        assertThat(result.load().totalOperations()).isEqualTo(24);
        assertThat(result.load().failed()).isZero();
        assertThat(result.load().approved()).isPositive();
        assertThat(result.reconciliation().passed()).isTrue();
        assertThat(result.reconciliation().totalDriftMinor()).isZero();
        assertThat(globalSignedSum()).isZero();
    }

    @Test
    void injectsFaultsOverTheControlPlaneAndStillReconcilesToZeroDrift() {
        int before = CONTROL_CALLS.get();
        PARTNER.setAuthorizeMode(ControllablePartner.AuthorizeMode.TIMEOUT);
        PARTNER.setTimeoutSleepMillis(700L);

        DemoResult result = demoRunner.run(12, 4, 2, 1_000_000L, "TIMEOUT", 5L);

        // The control plane was actually driven (a real HTTP hop to inject + reset the fault).
        assertThat(CONTROL_CALLS.get()).isGreaterThan(before);
        assertThat(result.faultsInjected()).isNotEmpty();
        // The partner timed out, so the bounded-timeout compensating reversal fired — yet zero drift.
        assertThat(result.load().compensated()).isPositive();
        assertThat(result.reconciliation().passed()).isTrue();
        assertThat(result.reconciliation().totalDriftMinor()).isZero();
        assertThat(globalSignedSum()).isZero();
    }

    private static HttpServer startControlStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/control/faults", DemoRunnerIT::handleControl);
            server.setExecutor(Executors.newFixedThreadPool(4));
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException("failed to start control stub", e);
        }
    }

    private static void handleControl(HttpExchange exchange) throws IOException {
        CONTROL_CALLS.incrementAndGet();
        exchange.getRequestBody().readAllBytes();
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
    }
}
