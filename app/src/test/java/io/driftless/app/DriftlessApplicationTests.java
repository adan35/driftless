package io.driftless.app;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Smoke test: the modular monolith context wires every feature module together.
 *
 * <p>Since Spec 01 the ledger contributes JPA + Flyway, so booting the context needs a real
 * datasource; a Testcontainers Postgres (wired via {@link ServiceConnection}) provides it — the same
 * database the monolith runs against — and Flyway applies the ledger migrations on startup.
 */
@SpringBootTest
@Import(DriftlessApplicationTests.PostgresContainerConfig.class)
class DriftlessApplicationTests {

    @TestConfiguration(proxyBeanMethods = false)
    static class PostgresContainerConfig {
        @Bean
        @ServiceConnection
        PostgreSQLContainer<?> postgresContainer() {
            return new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));
        }
    }

    @Test
    void contextLoads() {}
}
