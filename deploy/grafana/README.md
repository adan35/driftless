# Grafana provisioning — Driftless (Spec 08)

Everything here is **as-code** and auto-loaded; no manual clicking is required.

```
deploy/grafana/
  provisioning/
    datasources/prometheus.yml   # the Prometheus datasource (http://prometheus:9090)
    dashboards/dashboards.yml    # file provider → loads /var/lib/grafana/dashboards/*.json
  dashboards/
    zero-drift.json              # FLAGSHIP read-only "Zero Drift" dashboard
    system-health.json           # throughput, rule-engine p99, saga outcomes, error rates
```

## Anonymous read-only access (the public "zero drift" view)

The flagship dashboard is meant to be linkable from the README without exposing edit rights. Spec 09's
compose wires the Grafana container; configure it for anonymous, read-only viewing with these env vars
(the dashboards themselves are already `editable: false` / `allowUiUpdates: false`):

```yaml
environment:
  GF_AUTH_ANONYMOUS_ENABLED: "true"
  GF_AUTH_ANONYMOUS_ORG_ROLE: "Viewer"
  GF_AUTH_ANONYMOUS_ORG_NAME: "Main Org."
  GF_AUTH_DISABLE_LOGIN_FORM: "false"
  GF_USERS_DEFAULT_THEME: "light"
  # Land anonymous visitors straight on the Zero Drift dashboard:
  GF_DASHBOARDS_DEFAULT_HOME_DASHBOARD_PATH: "/var/lib/grafana/dashboards/zero-drift.json"
volumes:
  - ./deploy/grafana/provisioning:/etc/grafana/provisioning
  - ./deploy/grafana/dashboards:/var/lib/grafana/dashboards
```

`Viewer` is read-only: anonymous visitors can see the dashboard and its live `driftless_recon_drift_amount`
panel ($0 under normal operation) but cannot edit or administer anything.

## What the Zero Drift dashboard proves

- **Headline stat** `max(driftless_recon_drift_amount)` — green "$0 — ZERO DRIFT", turns red the instant
  reconciliation measures any drift.
- **Reconciliation pass/fail** timeline (`driftless_recon_run_passed`).
- **Compensating reversals** over time (`rate(driftless_auth_compensating_reversals_total[5m])`) — rises
  under Spec 07 fault injection **while drift stays 0**, telling the whole story.
- **Outbox health** (`driftless_outbox_pending_depth`, `driftless_recon_stuck_outbox_count`) and the
  independent **ledger signed-sum witness** (`driftless_ledger_signed_sum_abs_minor`, also 0).
