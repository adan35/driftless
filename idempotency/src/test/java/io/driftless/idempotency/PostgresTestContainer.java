package io.driftless.idempotency;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared real-Postgres container for the idempotency integration tests, wired into the context by
 * {@link ServiceConnection} so Spring Boot derives the datasource URL/credentials automatically.
 *
 * <p>Flyway runs <em>both</em> module histories against this container: the ledger's migrations (the
 * atomicity test posts a real ledger transaction) and this module's {@code idempotency_record} /
 * {@code outbox_event} schema. Each module owns an independent history starting at {@code V1}, so
 * they are applied as two separate Flyway timelines (distinct locations and distinct history
 * tables), wired here by a {@link FlywayMigrationStrategy} that runs before JPA validates. The tests
 * therefore exercise the real schema, constraints and {@code FOR UPDATE SKIP LOCKED} behaviour — not
 * an in-memory substitute.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestContainer {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));
    }

    /**
     * Apply both module histories. The auto-configured Flyway points at the ledger's default
     * location ({@code classpath:db/migration}); we run it, then run this module's own timeline from
     * {@code classpath:db/migration_idempotency} (a sibling location, so the ledger's recursive scan
     * never picks it up) into a separate history table. This mirrors how the future {@code app}
     * runtime composes per-module migrations and keeps each module's V1 distinct.
     */
    @Bean
    FlywayMigrationStrategy moduleMigrations(DataSource dataSource) {
        return ledgerFlyway -> {
            ledgerFlyway.migrate();
            Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration_idempotency")
                    .table("flyway_schema_history_idempotency")
                    // The ledger migrations already populated `public`; this independent timeline
                    // baselines its own history table over that non-empty schema rather than failing.
                    // baselineVersion=0 so this module's own V1 still runs (a default baseline of 1
                    // would mark V1 as already applied and skip it).
                    .baselineOnMigrate(true)
                    .baselineVersion("0")
                    .load()
                    .migrate();
        };
    }
}
