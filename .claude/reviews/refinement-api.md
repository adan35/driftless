# Review — API-Refinement (account lifecycle + funding, keyset statement pagination, error codes)

## GATE: PASS

All three invariants hold. No Critical findings. One Warning (since fixed), several Info.

## Verified
- **Frozen contract — purely additive.** `Ledger` gains only `findAccount` + `entriesAfter` and the
  new `EntryCursor`/`EntryPage` types; no existing signature/record/behavior changed. All
  implementers (LedgerService, MeteredLedger, test doubles) delegate faithfully.
- **Funding = balanced post.** `POST /accounts/{id}/funding` posts `DEBIT target / CREDIT per-currency
  ASSET funding-source` via the normal `ledger.post` (∑ per currency == 0); unknown account / currency
  mismatch validated before any write; idempotent (replay returns original, same key + different
  amount → 409); IT asserts global signed sum == 0.
- **Keyset pagination.** DB-generated global `entry_seq` (insertable=false), seek query
  `WHERE entry_seq > ? ORDER BY entry_seq LIMIT ?` (no OFFSET), opaque cursor parsed to a bound `Long`
  (no SQL injection; malformed → 400), limit double-capped at 200.
- **Account open** idempotent; **error codes** add a stable `code` + `type` URI consistently across
  auth/accounts/tokens; thin controllers, DTOs, injected Clock, Idempotency-Key enforced, no PAN.
- OpenAPI is a hand-authored `deploy/openapi/openapi.yaml` + static Swagger UI webjar (springdoc 2.x is
  incompatible with Spring Boot 4 — the build stays green).

## Findings (W1 fixed in follow-up)
- **W1 (fixed):** the keyset "never skips under concurrent appends" claim was overstated — Postgres
  sequences are non-transactional, so a lower `entry_seq` committing after a higher one can be
  transiently omitted from a statement page (no ledger invariant breached; a fresh read from START and
  the offset path always return everything). Resolved by correcting the wording across
  `Ledger`/`EntryCursor`/`EntryPage`/`LedgerService`/`JournalEntryRepository`/`V3` and adding a
  deterministic two-connection IT (`LedgerKeysetPaginationIT.inFlightLowerSequenceIsTransientlyOmitted...`)
  that documents the behavior and proves no data loss.
- **I2 (fixed):** added funding same-key/different-amount → 409 and malformed-cursor → 400 tests.
- **I1 (fixed):** V3 comment notes monotonic backfill relies on the append-only guarantee.
- I3/I5 (optional polish): not pursued.

Build: `./mvnw verify` BUILD SUCCESS (all 11 modules; recon property gate running, not skipped).
