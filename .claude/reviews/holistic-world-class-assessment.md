# Driftless — Holistic World-Class Assessment

> Whole-system architecture & quality review judging Driftless against the bar of a top-tier,
> reference-grade open-source double-entry ledger / issuer-processor. Produced during the refinement
> phase after all 10 specs (00–09) shipped green under the property gate.

## Overall verdict

**Driftless is already very good — top ~5% of open-source payments projects — and the core
engineering is genuinely excellent.** The three invariants are enforced in depth (code + DB trigger +
property gate); the saga's bounded-timeout compensating reversal with durable recovery and
late-response absorption is correct and subtle; balance-by-summation is principled; module boundaries
(api/spi seams, inward deps, outbox integration) are clean.

**The gap to "world-class" is not correctness — it is completeness of the surface and the proof.**
Five themes: (1) the public API is thinner than a real issuer-processor needs; (2) the gate is strong
but single-currency/single-account/sequential; (3) operability lacks alerts/runbooks/tracing; (4)
CI/supply-chain is minimal; (5) repo hygiene (LICENSE/CONTRIBUTING/SECURITY) was missing.

## Per-dimension verdicts (summary)

1. **Correctness & invariants — Excellent.** Per-currency balance validation with `Math.addExact`,
   append-only trigger, DB-unique idempotency backstop, overflow-checked `Money`, partial-capture
   reverses the captured amount. Gaps: multiple/incremental partial captures not supported;
   multi-currency modeled but never exercised end-to-end.
2. **API / contract design — Clean but incomplete.** Exemplary frozen `ledger.api`/`spi`, RFC-7807
   errors, idempotency-key enforcement. Gaps: no account-lifecycle/funding REST; `entriesFor` is
   offset not keyset; no OpenAPI; error bodies lack machine-readable codes.
3. **Architecture & boundaries — Excellent.** Strict inward deps, recon reads via published SPIs,
   partner is a separate service. Gaps: boundaries are convention not CI-enforced (add ArchUnit);
   in-memory VelocityStore is the one horizontal-scale leak (documented).
4. **Resilience & failure handling — Excellent (standout).** Two-phase authorize, bounded timeout,
   hold released on compensation, partner-reverse retried-to-completion, late-response grace,
   crash-safe outbox. Gaps: no poison/dead-letter parking; no backoff on partner retries.
5. **Observability & operability — Good for demo, thin for prod.** Well-modeled metrics, provisioned
   dashboards, AFTER_COMMIT gauge binding, operator SPIs. Gaps: no alert rules/Alertmanager; no
   tracing; no JSON logging/correlation id; no runbook.
6. **Testing rigor — Strong, honest gate, under-covers its claims.** Testcontainers, jqwik gate with
   anti-skip guard, concurrency ITs, drift-detection IT. Gap: gate is sequential/single-account/
   single-currency — the hardest claims (concurrency, multi-currency) aren't property-tested.
7. **Documentation — Strong on the "why", missing OSS table-stakes.** `.docs/` deep-dives, ADRs,
   specs, README are reference-quality. Gaps: LICENSE/CONTRIBUTING/SECURITY/CODE_OF_CONDUCT absent;
   no glossary/C4; keyset-pagination doc/code mismatch.
8. **Security & compliance — Solid fundamentals, deliberately pre-PCI.** No raw PAN anywhere,
   validated inputs, parameterized queries, gated demo endpoint. Gaps: no auth on endpoints
   (demo-only acceptable, document it); hardcoded local DB creds.
9. **Build / CI / supply chain — Good local gates, minimal CI.** Per-module JaCoCo, Spotless,
   property gate in verify. Gaps: single CI job; no dependency/image scanning, no SBOM, no
   maven-enforcer, no release flow.
10. **WOW / pitch — Lands.** Zero drift proven by construction, continuously, under fault, and in CI.
    Single highest-leverage win: make the gate **concurrent and multi-currency**.

## Top P1 enhancements (refinement backlog)

1. Account-lifecycle + funding REST endpoints (money moves through the public API, not just the demo).
2. Make the zero-drift gate **concurrent + multi-currency** (highest-leverage credibility win).
3. Prometheus alert rules + Alertmanager (`drift != 0`, `recon_passed == 0`, stuck-outbox, rising
   partner obligations) — the pitch needs an alarm that fires.
4. `LICENSE` (Apache-2.0) + `SECURITY.md` + `CONTRIBUTING.md` + `CODE_OF_CONDUCT.md`.
5. OpenAPI (`springdoc` + committed `openapi.yaml`).
6. Keyset/seek pagination for `entriesFor` (+ a statement REST endpoint); align the docs.
7. CI/supply-chain hardening: dependency-check, Trivy image scan, CycloneDX SBOM, maven-enforcer.
8. Enforce module boundaries in CI with ArchUnit / Spring Modulith.

## Already excellent — preserve as-is

The three-invariant enforcement (code + trigger + gate), the bounded-timeout compensating reversal
with durable recovery and late-response absorption, balance-by-summation, the api/spi seams and
outbox-based module integration, and the honest property gate with its anti-skip guard.

---

### Refinement actions taken (this phase)

- P1 #4 (hygiene): added LICENSE (Apache-2.0), SECURITY.md, CONTRIBUTING.md, CODE_OF_CONDUCT.md, a
  glossary (`.docs/05-glossary.md`).
- P1 #3 (ops): added `deploy/prometheus/alerts.yml` + wired `rule_files`/`alerting` into
  `prometheus.yml` + an `alertmanager` service in compose.
- P1 #1/#5/#6 (API): account-lifecycle + funding REST, OpenAPI, keyset pagination — delivered via the
  `/deliver` pipeline.
- P1 #2 (gate): concurrent + multi-currency property gate — delivered via the `/deliver` pipeline.
- P1 #7/#8 (CI): ArchUnit boundaries, maven-enforcer, supply-chain scanning + SBOM — delivered via
  the `/deliver` pipeline.
