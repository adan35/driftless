# Security Policy

Driftless is a reference implementation of an issuer-processor core. Although it is built around
strong correctness invariants, **it is not a production-hardened payments system** — real PCI scope,
PAN encryption/HSM, authentication/authorization, TLS, and secrets management are explicitly future
work (see the per-spec "out of scope" sections and `docs/adr/`).

## What is in scope

- **Correctness invariants.** A reproducible way to make the ledger drift (a balanced sequence of
  API calls that yields `∑ journal entries != 0`), bypass the append-only guarantee, or double-apply
  an idempotent operation is the most serious class of issue and is always in scope.
- **Idempotency / saga safety.** A way to leave a dangling hold, a half-posted capture, or an
  unbounded partner wait.
- **Data exposure.** Any path that causes a raw PAN (card number) to reach a log line, a persisted
  column, a metric label, or an error response. Tokens deliberately store only an opaque `cardRef`.
- **Injection / standard web vulnerabilities** in the REST surface (SQL injection, etc.).

## What is out of scope (known, documented limitations)

- The REST endpoints ship **without authentication** by design in the demo profile; the `/demo/run`
  endpoint is `@ConditionalOnProperty`-gated and disabled by default.
- Local-development credentials (e.g. `postgres/driftless`) are committed for one-command bring-up
  and are clearly local-only.
- The in-memory `VelocityStore` is per-instance (documented as the boundary to move to Redis before
  horizontal scale).
- No rate limiting, request-size limits, TLS, or secret management — all noted as future hardening.

## Reporting a vulnerability

Please report security issues **privately** rather than opening a public issue:

- Open a [GitHub Security Advisory](https://github.com/adan35/driftless/security/advisories/new) on
  the repository, **or**
- Email the maintainer at the address on the repository owner's GitHub profile.

Include a clear description, the affected component, and ideally a minimal reproduction (for an
invariant breach, the sequence of API calls and the resulting non-zero reconciliation result).

You can expect an acknowledgement within a few days. Because this is a volunteer-maintained reference
project, fixes are best-effort; we will credit reporters in the release notes unless you prefer to
remain anonymous.

## Disclosure

Please give us a reasonable window to address a confirmed issue before any public disclosure.
