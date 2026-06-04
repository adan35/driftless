package io.driftless.idempotency.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.driftless.outbox.internal.EventPublisher;
import io.driftless.outbox.internal.LoggingEventPublisher;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Publishes the Spec 02 module's default infrastructure beans: the in-process {@link EventPublisher}
 * and an {@link ObjectMapper} for serializing stored idempotent results.
 *
 * <p>Registered as an {@link AutoConfiguration} (not a component-scanned {@code @Configuration}) so it
 * is processed <em>after</em> application beans; combined with {@link ConditionalOnMissingBean}, a
 * host application's own {@code ObjectMapper} or a real broker adapter cleanly replaces these defaults
 * with no duplicate-bean clash. Listed in {@code
 * META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}.
 *
 * <p>Providing the {@code ObjectMapper} here (rather than relying on the host configuring Jackson)
 * keeps this library self-sufficient: the replay guard can serialize/deserialize results even when
 * embedded in an application that has not wired web/JSON auto-configuration. The module's own beans
 * (guard, outbox writer, relay) and its JPA persistence are wired by the component-scanned {@link
 * IdempotencyModuleConfig}, mirroring how the ledger separates its scanned persistence config from
 * its auto-configured default {@code HoldView}.
 */
@AutoConfiguration
public class IdempotencyAutoConfiguration {

    /** Default in-process publisher — replaced by any application-supplied {@link EventPublisher}. */
    @Bean
    @ConditionalOnMissingBean(EventPublisher.class)
    public EventPublisher loggingEventPublisher() {
        return new LoggingEventPublisher();
    }

    /**
     * Default {@link ObjectMapper} for the response blob, with JSR-310 support so {@code Instant} and
     * other time types in stored results round-trip. Yields to any {@code ObjectMapper} the host
     * application already defines.
     */
    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    public ObjectMapper idempotencyObjectMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }
}
