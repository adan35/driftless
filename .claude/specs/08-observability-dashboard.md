# Spec 08 — Observability & Dashboard

| Field | Value |
|-------|-------|
| **Owns** | Prometheus metrics, Grafana, the public read-only "zero drift" dashboard |
| **CV story** | Splunk / Grafana / Dynatrace |
| **Depends on** | Spec 07 (reconciliation results), all flow modules (metrics sources) |
| **Blocks** | Spec 09 (compose wires up Prometheus/Grafana) |
| **Parallelizable** | No — runs after 07 |

## North star

The proof of zero drift has to be **visible**. This spec instruments the system with Prometheus
metrics and ships a Grafana dashboard whose headline panel is a public, read-only **"zero drift"**
view — the live evidence that backs the README's claim.

## The three invariants (how they apply here)

Observability moves no money. Its job is to make the invariants **legible**: surface the
reconciliation result (balance invariant), the outbox health (idempotency/exactly-once), and the
saga outcomes (compensation working). If drift ever occurs, this dashboard is where it shows red.

## Goal

End-to-end observability: Micrometer/Prometheus metrics across modules, a Grafana instance
provisioned with dashboards as code, and a flagship read-only "zero drift" dashboard.

## Scope / Deliverables

1. **Metrics instrumentation** (Micrometer → Prometheus, `/actuator/prometheus`):
   - **Ledger:** postings/sec, rejected-unbalanced count, entry count.
   - **Reconciliation (Spec 07):** `drift_amount` (gauge; should be 0), last-run timestamp,
     checks pass/fail, stuck-outbox count.
   - **Auth saga:** authorize/capture/reverse counts, decline rate, **compensating-reversal count**,
     partner-call latency, timeout count, saga state distribution.
   - **Rule engine (Spec 05):** evaluation latency histogram (so the **p99 single-digit-ms** claim
     is visible), decisions by reason code.
   - **Outbox (Spec 02):** pending depth, publish latency, relay errors.
   - **Tokens (Spec 06):** status-change counts by transition.
   - Standard JVM/HTTP metrics.

2. **Prometheus** config scraping `app` and `partner-simulator`.

3. **Grafana**, provisioned as code (datasource + dashboards in version control, auto-loaded):
   - **"Zero Drift" dashboard (flagship, read-only):** a big single-stat showing current
     `drift_amount == $0`, the reconciliation pass/fail timeline, compensating-reversals over time,
     and outbox health. Designed to be screenshot-able / shareable as the headline proof.
   - **System health dashboard:** throughput, latencies (incl. rule-engine p99), saga outcomes,
     error rates.

4. **Read-only / public access** for the zero-drift dashboard (anonymous viewer or a published
   snapshot) so it can be linked from the README without exposing edit rights.

## Acceptance criteria (falsifiable)

- [ ] `/actuator/prometheus` exposes the metrics listed above with sensible names/labels.
- [ ] Prometheus scrapes both `app` and `partner-simulator` successfully.
- [ ] Grafana comes up **provisioned** (datasource + dashboards loaded from version-controlled
      config; no manual clicking required).
- [ ] The "Zero Drift" dashboard shows `drift_amount` as a headline stat reading **$0** under
      normal operation, and reflects the reconciliation pass/fail state.
- [ ] Running Spec 07's fault injection makes **compensating-reversal count** rise on the dashboard
      while `drift_amount` **stays at 0** — visually telling the whole story.
- [ ] The rule-engine latency panel shows the **p99** so the single-digit-ms claim is verifiable.
- [ ] The zero-drift dashboard is reachable read-only without admin credentials.

## Gate

QA agent confirms the dashboard accurately reflects reconciliation state (it must go red if Spec
07's deliberate-drift fixture is run). The dashboard is the public face of the gate's result.

## Out of scope

- Log aggregation stack (structured logs are fine; full ELK/Splunk is future work).
- Alerting/paging rules (a stretch; basic Grafana alert on `drift_amount > 0` is welcome but
  optional).
