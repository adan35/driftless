package io.driftless.common.time;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Publishes the application's single source of time.
 *
 * <p>Business code must depend on an injected {@link Clock} and never call {@code Instant.now()} or
 * {@code new Date()} directly, so that time can be frozen in tests with {@link Clock#fixed}. The
 * default here is {@link Clock#systemUTC()}; a test or environment may override it by supplying its
 * own {@code Clock} bean (for example via {@code @TestConfiguration}).
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
