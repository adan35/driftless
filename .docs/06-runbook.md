# Operations Runbook

How to operate Driftless and respond when something goes wrong. The whole system is built so that
**drift is impossible to hide** — this runbook tells you what the alarms mean and what to do.

## At a glance

| Where | URL (compose) | What it shows |
|-------|---------------|---------------|
| Zero Drift dashboard | http://localhost:3000 → *Zero Drift* | the headline: `drift_amount = $0`, recon pass/fail, compensating-reversals, outbox health |
| Prometheus | http://localhost:9090 (`/alerts`) | metrics + firing alert rules |
| Alertmanager | http://localhost:9093 | routed alerts |
| App API + metrics | http://localhost:8080 | REST, `/actuator/health`, `/actuator/prometheus` |
| Reconciliation | `GET /reconciliation/latest`, `…/{id}/rca` | the proof + RCA detail |

Key metrics (all `driftless_*`): `recon_drift_amount_minor_units` (must be 0), `recon_run_passed`,
`recon_stuck_outbox_count`, `outbox_pending_depth`, `auth_compensating_reversals_total`,
`auth_dangling_partner_reverse`, `auth_outstanding_partner_obligations`,
`rules_evaluation_seconds` (p99).

## The golden rule

> **Never `UPDATE`/`DELETE` `journal_entry` to "fix" the books.** The table is append-only and a
> trigger will reject it anyway. Every correction is a **new compensating transaction**, decided
> deliberately after an RCA. Drift is surfaced, never silently patched.

---

## Alert: `LedgerDriftDetected` (critical)

`max(driftless_recon_drift_amount_minor_units) != 0` — the ledger no longer sums to zero. This should
be impossible in normal operation; treat it as a real incident.

1. **Confirm.** `GET /reconciliation/latest` → which check failed and the `totalDriftMinor`.
2. **Localize.** `GET /reconciliation/{id}/rca` → the offending accounts/transactions and a candidate
   cause. For `PER_TRANSACTION` failures it names the exact unbalanced transaction(s).
3. **Investigate root cause.** Inspect those transactions' entries (`GET /accounts/{id}/statement`).
   Drift implies an unbalanced write reached the journal — which the ledger's `post()` rejects — so
   look for a direct DB write that bypassed the API, a bad migration/fixture, or data tampering.
4. **Remediate deliberately.** Decide the correct compensating transaction and post it through the
   normal `Ledger.post` (balanced, idempotent). Re-run `POST /reconciliation/run` to confirm zero.
5. **Postmortem.** Capture how the unbalanced data entered and close that path.

## Alert: `ReconciliationFailing` (critical)

`min(driftless_recon_run_passed) == 0` — a reconciliation check failed (drift, hold, or outbox). Use
`GET /reconciliation/latest` to see *which* check; then follow the matching section below.

## Alert: `OutboxRelayStuck` / `OutboxBacklogGrowing` (warning)

PENDING outbox events are old (`recon_stuck_outbox_count > 0`) or the backlog is high.

1. Check the relay is running and the app is healthy (`/actuator/health`).
2. Inspect the oldest PENDING events. A single **poison event** can block the queue head — look for
   one event repeatedly failing to publish.
3. If a poison event is blocking, park/repair it (a dead-letter path is future work; for now,
   identify and fix the consumer/serialization issue). Backlog from a transient relay outage drains
   on its own once the relay recovers.

## Alert: `DanglingPartnerReverse` (warning)

`auth_dangling_partner_reverse > 0` — a compensation finalized without a confirmed partner reverse, so
a partner-side authorization may be live and uncancelled. Cross-check via the partner's
`GET /partner/state/{id}`; the recovery sweep re-drives outstanding obligations, so this should clear
— if it persists, the partner reverse endpoint may be failing.

## Alert: `OutstandingPartnerObligationsRising` (warning)

`auth_outstanding_partner_obligations > 50` sustained — many authorizations are stuck `COMPENSATING`
awaiting a partner reverse. Almost always means the **partner is degraded/unreachable**. The system
is *safe* (holds already released, local books balanced); obligations settle automatically once the
partner recovers and the sweep confirms each reverse. Check partner health and the network hop.

## Alert: `TargetDown` (critical)

`up == 0` — Prometheus can't scrape `app` or `partner-simulator`. Check the container
(`docker compose ps`, logs), the health endpoint, and the network.

---

## Routine operations

- **Run reconciliation on demand:** `POST /reconciliation/run`.
- **Read a statement:** `GET /accounts/{id}/statement?limit=100` then follow `nextCursor`.
- **Bring the stack up / demo:** `docker compose up --build`; `./scripts/demo.sh 60 8 MIXED` (or
  `make demo`). See the [README quickstart](../README.md).
- **Browse the API:** `/swagger-ui` (served from `app`).

## Known operational limits (deliberate, documented)

- No authentication on the REST surface in the demo profile; the `/demo/run` endpoint is disabled by
  default. The in-memory velocity store is per-instance (move to Redis before horizontal scale).
  See [`SECURITY.md`](../SECURITY.md) and the [holistic assessment](../.claude/reviews/holistic-world-class-assessment.md).
