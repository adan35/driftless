# Spec 09 — Packaging & Deploy

| Field | Value |
|-------|-------|
| **Owns** | `docker compose up` one-command bring-up; k8s manifests as an optional stretch section |
| **CV story** | Docker, basic k8s |
| **Depends on** | All modules (it packages them); Spec 08 (Prometheus/Grafana services) |
| **Parallelizable** | No — final step |

## North star

The whole system — modular monolith, the separate partner simulator, Postgres, Prometheus, and
Grafana — must come up with **one command** so a reviewer can see provable zero drift in minutes.
Kubernetes is a clearly-marked later stretch, not the MVP path.

## The three invariants (how they apply here)

Packaging moves no money, but it must not undermine the invariants: the database is durable and
migrated (Flyway) on startup, the partner simulator is a **genuinely separate container** (real
network boundary), and nothing in the compose setup short-circuits the saga's external leg into an
in-process call.

## Goal

A reproducible, one-command local bring-up via Docker Compose, with a short optional section for
Kubernetes manifests as a stretch goal.

## Scope / Deliverables

1. **Dockerfiles:**
   - `app` (modular monolith boot jar).
   - `partner-simulator` (its own image — separate service, separate container).
   - Multi-stage builds (Maven build stage → slim JRE runtime); small, cache-friendly images.

2. **`docker-compose.yml`** bringing up the full stack:
   - `postgres` (with a volume; healthcheck).
   - `app` (depends on healthy `postgres`; runs Flyway migrations on startup; exposes the REST API
     and `/actuator/prometheus`).
   - `partner-simulator` (separate service on its own port; `app` reaches it by service name over
     the compose network — a **real network hop**).
   - `prometheus` (scrapes `app` + `partner-simulator`).
   - `grafana` (provisioned datasource + dashboards from Spec 08; zero-drift dashboard reachable).
   - Sensible env-var configuration, healthchecks, and `depends_on` ordering.

3. **One-command bring-up:** `docker compose up` (optionally a `make demo` / script) starts
   everything; a documented sequence then runs the load generator + fault injection (Spec 07) so a
   reviewer can watch the zero-drift dashboard hold at $0 under faults.

4. **README quickstart** wiring it together: clone → `docker compose up` → open the zero-drift
   dashboard → run the fault demo → see drift stay at $0. This is the payoff paragraph that links
   to the proof.

5. **Kubernetes (OPTIONAL STRETCH — clearly marked):** a `k8s/` directory with Deployments +
   Services for `app`, `partner-simulator`, Postgres (or a note to use a managed DB), and the
   monitoring stack, plus a short README note. Explicitly labeled as a stretch beyond MVP.

## Acceptance criteria (falsifiable)

- [ ] `docker compose up` from a clean clone brings up postgres + app + partner-simulator +
      prometheus + grafana with no manual steps.
- [ ] Flyway migrations run automatically on `app` startup against the compose Postgres.
- [ ] `app` reaches `partner-simulator` over the compose network by service name (verified: it is a
      separate container, not an in-process call).
- [ ] Prometheus shows both targets up; Grafana loads the provisioned zero-drift dashboard.
- [ ] The documented demo sequence (load + fault injection) runs against the composed stack and the
      zero-drift dashboard stays at **$0**.
- [ ] Images build via multi-stage Dockerfiles and are reasonably sized.
- [ ] The README quickstart is accurate end-to-end (someone following it literally succeeds).
- [ ] (Stretch) `k8s/` manifests exist and are marked optional; not required for the gate.

## Gate

Final QA: a reviewer following only the README quickstart can reproduce provable zero drift on
their own machine. That reproducibility is the close of the whole pitch.

## Out of scope

- Production hardening (secrets management, TLS, autoscaling) beyond notes.
- A hosted/public deployment — local reproducibility is the MVP target; k8s is the stretch.
