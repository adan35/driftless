# Code Review — Spec 09 Packaging & Deploy

## GATE: FAIL

One Critical demo-endpoint-safety defect: `POST /demo/run` is **enabled by default** in a
normal/prod boot because of `matchIfMissing = true`. Everything else (separate partner container,
compose wiring, multi-stage non-root Dockerfiles, balanced seed post, k8s stretch) is sound.
Flip the gate default and the gate passes.

---

## Critical

### C1 — Demo endpoint is exposed by default in a normal/prod boot
`recon/src/main/java/io/driftless/recon/web/DemoController.java:26`
```java
@ConditionalOnProperty(name = "driftless.demo.enabled", havingValue = "true", matchIfMissing = true)
```
`matchIfMissing = true` means that when the property is **absent** (the default profile — a normal /
production boot, which sets `driftless.demo.enabled` nowhere: confirmed no default
`application.properties` / `application-prod` defines it), the `DemoController` bean is still
registered and `POST /demo/run` is **live**. The class Javadoc itself promises the opposite
("gated behind `driftless.demo.enabled` ... never an exposed surface"), and the spec's #1 requirement
is "DISABLED by default and only enabled in the docker profile." An exposed `/demo/run` is an
unauthenticated, unbounded money-moving/load-driving surface (it opens accounts, posts funding,
drives the saga) on any default boot. This is the exact failure the review brief flags as Critical.

This is also why the ITs pass without ever setting the flag — the default-on behavior masks the bug.

**Fix:** set `matchIfMissing = false`:
```java
@ConditionalOnProperty(name = "driftless.demo.enabled", havingValue = "true", matchIfMissing = false)
```
The docker profile already sets `driftless.demo.enabled=true`
(`app/src/main/resources/application-docker.properties:26`), so the compose/k8s demo keeps working
while a normal boot has no demo surface. Add a negative test (see W3) so this can't regress.

---

## Warning

### W1 — Container healthchecks depend on `wget`, which is not in the Temurin JRE image
`docker-compose.yml:35` (partner-simulator) and `docker-compose.yml:62` (app) use
`wget -q -O /dev/null http://localhost:.../actuator/health`. The runtime stage is
`eclipse-temurin:21-jre` (`app/Dockerfile:48`, `partner-simulator/Dockerfile:38`), an Ubuntu base
that does **not** ship `wget` by default. If `wget` is absent the healthcheck always reports
unhealthy → `app`'s `depends_on: partner-simulator: condition: service_healthy`
(`docker-compose.yml:49-50`) never resolves → the one-command bring-up (the whole gate) hangs.
**Fix:** verify `wget` is present in the image; if not, either install it in the runtime stage
(`RUN apt-get update && apt-get install -y --no-install-recommends wget && rm -rf /var/lib/apt/lists/*`),
switch the healthcheck to a Spring Boot actuator-based check, or use a Java/`/dev/tcp` probe that
needs no extra binary. (`curl` is also typically absent — don't swap one for the other blindly.)

### W2 — `/demo/run` has no upper bound on `count` / `concurrency` / `accounts` / `fundingMinor`
`recon/src/main/java/io/driftless/recon/internal/demo/DemoRunner.java:95-98` validates only
`>= 1`; `DemoController.run` (`DemoController.java:34`) takes the body without `@Valid` and there is
no maximum. A caller can request `count`/`concurrency` in the millions, spawning unbounded worker
threads and ledger work — resource exhaustion / DoS. Low blast radius once C1 is fixed (gated), but
defense-in-depth is cheap. **Fix:** add upper bounds in `DemoRunner.run` (e.g.
`require(ops <= 10_000, ...)`, `require(workers <= 64, ...)`, `require(accountCount <= 1_000, ...)`)
or bound them via `@Min/@Max` on `DemoRunRequest` with `@Valid` on the controller param.

### W3 — No test proves the endpoint is OFF when the flag is false/absent
`recon/src/test/java/io/driftless/recon/DemoControllerIT.java` only exercises the happy path with the
controller active. Because of C1 the controller is active without anyone setting the flag, so there is
no coverage asserting the gate actually gates. **Fix:** add a `@SpringBootTest` (or slice) with
`driftless.demo.enabled` unset/`false` asserting `POST /demo/run` returns 404, and update the existing
ITs to explicitly set `driftless.demo.enabled=true` (via `@DynamicPropertySource`/`@TestPropertySource`)
so they remain green after C1's fix.

---

## Info

### I1 — `/demo/run` does not enforce an `Idempotency-Key`
`DemoController.java:33-34`. CLAUDE.md says every mutating endpoint reads `Idempotency-Key`. The demo
is mutating (opens accounts, posts funding, drives the saga). It is safe in practice — the funding
post is keyed (`demo-fund:<cardholder>`, `DemoRunner.java:153`) and `openAccount`/`post` are
idempotent (`LedgerService.java:73-85`, `:90-95`), and each run uses fresh random cardholder ids —
so a replay only appends more correct history. Acceptable for a seed-only demo, but worth a one-line
comment noting the deliberate convention exception.

### I2 — Redundant partner URL configuration
`docker-compose.yml:57-58` sets `AUTH_PARTNER_BASE_URL` / `DRIFTLESS_RECON_PARTNER_CONTROL_BASE_URL`
*and* `application-docker.properties:8-9` hardcodes the same `http://partner-simulator:8081`. Both
agree, so harmless, but two sources of truth can drift. Prefer the env var (compose-owned topology)
or the properties file, not both.

### I3 — k8s stretch runs the `docker` profile, so `/demo/run` is enabled there too
`k8s/30-app.yaml` sets `SPRING_PROFILES_ACTIVE: docker`, which enables the demo endpoint. Consistent
with the "local demo" framing and clearly labeled a stretch, but if k8s is ever pointed at anything
real, the demo surface rides along. Note it in `k8s/README.md`.

---

## Verified good (no action)

- **Partner is a real separate container.** `partner-simulator` is its own compose service
  (`docker-compose.yml:27-39`) and its own image/Dockerfile; `app` reaches it only by service name
  over HTTP (`AUTH_PARTNER_BASE_URL=http://partner-simulator:8081`, `docker-compose.yml:57`). Demo
  fault injection goes through `FaultInjectionHarness` to the simulator's `/control/faults` over HTTP
  (`DemoRunner.java:113-116`). No in-process partner shortcut, no stub profile in the running app.
- **Balanced seed post.** `DemoRunner.seedFundedAccounts` posts equal DEBIT (cardholder) / CREDIT
  (settlement) (`DemoRunner.java:152-158`) → nets to zero; goes through the ledger's normal `post()`.
  No `UPDATE`/`DELETE` on `journal_entry`, no saga/idempotency bypass. Re-runnable without dangling
  state (idempotent open/post; fresh cardholders each run). Recovery sweep is drained with a bounded
  budget (`DemoRunner.java:64,166-178`) before reconciling.
- **Compose correctness.** postgres has a named volume + `pg_isready` healthcheck
  (`docker-compose.yml:17-25`); `app depends_on` postgres *healthy* AND partner-simulator *healthy*
  (`:46-50`); `ddl-auto=validate` + `flyway.enabled=true` (app `application.properties`) so Flyway
  migrates on startup; prometheus mounts `deploy/prometheus/prometheus.yml` read-only and scrapes both
  targets (`:73-74`, `deploy/prometheus/prometheus.yml`); grafana mounts provisioning + dashboards
  read-only with anonymous **Viewer** (read-only, not Admin) role (`:85-93`). Ports 8080/8081/9090/3000
  consistent across compose, prometheus, README.
- **Dockerfiles.** Multi-stage (JDK build → JRE runtime), `-DskipTests` in-image (no Testcontainers in
  build), non-root `driftless` user (`app/Dockerfile:50-52`, `partner-simulator/Dockerfile:40-42`),
  cache-friendly pom-then-src layering with `.m2` cache mount, `MaxRAMPercentage` set. `.dockerignore`
  excludes `**/target`, `.git`, `.claude`, `docs`, `.docs`, `deploy`, `k8s`. No secrets baked in.
- **Security.** Postgres creds are obvious local-dev defaults (`driftless/driftless`) supplied via env
  (`docker-compose.yml:13-16,53-55`); k8s uses a `Secret` with a clear "use managed DB" note
  (`k8s/10-postgres.yaml`). Grafana viewer is read-only. No PAN anywhere.
- **Regression.** Frozen `ledger.api`/`spi` untouched; only change to existing source is
  `recon/pom.xml` adding the `io.driftless.recon.internal.demo` package to the JaCoCo include list —
  no business-logic change. New code is additive (recon demo package + controller/DTO).
