package io.driftless.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.driftless.common.id.AccountId;
import io.driftless.common.money.Money;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the shape-preserving compact-constructor guard added to {@link PostingLine} (review
 * finding W1): a posting line's amount is strictly positive minor units; {@link Direction} carries
 * the sign.
 */
class PostingLineTest {

    private static final AccountId ACCOUNT = AccountId.newId();

    @Test
    void acceptsStrictlyPositiveAmount() {
        PostingLine line = new PostingLine(ACCOUNT, Direction.DEBIT, Money.of(100L, "USD"));
        assertThat(line.amount().amountMinor()).isEqualTo(100L);
    }

    @Test
    void rejectsZeroAmount() {
        assertThatThrownBy(() -> new PostingLine(ACCOUNT, Direction.DEBIT, Money.of(0L, "USD")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strictly positive");
    }

    @Test
    void rejectsNegativeAmount() {
        assertThatThrownBy(() -> new PostingLine(ACCOUNT, Direction.CREDIT, Money.of(-1L, "USD")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strictly positive");
    }

    @Test
    void rejectsNullComponents() {
        assertThatThrownBy(() -> new PostingLine(null, Direction.DEBIT, Money.of(1L, "USD")))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PostingLine(ACCOUNT, null, Money.of(1L, "USD")))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PostingLine(ACCOUNT, Direction.DEBIT, null))
                .isInstanceOf(NullPointerException.class);
    }
}
