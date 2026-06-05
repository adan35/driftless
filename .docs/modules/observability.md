# Module: `observability` — Metrics & the "Zero Drift" Dashboard (Spec 08)

> The proof of zero drift has to be **visible.** This module instruments the system with Prometheus
> metrics and ships a Grafana dashboard whose headline panel is a public, read-only **"zero drift"**
> view — the live evidence that backs the README's claim.

## Why this module exists

A correctness property nobody can see is a correctness property nobody believes. Spec 07 *proves*
zero drift; this module makes that proof **legible and shareable**: a single big number on a
dashboard reading **$0**, a reconciliation pass/fail timeline, and — the storytelling panel —
compensating-reversals climbing while drift stays flat at zero. When a reviewer injects faults, they
watch the system absorb them in real time without ever drifting.

Observability moves no money. Its job is to make the three invariants visible: the reconciliation
result (Balance), the outbox health (Idempotency/exactly-once), and the saga outcomes (compensation
working). If drift ever occurs, this is where it shows red.

## What gets measured

Metrics are exposed on `app`'s `/actuator/prometheus` endpoint (Micrometer → Prometheus), with a
consistent `driftless_` prefix and meaningful labels:

| Source | Metrics |
|--------|---------|
| **Reconciliation** | `driftless_recon_drift_amount` (gauge — the headline; should read 0), last-run timestamp, checks pass/fail, stuck-outbox count |
| **Auth saga** | authorize/capture/reverse counts, decline rate, **compensating-reversal count**, partner-call latency, timeout count, saga-state distribution, dangling-partner-reverse + outstanding obligations |
| **Rule engine** | evaluation latency histogram (so the **p99 single-digit-ms** claim is verifiable), decisions by reason code |
| **Ledger** | postings/sec, rejected-unbalanced count, entry count |
| **Outbox** | pending depth, publish latency, relay errors |
| **Tokens** | status-change counts by transition |
| **Platform** | standard JVM / HTTP / connection-pool metrics |

Where possible, instrumentation lives in this module and is driven by **domain events** (the outbox)
and by **polling reconciliation results**, keeping the feature modules clean; in-path timing (rule
p99, partner latency) is measured at the source.

## The dashboards (provisioned as code)

Grafana is provisioned entirely from version-controlled files (datasource + dashboards), so it comes
up correct with **no manual clicking**:

1. **"Zero Drift" dashboard (flagship, read-only):**
   - a big single-stat: current `drift_amount` = **$0**;
   - the reconciliation **pass/fail timeline**;
   - **compensating-reversals over time**;
   - **outbox health** (pending depth / stuck count).
   Designed to be screenshot-able and shareable as the headline proof, reachable **read-only**
   without admin credentials.

2. **System health dashboard:** throughput, latencies (including the rule-engine **p99**), saga
   outcomes, error rates.

```
   ┌──────────────────────────  ZERO DRIFT  ──────────────────────────┐
   │                                                                   │
   │     drift_amount:   $0        reconciliation: ✓ PASS              │
   │                                                                   │
   │   compensating reversals  ▁▂▅▇█▅▂   (rising under fault inject)   │
   │   drift_amount over time   ────────────────────  (flat at 0)      │
   │   outbox pending depth     ▁▁▁▂▁▁▁                                │
   └───────────────────────────────────────────────────────────────────┘
```

## The story it tells

Running Spec 07's fault injection makes the **compensating-reversal count rise** on the dashboard
while `drift_amount` **stays at 0** — visually telling the whole Driftless story in one screen: the
system is absorbing real downstream failures and the books never move off zero. If someone ran the
deliberate-drift fixture, the headline stat would go **red** — which is exactly how you know the
dashboard is honest and not just painting green.

## Prometheus

A version-controlled Prometheus config scrapes both `app` and the separate `partner-simulator` (both
expose `/actuator/prometheus`), so the dashboard sees the whole system including the real network
leg.

## How this is wired to run

The metric endpoints and dashboards-as-code live here; **Spec 09's `docker-compose`** brings up
Prometheus and Grafana as containers, points Prometheus at the two scrape targets, and loads the
provisioned dashboards — so `docker compose up` yields a live zero-drift dashboard with no manual
steps.

## Out of scope

- Full log-aggregation stack (structured logs are present; ELK/Splunk is future work).
- Paging/alerting beyond an optional basic Grafana alert on `drift_amount > 0`.
