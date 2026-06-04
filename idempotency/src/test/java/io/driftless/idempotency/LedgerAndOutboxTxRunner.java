package io.driftless.idempotency;

import io.driftless.common.id.AccountId;
import io.driftless.ledger.api.Ledger;
import io.driftless.ledger.api.PostingResult;
import io.driftless.outbox.api.OutboxEvent;
import io.driftless.outbox.api.OutboxWriter;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Test helper that exercises the outbox's transactional-atomicity contract: it posts a real ledger
 * transaction and appends an outbox event in the <em>same</em> {@code @Transactional} method, so a
 * forced failure rolls both back together.
 *
 * <p>It is a real Spring bean (not an inline lambda) so the {@code @Transactional} boundary is a
 * genuine proxy boundary and {@link OutboxWriter}'s {@code MANDATORY} propagation sees the active
 * transaction.
 */
@Component
@RequiredArgsConstructor
class LedgerAndOutboxTxRunner {

    private final Ledger ledger;
    private final OutboxWriter outbox;
    private final Clock clock;

    /** Post a balanced transfer and append an event atomically; optionally fail to force a rollback. */
    @Transactional
    PostingResult postAndAppend(String key, AccountId from, AccountId to, long minor, boolean fail) {
        PostingResult posted = ledger.post(IdempotencyFixtures.transfer(key, from, to, minor));
        outbox.append(new OutboxEvent(
                "ledger.transaction",
                posted.transactionId().toString(),
                "TransactionPosted",
                "{\"transactionId\":\"" + posted.transactionId() + "\"}",
                clock.instant()));
        if (fail) {
            throw new IllegalStateException("forced rollback after ledger post + outbox append");
        }
        return posted;
    }
}
