# ADR-0003 — Money as integer minor units

- Status: Accepted
- Date: 2026-06-01
- Deciders: Driftless core team

## Context

Driftless keeps money correct under failure; the balance invariant (`SUM(entries) == 0`) must hold
exactly, with no rounding noise. Floating-point types (`double`, `float`) cannot represent most
decimal monetary values exactly and accumulate error under repeated arithmetic — unacceptable for a
ledger. `BigDecimal` is exact but invites scale/rounding-mode bugs, is comparatively heavy, and its
`equals` distinguishes `2.0` from `2.00`, which is a footgun for value semantics.

## Decision

Represent every monetary amount as a `long` count of the currency's **minor unit** (cents, pence,
…) paired with an explicit ISO-4217 `java.util.Currency`, encapsulated in an immutable
`io.driftless.common.money.Money` record.

- All arithmetic is integer arithmetic (`Math.addExact`/`subtractExact`/`negateExact`), so overflow
  throws rather than wrapping silently.
- Operations that combine two amounts **reject a currency mismatch** with a typed
  `CurrencyMismatchException`; there is no implicit FX in the kernel.
- **No floating point ever touches a balance**, anywhere in the system.
- `Money` is the only monetary type that crosses a module boundary; raw `long`/`BigDecimal` amounts
  are not passed between modules.

## Consequences

- Balances are exact and reproducible; the zero-drift proof is not undermined by representation
  error.
- Currencies with non-2 minor-unit exponents (e.g. JPY, KWD) are handled by the ISO-4217 currency's
  own `getDefaultFractionDigits()` when amounts are presented; storage and math remain pure integer
  minor units.
- Cross-currency math is a compile-of-intent error surfaced loudly at runtime, preventing silent
  mixing of currencies.
- Amounts are bounded by `long` range; overflow is fail-fast rather than corrupting a balance.
