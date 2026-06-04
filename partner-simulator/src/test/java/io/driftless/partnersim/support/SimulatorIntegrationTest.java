package io.driftless.partnersim.support;

import io.driftless.partnersim.control.ControlService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Base for HTTP-level tests: boots the simulator on a random port (a real network boundary, exactly
 * what Spec 04 requires) and resets all faults + recorded state before each test for isolation.
 *
 * <p>Uses the Spring Boot 4 {@link RestTestClient}, auto-configured against the running server for a
 * {@code RANDOM_PORT} web environment, so the tests exercise the simulator over real HTTP.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
public abstract class SimulatorIntegrationTest {

    @Autowired
    protected RestTestClient client;

    @Autowired
    private ControlService controlService;

    @BeforeEach
    void resetSimulator() {
        controlService.resetAll();
    }
}
