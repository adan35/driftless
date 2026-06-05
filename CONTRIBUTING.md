# Contributing to Driftless

Thanks for your interest in Driftless — an issuer-processor core whose whole pitch is **provable zero
drift**. The bar for contributions is correctness first: every change must keep the three invariants
true and the zero-drift gate green.

## Ground rules (the three invariants)

These are non-negotiable. A change that weakens any of them will not be merged:

1. **Balance** — every journal write is balanced; `∑ entries == 0` per currency. Unbalanced posts are
   rejected and write nothing.
2. **Immutability** — `journal_entry` is append-only (enforced by a DB trigger). Corrections are new
   compensating entries, never edits/deletes.
3. **Idempotency** — every mutating operation is safe to retry; a replay returns the original result.

The property-based gate (`recon/.../LedgerInvariantPropertyTest`) encodes these. **Never weaken,
`@Disabled`, or delete it** to make a build pass — that is a regression, not a fix.

## Project conventions

Read [`CLAUDE.md`](./CLAUDE.md) first — it is the canonical context (stack, module map, conventions).
In short:

- **Java 21, Spring Boot 4, Maven multi-module.** Dependencies point strictly inward toward
  `ledger`/`common`; modules integrate only through published `api`/`spi` packages and outbox events
  — never another module's `internal`.
- **Money is `Money`** (integer minor units) — no floating point, ever. **Time is an injected
  `Clock`** — never `Instant.now()` in business logic.
- Thin `@RestController`s (no JPA entities returned); business logic in `@Service`; constructor
  injection; `@ConfigurationProperties` over scattered `@Value`.
- SLF4J `{}` logging — never string concatenation, never `System.out`. **No raw PAN** in logs,
  storage, or metric labels.
- Every mutating endpoint reads an `Idempotency-Key` (missing ⇒ 400, same key + different body ⇒
  409, replay ⇒ original 2xx).
- The `io.driftless.ledger.api` shape is **frozen** — do not change existing types without an ADR and
  a deliberate review (adding a new `spi` interface is fine).

## Workflow

1. **Open an issue** describing the change (especially anything touching the ledger core or the auth
   saga — those are gated, sequential changes).
2. **Branch** from `main`.
3. **Write the test first** for new/changed behavior. Integration tests use Testcontainers Postgres;
   invariant properties use jqwik. A change that moves money needs an explicit `∑ == 0` assertion.
4. **Implement**, reusing existing methods/beans before writing new ones.
5. **Run the full build and gates:**
   ```bash
   ./mvnw spotless:apply     # auto-format (palantir-java-format)
   ./mvnw verify             # compile + tests + Spotless + JaCoCo + the zero-drift property gate
   ```
   A running Docker daemon is required (Testcontainers). The build must end green with the property
   gate **running**, not skipped.
6. **Open a pull request** that explains *what changed and why*, notes any corrected (vs. new) logic,
   and states how the invariants are upheld. Keep commits focused and descriptive.

## Tests, specs, and docs

- Engineering specs live in [`.claude/specs/`](./.claude/specs/); published documentation in
  [`.docs/`](./.docs/); ADRs in [`docs/adr/`](./docs/adr/).
- If you change behavior, update the relevant `.docs/` page and any affected spec/ADR.

## Code of Conduct

By participating you agree to abide by our [Code of Conduct](./CODE_OF_CONDUCT.md).

## License

By contributing, you agree that your contributions are licensed under the project's
[Apache License 2.0](./LICENSE).
