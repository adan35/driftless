package io.driftless.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Boots the Driftless modular monolith.
 *
 * <p>{@code scanBasePackages = "io.driftless"} wires every feature module's beans (each lives under
 * {@code io.driftless.<module>}) into one application context. The separately-deployable {@code
 * partner-simulator} has its own main and is reached only over HTTP.
 */
@SpringBootApplication(scanBasePackages = "io.driftless")
public class DriftlessApplication {

    public static void main(String[] args) {
        SpringApplication.run(DriftlessApplication.class, args);
    }
}
