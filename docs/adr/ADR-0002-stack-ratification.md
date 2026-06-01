# ADR-0002 — Stack ratification

- Status: Accepted
- Date: 2026-06-01
- Deciders: Driftless core team

## Context

Spec 00 must ratify the technology stack once, so every downstream spec builds on the same
foundation rather than re-litigating tooling. The system is a money-correctness core that needs
modern language ergonomics, a mature transactional database, deterministic schema evolution,
first-class testing of both invariants and integration, and a metrics story for the public
zero-drift dashboard.

## Decision

| Concern | Choice | Why |
|---|---|---|
| Language | **Java 21** | Records, sealed types, switch expressions and pattern matching make immutable value types (`Money`, ids, DTOs) and exhaustive domain logic concise and safe. LTS. |
| Framework | **Spring Boot 4.0.6** (Spring Web MVC) | Mature DI, transactions, web, and test support; the de-facto JVM standard for this class of service. |
| Build | **Maven multi-module** (wrapper committed) | Enforces module boundaries as separate compilation units; the committed wrapper makes the build reproducible without a local Maven install. |
| Boilerplate | **Lombok** | `@RequiredArgsConstructor`, `@Slf4j`, etc., remove ceremony; constructor injection stays the convention. |
| Persistence | **PostgreSQL** | Strong transactional guarantees and `SUM`-based balance queries; trigger/privilege support to enforce append-only at the DB. |
| Migrations | **Flyway** | Versioned, append-only schema evolution; never edit a shipped migration. |
| Testing | **JUnit 5 + Testcontainers (Postgres) + jqwik** | Unit tests beside code; integration tests against real Postgres; jqwik drives the property-based zero-drift invariant. |
| Observability | **Micrometer → Prometheus + Grafana** | Standard JVM metrics pipeline feeding the public read-only dashboard. |
| Packaging | **Docker Compose** | One-command bring-up of app + simulator + Postgres + Prometheus/Grafana. |

Money is stored as integer **minor units** with an explicit ISO-4217 currency (see ADR-0003). No
floating point ever touches a balance.

## Consequences

- Downstream specs assume this stack and do not re-decide it; deviations must amend this ADR.
- Postgres, Flyway, Micrometer/Prometheus, and Docker Compose are **targets**: their version
  coordinates are managed in the parent POM's dependency management as they are wired in by their
  owning specs (Persistence: Spec 01+; Observability: Spec 08; Packaging: Spec 09). At Spec 00 the
  build is compile + unit/integration test + quality gates only.
- The committed Maven wrapper (`mvnw`/`mvnw.cmd`) is the single supported entry point for builds.
