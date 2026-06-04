package io.driftless.tokens;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared real-Postgres container for the tokens integration tests, wired into the context by {@link
 * ServiceConnection} so Spring Boot derives the datasource URL/credentials automatically.
 *
 * <p>Flyway runs three independent module histories against this container, each starting at {@code
 * V1} with its own history table: the ledger's default {@code db/migration} (on the classpath via
 * the inward dependency), the idempotency module's {@code db/migration_idempotency} (the guard and
 * outbox tables every transition writes), and this module's {@code db/migration_tokens} (the {@code
 * token} / {@code token_status_history} schema). The tests therefore exercise the real schema,
 * constraints and the append-only trigger — not an in-memory substitute.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestContainer {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));
    }

    /**
     * Apply all three module histories. The auto-configured Flyway points at the ledger's default
     * location ({@code classpath:db/migration}); we run it, then run the idempotency and tokens
     * timelines from their sibling locations into separate history tables. Each baselines its own
     * history table over the already-populated schema (baselineVersion=0 so its own {@code V1} still
     * runs). Mirrors how the {@code app} runtime composes per-module migrations.
     */
    @Bean
    FlywayMigrationStrategy moduleMigrations(DataSource dataSource) {
        return ledgerFlyway -> {
            ledgerFlyway.migrate();
            migrateModule(dataSource, "classpath:db/migration_idempotency", "flyway_schema_history_idempotency");
            migrateModule(dataSource, "classpath:db/migration_tokens", "flyway_schema_history_tokens");
        };
    }

    private static void migrateModule(DataSource dataSource, String location, String historyTable) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations(location)
                .table(historyTable)
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();
    }
}
