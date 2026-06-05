package io.driftless.auth;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Applies all four module Flyway histories the auth integration tests need against the shared
 * Testcontainers Postgres, each starting at {@code V1} with its own history table: the ledger's
 * default {@code db/migration} (the auto-configured Flyway), then the idempotency module's {@code
 * db/migration_idempotency} (guard + outbox), the tokens module's {@code db/migration_tokens} (token
 * gating), and this module's {@code db/migration_auth} (authorization + hold). Mirrors how the {@code
 * app} runtime composes per-module migrations.
 */
@TestConfiguration(proxyBeanMethods = false)
public class AuthMigrations {

    @Bean
    FlywayMigrationStrategy moduleMigrations(DataSource dataSource) {
        return ledgerFlyway -> {
            ledgerFlyway.migrate();
            migrateModule(dataSource, "classpath:db/migration_idempotency", "flyway_schema_history_idempotency");
            migrateModule(dataSource, "classpath:db/migration_tokens", "flyway_schema_history_tokens");
            migrateModule(dataSource, "classpath:db/migration_auth", "flyway_schema_history_auth");
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
