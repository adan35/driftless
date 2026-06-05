# Driftless

> **An open-source issuer-processor core that keeps money provably correct under failure.**
> An immutable double-entry ledger + a card-authorization saga, engineered so that for *any*
> sequence of `authorize / capture / reversal / fault`, the books **provably sum to zero**.

<p align="center">
  <em>Zero drift isn't asserted here — it's proven, continuously, and shown live on a dashboard.</em>
</p>

---

## Why Driftless exists

When you tap a card, a chain of systems must agree, to the cent, on what just happened. The hard
part isn't the happy path — it's **failure**: a network call that times out *after* the other side
already acted, a process that crashes between "place a hold" and "record the charge", a retry that
risks charging twice. Each can leave the books **drifted** — the ledger says one thing, reality says
another — and in payments, drift is real money lost or conjured. The industry has expensive, public
examples: phantom holds that never release, captured-but-unconfirmed charges, end-of-day totals that
don't reconcile.

Driftless is a focused, fully-tested reference implementation that solves this **correctly** and,
crucially, **proves it**. Its signature property is *provable zero drift*: a continuous
reconciliation job and a property-based invariant test demonstrate that across random storms of
operations and injected faults, the sum of all journal entries is exactly zero.

## The three invariants (the spine of the system)

Every line of money-moving code respects these — they are treated as **correctness, not style**:

1. **Balance** — every journal write is balanced; `SUM(entries) == 0` per currency, always.
   Unbalanced posts are rejected and write nothing.
2. **Immutability** — entries are append-only. Corrections are **new compensating entries**, never
   edits or deletes (enforced in code *and* by a database trigger).
3. **Idempotency** — every mutating operation is safe to retry: a replay returns the original
   result, never a double-effect.

The **non-negotiable gate**: a property-based test (jqwik) generates random sequences of
`auth / capture / reversal / fault` and asserts, after each settles, that `∑ journal entries == 0`,
no operation double-applied, and no hold dangles. It runs in CI. Green that gate, and the dashboard
*proves* zero drift instead of asserting it. That is the whole pitch.

## What makes it different

| Most demos | Driftless |
|------------|-----------|
| Store a `balance` column and mutate it | Balance is **derived by summation** over immutable entries — it cannot silently drift |
| "Handle errors" with try/catch | A **saga** with a **bounded-timeout compensating reversal** + durable recovery — a stuck partner call can never leave a phantom hold |
| Assert idempotency in prose | An **idempotency guard** recording the key in the *same DB transaction* as the write, plus a transactional **outbox** for exactly-once events |
| Claim correctness | **Proves** it: a property test over random `auth/capture/reverse/fault` asserts `∑ == 0`, wired into CI |
| A monolith pretending to be distributed | A genuinely **separate** partner service over a real network boundary, with a fault-injection control plane |

## Architecture at a glance

A **modular monolith** (`app`) — one runnable Spring Boot process of independently-compiled,
strictly-inward-depending Maven modules — plus exactly one genuinely separate service, the
`partner-simulator`, reached only over HTTP so there's a real boundary to fail against.

```
   Cardholder taps                                   Money stays correct under EVERY failure
         │
         ▼
   ┌──────────────────┐   rules   ┌────────────┐                 ┌──────────────────────┐
   │  Auth Saga (03)  │─────────▶│ Rule Engine │   bounded-      │ Partner Simulator    │
   │ authorize/capture│  <1ms p99 │   (05)      │   timeout call  │ (04, separate svc)   │
   │ reverse + compen-│           └────────────┘ ───────────────▶│ real network hop,    │
   │ sating reversal  │                                          │ injectable faults    │
   └────────┬─────────┘  every step: balanced post + idempotency + outbox event
            ▼
   ┌──────────────────┐        ┌──────────────────────┐      ┌────────────────────┐
   │ Ledger (01)      │◀───────│ Reconciliation (07)  │─────▶│ Observability (08) │
   │ append-only      │  proves│ continuous proof +   │ feeds│ "zero drift"       │
   │ double-entry     │  ∑ == 0│ fault harness (GATE) │      │ Grafana dashboard  │
   └──────────────────┘        └──────────────────────┘      └────────────────────┘
```

| Module | Responsibility |
|--------|----------------|
| `common` | Kernel: `Money` (integer minor units), typed ids, injected `Clock`, errors |
| `ledger` (01) | Immutable double-entry journal; frozen `Ledger` api + `HoldView` spi; balance by summation |
| `idempotency` (02) | At-most-once guard + transactional outbox/relay for exactly-once events |
| `rules` (05) | Hot-path limits/velocity/MCC decisions; no I/O, p99 single-digit ms |
| `tokens` (06) | Token lifecycle state machine with outbox status propagation |
| `auth` (03) | The authorization saga: available-vs-posted, bounded-timeout compensating reversal |
| `recon` (07) | Continuous balance-proof job, load generator, fault harness, **the property gate** |
| `observability` (08) | Micrometer/Prometheus metrics + provisioned Grafana "zero drift" dashboard |
| `app` | The runnable monolith wiring every module; REST + `/actuator/prometheus` |
| `partner-simulator` (04) | Separate service with a fault-injection control plane |

Full deep-dives — *why each module exists, its business logic, and a worked example* — are in
[`.docs/`](./.docs/). Start with the [overview](./.docs/01-overview.md),
[architecture](./.docs/02-architecture.md), the
[double-entry walk-through](./.docs/03-double-entry-ledger.md), and the
[invariants](./.docs/04-invariants.md).

## The lifecycle of an authorization

```
POST /authorizations { account, amount, mcc, merchantId, tokenId? }   (Idempotency-Key)
   │
   ├─▶ evaluate rules ──DECLINE──▶ DECLINED            (no hold, no money moved)
   │       │ APPROVE
   │       ▼
   ├─▶ place HOLD       available -= amount  (posted unchanged)   ── committed before the call ──┐
   │       │
   │       ▼
   ├─▶ call partner.authorize OVER HTTP under a HARD bounded timeout
   │       │ approve            │ timeout / 5xx / no-response
   │       ▼                    ▼
   │   AUTHORIZED         COMPENSATING ── release hold, retry partner-reverse to completion ──▶ REVERSED
   │       │
   │       ├── capture ─▶ post settlement (posted moves, hold released) ─▶ CAPTURED
   │       └── reverse ─▶ compensating reversal ─▶ REVERSED
   │
   └─▶ every step: a balanced ledger post + an outbox event, committed in ONE transaction
```

The crux is the **bounded-timeout compensating reversal**: if the partner leg stalls, the saga
releases the hold immediately and durably retries the partner reverse until it's confirmed — so a
*late* partner success is absorbed and the books never drift.

## Quickstart — see provable zero drift in minutes

> Requires Docker (with Compose v2) only — no local JDK/Maven needed. The images build the boot jars
> inside multi-stage Dockerfiles.

```bash
git clone https://github.com/adan35/driftless.git
cd driftless
docker compose up --build    # builds the images, then starts the whole stack and migrates the DB
```

This brings up five containers — `postgres`, the modular-monolith `app`, the **separate**
`partner-simulator`, `prometheus`, and `grafana`. Flyway migrates every module's schema on `app`
startup, and the app reaches the partner-simulator **by service name over the network** (a real
network hop to a genuinely separate container, never an in-process call).

| Service | URL | Notes |
|---------|-----|-------|
| Grafana | http://localhost:3000 → **Zero Drift** | anonymous, read-only viewer (no login) |
| App REST + metrics | http://localhost:8080 | `/reconciliation/latest`, `/actuator/prometheus`, `/actuator/health` |
| Partner-simulator | http://localhost:8081 | separate service; fault control plane at `/control/faults` |
| Prometheus | http://localhost:9090 | scrapes `app` + `partner-simulator` |
| Postgres | localhost:5432 | user/pass/db all `driftless` |

Then, against the running stack:

1. Open the **Zero Drift** dashboard → http://localhost:3000 → *Zero Drift*.
2. Run the fault demo (Docker-only): `./scripts/demo.sh 60 8 MIXED`. It seeds funded accounts,
   drives a realistic authorize/capture/reverse load mix through the live saga, injects partner
   faults (timeout + fail-before-response) into the *separate* partner-simulator over its control
   plane, settles the recovery sweep, and reconciles.
3. Watch the dashboard: the **compensating-reversal** count rises while **`drift_amount` stays at
   `$0`**. That's the whole story on one screen.

Check it yourself:

```bash
curl -s localhost:8080/reconciliation/latest         # -> passed: true, totalDriftMinor: 0
curl -s localhost:8080/actuator/prometheus \
  | grep -E 'driftless_recon_drift_amount|driftless_auth_compensating_reversals_total'
#   driftless_auth_compensating_reversals_total{application="driftless"}  20.0   (rose under faults)
#   driftless_recon_drift_amount_minor_units{application="driftless"}      0.0   (stayed at zero)
```

Tear down with `docker compose down` (keep the database volume) or `docker compose down -v` (remove
it).

> **Optional convenience:** if you have GNU `make` installed, the same steps are wrapped as
> `make demo`, `make down` (keep the volume) and `make clean` (`docker compose down -v`). `make` is
> not a Docker prerequisite — the commands above need only Docker with Compose v2.

Kubernetes manifests are an **optional stretch** under [`k8s/`](./k8s/) — not the MVP path.

## Explore the API

The modular monolith exposes a documented, idempotent REST surface — browsable as OpenAPI:

- **Swagger UI:** http://localhost:8080/swagger-ui · **spec:** `GET /openapi.yaml`
  (also committed at [`deploy/openapi/openapi.yaml`](./deploy/openapi/openapi.yaml)).
- **Move money through it:** `POST /accounts` → `POST /accounts/{id}/funding` (a balanced load) →
  `POST /authorizations` → `…/capture` or `…/reverse`; read `GET /accounts/{id}/balance` (posted +
  available) and `GET /accounts/{id}/statement?after=&limit=` (keyset-paginated journal).
- Every mutating call takes an `Idempotency-Key`; errors are RFC-7807 with a stable `code`.

Operationally, drift is an **alarm that fires** (`deploy/prometheus/alerts.yml`), not just a panel —
see the [operations runbook](./.docs/06-runbook.md).

## Build & test locally

```bash
./mvnw verify     # full reactor: Spotless + JaCoCo + ArchUnit + enforcer + the zero-drift gate
```

Integration tests use **Testcontainers** (real Postgres, real triggers/constraints), so a running
Docker daemon is required. The CI gate (`recon/.../LedgerInvariantPropertyTest`) runs the property
test — now **sequential, concurrent, and multi-currency** — on every build. CI also enforces module
boundaries (ArchUnit), the build contract (maven-enforcer), and supply-chain provenance (CycloneDX
SBOM + Trivy image scan + Dependabot).

- **Stack:** Java 21, Spring Boot 4, Maven multi-module, PostgreSQL + Flyway, Micrometer/Prometheus,
  Grafana, Docker Compose.
- **Money** is integer **minor units** — no floating point ever touches a balance.
- **Time** is an injected `Clock` — never `Instant.now()` in business logic.

## How it proves zero drift (not just claims it)

1. **By construction** — every transaction is balanced, so the global signed sum starts and stays at
   zero; there is no stored balance to drift from history.
2. **Continuously** — the reconciliation job sums the whole ledger on a schedule and on demand,
   detects any drift (proven by a test that injects an unbalanced row via raw SQL and watches the job
   catch it), and never auto-edits the books.
3. **Under fault** — the harness injects latency/timeout/fail-before-response/duplicate/late-response
   into the real partner leg while load runs, and reconciliation still reports zero drift — the
   system self-heals via compensation + idempotency.
4. **In CI** — the property test fails the build if any random sequence ever drifts, double-applies,
   or leaves a dangling hold.

## Repository layout

```
common/ ledger/ idempotency/ rules/ tokens/ auth/ recon/ observability/ app/   modular monolith
partner-simulator/                                                              separate service
docker-compose.yml  Makefile  scripts/demo.sh                                   one-command bring-up + demo
app/Dockerfile  partner-simulator/Dockerfile                                    multi-stage build -> slim JRE
deploy/           Prometheus config + Grafana dashboards-as-code
k8s/              optional Kubernetes manifests (stretch beyond the MVP)
.docs/            published documentation (overview, architecture, per-module deep-dives)
.claude/          the engineering specs, delivery roles, and per-spec test/review artifacts
docs/             ADRs + frozen contract (local)
```

## License & status

Licensed under the **[Apache License 2.0](./LICENSE)**. A reference implementation built
spec-by-spec (see [`.claude/specs/`](./.claude/specs/)). The authorization saga, ledger,
idempotency/outbox, rules, tokens, reconciliation gate, observability, and packaging are implemented
and green under the property gate.

Contributions welcome — see [`CONTRIBUTING.md`](./CONTRIBUTING.md),
[`CODE_OF_CONDUCT.md`](./CODE_OF_CONDUCT.md), and the [security policy](./SECURITY.md).

---

<p align="center"><em>Driftless — because in payments, "probably balanced" isn't good enough.</em></p>
