# Spec 09 — Packaging & Deploy — QA verification

**Role:** Senior QA (independent verification). **Date of run:** real run captured below.
**Environment:** Linux, `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-arm64` (Temurin/OpenJDK 21.0.11),
Docker 29.1.3, Docker Compose v2.32.4, `postgres:16-alpine` pre-pulled. `make` **not installed**
(used `scripts/demo.sh` directly, as `make demo` wraps it).

**Scope verified:** `./mvnw -B -pl recon,app -am verify` (BUILD SUCCESS, property gate running),
the new demo code (`DemoFaultPlan`, `DemoRunner`, `DemoController`) and its tests, the
zero-drift-under-fault invariant on the demo path, a full `docker compose` bring-up of the stack,
the headline demo (load + fault injection → reconcile → zero drift), and the README quickstart.

## Headline result

- **BUILD SUCCESS** — full reactor (recon,app `-am` pulls in every module). Zero-drift property
  gate (`recon/.../LedgerInvariantPropertyTest`) **RAN, not skipped** (2 properties).
- **Stack came up:** postgres + app + partner-simulator + prometheus + grafana, all up/healthy.
- **Demo proved zero drift on the live composed stack** under injected faults:
  `passed=true, totalDriftMinor=0`, `compensating_reversals_total` nonzero. DB `SUM(journal)=0`.
- **The demo CANNOT create drift** — funding is a balanced ledger post; only ordinary
  authorize/capture/reverse/fault paths are exercised; signed sum stays 0.
- **2 defects found** (1 High: broken Grafana panels; 1 Medium: demo endpoint enabled by default)
  plus README/`make` accuracy gaps. None create drift.

## Pass/fail counts (from the real run)

- Build: **BUILD SUCCESS**, 10 reactor modules SUCCESS.
- Demo unit/IT: `DemoFaultPlanTest` 8/8, `DemoRunnerIT` 2/2, `DemoControllerIT` 2/2 — all pass.
- Property gate: `LedgerInvariantPropertyTest` 2/2 pass (ran ~39s, not skipped).
- Live stack: 5/5 containers healthy; Prometheus 2/2 app targets `health=up`; Grafana healthy +
  Zero Drift dashboard provisioned; demo run zero drift.

## Test cases

| sr_no | test case (Given/When/Then) | expected | actual | reason_of_failure | db_config |
|-------|------------------------------|----------|--------|-------------------|-----------|
| 1 | **Given** the repo on JDK 21 **When** `./mvnw -B -pl recon,app -am verify` **Then** BUILD SUCCESS with all gates | BUILD SUCCESS, 10 modules SUCCESS | **PASS** — BUILD SUCCESS, total 04:46; all 10 modules SUCCESS | — | Testcontainers Postgres (per-module) |
| 2 | **Given** the recon module **When** verify runs **Then** the jqwik zero-drift property gate runs (not skipped) | `LedgerInvariantPropertyTest` executes | **PASS** — Tests run: 2, Failures 0, Skipped 0, 39.37s | — | Testcontainers Postgres |
| 3 | **Given** the demo fault-mix mapping **When** `DemoFaultPlanTest` runs **Then** all mixes map deterministically + unknown rejected | 8/8 pass | **PASS** — Tests run: 8, Failures 0 | — | none (pure) |
| 4 | **Given** the saga + Testcontainers ledger **When** `DemoRunnerIT` seeds→loads→reconciles (no faults) **Then** zero drift, signed sum 0 | 2/2 pass, `globalSignedSum()==0` | **PASS** — Tests run: 2, Failures 0, 24.41s | — | Testcontainers Postgres |
| 5 | **Given** TIMEOUT fault injected over control plane **When** `DemoRunnerIT` runs **Then** compensation fires AND reconciliation passes, signed sum 0 | compensated>0, drift 0 | **PASS** (part of test 4 set) — control plane hit, compensated>0, drift 0 | — | Testcontainers Postgres |
| 6 | **Given** the HTTP demo endpoint **When** `DemoControllerIT` POSTs /demo/run (body + no-body) **Then** 200 + zero-drift proof | 2/2 pass | **PASS** — Tests run: 2, Failures 0, 3.86s | — | Testcontainers Postgres |
| 7 | **Given** `driftless.demo.enabled` flag **When** absent (default) **Then** /demo/run should be UNavailable (disabled by default) | endpoint 404 when flag absent | **FAIL** — `@ConditionalOnProperty(..., matchIfMissing = true)` ⇒ endpoint is **ENABLED by default** when the flag is absent | matchIfMissing=true contradicts "disabled by default" (DEFECT #2) | n/a (config) |
| 8 | **Given** flag `=true` (docker profile / test) **When** POST /demo/run **Then** it runs | endpoint available | **PASS** — docker profile sets `driftless.demo.enabled=true`; live POST /demo/run returned 200 | — | compose Postgres |
| 9 | **Given** the demo funding step **When** seeding accounts **Then** it is a BALANCED post (debit cardholder == credit settlement), not an unbalanced injection | balanced PostingRequest | **PASS** — `DemoRunner.seedFundedAccounts` posts DEBIT cardholder + CREDIT settlement, equal `Money` | — | n/a (code review) |
| 10 | **Given** a fault-injected demo run **When** it completes **Then** global ∑ journal entries == 0 (no drift) | signed sum 0 | **PASS** — live DB `SELECT SUM(±amount_minor)=0`; metric `recon_drift_amount=0`, `signed_sum_abs=0` | — | compose Postgres |
| 11 | **Given** a completed demo **When** checking obligations **Then** no dangling holds / outstanding partner reverses / stuck outbox | all 0 | **PASS** — `dangling_partner_reverse=0`, `outstanding_partner_obligations=0`, `outbox_pending_depth=0` | — | compose Postgres |
| 12 | **Given** `journal_entry` **When** inspecting triggers **Then** append-only trigger present (immutability) | trigger exists | **PASS** — `trg_journal_entry_append_only` present | — | compose Postgres |
| 13 | **Given** `docker compose build` **When** building **Then** multi-stage app + partner-sim images build, reasonably sized | images build | **PASS** — `driftless/app:latest` 596MB, `driftless/partner-simulator:latest` 524MB | — | n/a |
| 14 | **Given** `docker compose up -d` **When** stack starts **Then** postgres+app+partner-sim+prometheus+grafana up/healthy | all healthy | **PASS** — app/postgres/partner-sim healthy; prometheus/grafana up (no healthcheck defined) | — | compose Postgres (volume) |
| 15 | **Given** Prometheus **When** GET /api/v1/targets **Then** app + partner-simulator health=up | both up | **PASS** — `app:8080` up, `partner-simulator:8081` up (separate targets / real network hop) | — | n/a |
| 16 | **Given** Grafana **When** GET /api/health and dashboard search **Then** ok + Zero Drift dashboard provisioned | ok + dashboard | **PASS** — `database: ok`; dashboard uid `driftless-zero-drift` provisioned | — | n/a |
| 17 | **Given** the live stack **When** `scripts/demo.sh 40 6 MIXED` **Then** /reconciliation/latest passed=true drift 0 and compensating_reversals>0 | zero drift, faults fired | **PASS** — passed=true, totalDriftMinor=0; compensated=4; `compensating_reversals_total`=11 | — | compose Postgres |
| 18 | **Given** the Zero Drift Grafana dashboard **When** Prometheus is queried with the panel's headline expr `max(driftless_recon_drift_amount)` **Then** it returns the $0 series | one series, value 0 | **FAIL** — query returns `[]` (NO DATA); real series is `driftless_recon_drift_amount_minor_units` | dashboard uses un-suffixed name; `.baseUnit("minor_units")` appends `_minor_units` (DEFECT #1) | compose Prometheus |
| 19 | **Given** the dashboard ledger-sum panel `max(driftless_ledger_signed_sum_abs_minor)` **When** queried **Then** returns the 0 series | one series, value 0 | **FAIL** — returns NO DATA; real series is `driftless_ledger_signed_sum_abs_minor_minor_units` (double "minor") | same baseUnit-suffix mismatch (DEFECT #1) | compose Prometheus |
| 20 | **Given** the other dashboard panels (run_passed, compensating reversals rate, outbox depth, stuck outbox) **When** queried **Then** all return data | data present | **PASS** — `recon_run_passed`, `compensating_reversals_total`, `outbox_pending_depth`, `recon_stuck_outbox_count` all return data | — | compose Prometheus |
| 21 | **Given** README curl `curl -s localhost:8080/reconciliation/latest` **When** run literally **Then** passed:true, totalDriftMinor:0 | matches README | **PASS** — `passed=true, totalDriftMinor=0` | — | compose Postgres |
| 22 | **Given** README curl `... /actuator/prometheus \| grep -E 'driftless_recon_drift_amount\|...reversals_total'` **When** run literally **Then** shows drift 0 + reversals | matches README | **PASS** — prefix grep matches `..._minor_units`; drift 0.0, reversals 13.0 | — | compose Postgres |
| 23 | **Given** README quickstart step 2 primary command `make demo` and teardown `make down`/`make clean` **When** followed literally (Docker-only env) **Then** they run | commands work | **FAIL** — `make` not installed; README header says "Requires Docker only". Fallbacks `./scripts/demo.sh` and `docker compose down -v` work | README lists `make` targets as primary but does not list `make` as a prerequisite (DEFECT #3) | n/a |
| 24 | **Given** `docker compose down -v` **When** tearing down **Then** containers + volume removed cleanly | clean teardown | **PASS** — all containers removed, `driftless_pgdata` volume removed | — | n/a |

## Notes / verified claims

- **Zero-drift proof (live):** demo summary `RECONCILIATION passed=True totalDriftMinor=0`; DB
  `SUM(±amount_minor)=0`; `driftless_recon_drift_amount_minor_units=0.0`,
  `driftless_ledger_signed_sum_abs_minor_minor_units=0.0`; `recon_run_passed=1`.
- **Faults actually fired:** `TIMEOUT@AUTHORIZE x8, FAIL_BEFORE_RESPONSE@AUTHORIZE x4`,
  `compensated=4`, `compensating_reversals_total` rose to 11–13 across runs — yet drift stayed 0.
- **Real network boundary preserved:** partner-simulator is a separate container; app reaches
  `http://partner-simulator:8081`; Prometheus lists it as a distinct `health=up` target.
- **Demo metric names in README curl are correct** (prefix grep matches the `_minor_units` series).
  The mismatch only bites the **Grafana dashboard panels**, which use exact PromQL selectors.
