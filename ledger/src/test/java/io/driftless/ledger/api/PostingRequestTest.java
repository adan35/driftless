package io.driftless.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.driftless.common.id.AccountId;
import io.driftless.common.money.Money;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the shape-preserving compact-constructor guards on {@link PostingRequest}: non-blank
 * idempotency key, non-null business time, at least two legs, and a defensively-copied line list.
 */
class PostingRequestTest {

    private static PostingLine line(Direction direction) {
        return new PostingLine(AccountId.newId(), direction, Money.of(100L, "USD"));
    }

    private static List<PostingLine> balancedPair() {
        return List.of(line(Direction.DEBIT), line(Direction.CREDIT));
    }

    @Test
    void acceptsAWellFormedRequest() {
        PostingRequest request = new PostingRequest("key-1", Instant.EPOCH, "desc", balancedPair());
        assertThat(request.lines()).hasSize(2);
    }

    @Test
    void rejectsBlankIdempotencyKey() {
        assertThatThrownBy(() -> new PostingRequest("  ", Instant.EPOCH, "desc", balancedPair()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("idempotencyKey");
        assertThatThrownBy(() -> new PostingRequest(null, Instant.EPOCH, "desc", balancedPair()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("idempotencyKey");
    }

    @Test
    void rejectsNullOccurredAt() {
        assertThatThrownBy(() -> new PostingRequest("key-1", null, "desc", balancedPair()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsFewerThanTwoLines() {
        assertThatThrownBy(() -> new PostingRequest("key-1", Instant.EPOCH, "desc", List.of(line(Direction.DEBIT))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 2 lines");
    }

    @Test
    void copiesLinesDefensively() {
        List<PostingLine> mutable = new ArrayList<>(balancedPair());
        PostingRequest request = new PostingRequest("key-1", Instant.EPOCH, "desc", mutable);
        mutable.clear();
        assertThat(request.lines()).hasSize(2);
        assertThatThrownBy(() -> request.lines().add(line(Direction.DEBIT)))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
