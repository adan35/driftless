package io.driftless.partnersim.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for the simulator. Provides the injected {@link Clock} the rest of the service uses for
 * timestamps, keeping {@code Instant.now()} / {@code new Date()} out of business logic (project rule)
 * and making time mockable in tests.
 */
@Configuration
public class SimulatorConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
