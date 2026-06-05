# Module: Packaging & Deploy (Spec 09)

> The whole system — modular monolith, the separate partner-simulator, Postgres, Prometheus, and
> Grafana — comes up with **one command** so a reviewer can see provable zero drift in minutes.

## Why this exists

A correctness proof nobody can reproduce is a correctness story, not a guarantee. Spec 09 makes the
entire system **trivially reproducible**: `docker compose up --build` from a clean clone brings up
the full stack, migrates the database, wires the dashboards, and a single `make demo` drives the
live system through a storm of partner faults while the zero-drift dashboard holds at `$0`. That
reproducibility is the close of the whole pitch.

Packaging moves no money, but it must not undermine the invariants: the database is durable and
**Flyway-migrated on startup**, the partner-simulator is a **genuinely separate container** (a real
network boundary), and nothing in the compose setup short-circuits the saga's external leg into an
in-process call.

## The stack (`docker-compose.yml`)

```
                         ┌─────────────────────────────────────────────┐
   localhost:3000 ──────▶│ grafana  (anonymous read-only "Zero Drift")  │
                         └───────────────┬─────────────────────────────┘
   localhost:9090 ──────▶ prometheus ────┤ scrapes /actuator/prometheus
                            │            │
              ┌─────────────┴───┐   ┌────┴───────────────────┐
   :8080 ────▶│ app (monolith)  │──▶│ partner-simulator      │◀── :8081
              │ REST + metrics  │HTTP│ (separate container —  │
              │ Flyway on boot  │   │  a real network hop)   │
              └────────┬────────┘   └────────────────────────┘
                       ▼
              ┌─────────────────┐
   :5432 ────▶│ postgres (vol + │
              │  healthcheck)   │
              └─────────────────┘
```

| Service | Role |
|---------|------|
| `postgres` | durable Postgres 16 (named volume, `pg_isready` healthcheck) |
| `app` | the modular monolith; depends on healthy `postgres` **and** `partner-simulator`; runs Flyway for every module's timeline on startup; exposes REST + `/actuator/prometheus` |
| `partner-simulator` | its **own image/container** on `:8081`; the app reaches it by service name (`AUTH_PARTNER_BASE_URL=http://partner-simulator:8081`) — a real network hop |
| `prometheus` | scrapes `app:8080` + `partner-simulator:8081` (mounts `deploy/prometheus/prometheus.yml`) |
| `grafana` | provisioned from `deploy/grafana/*`; anonymous read-only viewer; the **Zero Drift** dashboard |

## Images (multi-stage Dockerfiles)

Both `app/Dockerfile` and `partner-simulator/Dockerfile` are **multi-stage** and cache-friendly:

1. **Build stage** (`temurin:21-jdk`): copy the Maven wrapper + all module poms first and resolve
   dependencies into a cached layer, *then* copy sources and `mvn -pl <module> -am package
   -DskipTests`. (Testcontainers tests never run during an image build — they run in `./mvnw
   verify`.)
2. **Runtime stage** (`temurin:21-jre`): a slim image running only the boot jar as a **non-root**
   user.

A `.dockerignore` keeps the build context small (excludes `target/`, `.git`, `.claude`, `docs`, …).

## One-command bring-up + the demo

```bash
docker compose up --build      # postgres + app + partner-simulator + prometheus + grafana
make demo                      # or: ./scripts/demo.sh 60 8 MIXED
```

The demo drives the **real running system** via a seed-only, flag-gated app endpoint
(`POST /demo/run`, enabled only in the `docker` profile): it seeds funded accounts with **balanced**
ledger posts, drives a realistic `authorize/capture/reverse` load mix through the live saga (whose
partner leg is a genuine network hop to the partner-simulator), injects partner faults
(`timeout`, `fail-before-response`) through the simulator's control plane, settles the recovery
sweep, and reconciles. The endpoint only walks the normal flows — it **cannot** post an unbalanced
entry or otherwise create drift.

The payoff a reviewer sees:

```
operations : 60   approved/declined : 43/11   captures/reversals : 24/14   compensated : 6
RECONCILIATION : passed=True  totalDriftMinor=0
driftless_auth_compensating_reversals_total  20.0   (rose under faults)
driftless_recon_drift_amount_minor_units      0.0   (stayed at zero)
```

Compensating reversals rise while drift stays at `$0` — the whole Driftless story on one screen.

## Verified end-to-end

The stack was brought up against real containers and validated: all five services healthy, Flyway
applied every module's migrations, both Prometheus targets `up`, Grafana reachable with the
provisioned dashboard, the app reaching the partner-simulator by service name, and the fault demo
reporting `passed=true / totalDriftMinor=0`.

## Kubernetes (optional stretch — clearly marked)

`k8s/` contains Deployments + Services for `app`, `partner-simulator`, Postgres, and the monitoring
stack, with a short `k8s/README.md`. It is explicitly a **stretch beyond the MVP** — local
Docker-Compose reproducibility is the target.

## Out of scope

- Production hardening (secrets management, TLS, autoscaling) beyond notes.
- A hosted/public deployment — local reproducibility is the MVP.
