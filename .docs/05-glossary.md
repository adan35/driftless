# Glossary

The domain vocabulary used throughout Driftless. Read this once and the code, specs, and dashboards
read clearly.

| Term | Meaning |
|------|---------|
| **Issuer** | The cardholder's bank — the party Driftless models. It owes the cardholder their balance and authorizes/settles their card transactions. |
| **Issuer-processor** | The system that runs authorization and keeps the issuer's books. Driftless is a reference implementation of one. |
| **Acquirer** | The merchant's bank (out of scope here; the partner-simulator stands in for the external network). |
| **Double-entry** | Every transaction touches ≥2 accounts and debits equal credits, so money only *moves* — it's never created or destroyed. The signed sum of all entries is always zero. |
| **Journal entry** | One immutable, append-only leg of a transaction: an account, a direction (DEBIT/CREDIT), and a positive `Money` amount. |
| **Transaction** | A balanced batch of ≥2 journal entries posted atomically (`Ledger.post`). |
| **Posted balance** | The settled balance of an account: `SUM` over its journal entries, signed by direction. There is **no stored balance column** — it is always derived. |
| **Available balance** | `posted − activeHoldTotal`. What the cardholder can actually spend right now. |
| **Hold (authorization hold)** | A claim against funds placed when an authorization is approved. It reduces **available** immediately but moves no **posted** money. Reported to the ledger via the `HoldView` SPI; released by status change, never deleted. |
| **Authorization** | The "may I charge this card?" step. Approved → a hold is placed and the external partner is asked to authorize. |
| **Capture (settlement)** | Converting an authorization into a real money movement: posts a balanced settlement and releases the hold. Supports an amount ≤ the authorized amount. |
| **Reversal** | Undoing an authorization or capture via a **new compensating** transaction (and releasing the hold) — never by editing the original. |
| **Compensating reversal** | The reversal the saga runs automatically when the partner leg times out/fails, so a stuck call never leaves a phantom hold. The heart of the "$20K drift fix". |
| **Drift** | Any disagreement between the ledger and reality — i.e. `∑ journal entries != 0`, or a hold/outbox inconsistency. Driftless's whole purpose is to make drift provably impossible. |
| **Money** | An amount in integer **minor units** (cents/pence) of an explicit ISO-4217 currency. No floating point ever touches a balance. |
| **Minor units** | The smallest unit of a currency (e.g. cents for USD). `amountMinor = 10_000` means $100.00. |
| **MCC** | Merchant Category Code (ISO-18245): a 4-digit code identifying the merchant's business type, used by the rule engine (e.g. block gambling). |
| **Velocity** | A rolling-window measure of recent activity (count/total) on an account, used by the rule engine to decline rapid spending. |
| **Idempotency key** | A caller-supplied key that makes a mutating operation safe to retry: a replay returns the original result and produces no new side effect. |
| **Outbox** | The transactional outbox pattern: a domain event is written in the *same* DB transaction as the state change, then relayed exactly-once by a separate poller. |
| **Saga** | A long-running, multi-step operation (authorize → capture/reverse) coordinated with compensating actions instead of a distributed transaction. |
| **Token** | A wallet/device surrogate for a card (à la VTS), with its own lifecycle (INACTIVE→ACTIVE→SUSPENDED→DEACTIVATED). Stores only a `cardRef`, never the raw PAN. |
| **PAN** | Primary Account Number — the raw card number. Driftless never stores or logs it; it keeps an opaque `cardRef` reference. |
| **Reconciliation** | The continuous balance-proof job: it sums the whole ledger and checks holds/outbox consistency, proving (or detecting a breach of) zero drift. |
| **Property-based test (the gate)** | A jqwik test that generates random sequences of `auth/capture/reverse/fault` and asserts the invariants after each settles. The non-negotiable CI gate. |
| **Partner simulator** | The one genuinely separate service — a real network boundary the saga calls and that a fault-injection control plane can make slow/fail/duplicate/drop. |
| **HoldView** | The ledger SPI that reports active holds so the ledger can compute `available = posted − activeHoldTotal`. Implemented by the auth saga. |
| **Compensating entry** | A new journal transaction that offsets a prior one. The only correct way to "fix" the books — the originals are immutable. |
