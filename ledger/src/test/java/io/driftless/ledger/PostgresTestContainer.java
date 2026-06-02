package io.driftless.ledger;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared real-Postgres container for integration tests, wired into the context by {@link
 * ServiceConnection} so Spring Boot derives the datasource URL/credentials automatically.
 *
 * <p>Flyway runs the ledger's migrations against this container, so the integration tests exercise
 * the real schema, indexes and append-only trigger — not an in-memory substitute.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestContainer {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));
    }
}
