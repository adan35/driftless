package io.driftless.partnersim;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Boots the partner simulator — a separately-deployable service that gives Driftless a real network
 * boundary to fail against (injectable latency / failure / timeout on an external leg, per Spec 04).
 *
 * <p>It shares no process or in-JVM call path with the monolith {@code app}; the saga reaches it
 * over HTTP only.
 */
@SpringBootApplication
public class PartnerSimulatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(PartnerSimulatorApplication.class, args);
    }
}
