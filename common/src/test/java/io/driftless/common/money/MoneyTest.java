package io.driftless.common.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.Currency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class MoneyTest {

    private static final Currency USD = Currency.getInstance("USD");
    private static final Currency EUR = Currency.getInstance("EUR");

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        void factoryStoresMinorUnitsAndCurrency() {
            Money m = Money.of(1_250L, USD);

            assertThat(m.amountMinor()).isEqualTo(1_250L);
            assertThat(m.currency()).isEqualTo(USD);
        }

        @Test
        void factoryAcceptsIsoCurrencyCode() {
            assertThat(Money.of(99L, "EUR")).isEqualTo(Money.of(99L, EUR));
        }

        @Test
        void zeroIsAdditiveIdentity() {
            Money m = Money.of(500L, USD);

            assertThat(m.plus(Money.zero(USD))).isEqualTo(m);
        }

        @Test
        void rejectsNullCurrency() {
            assertThatNullPointerException().isThrownBy(() -> Money.of(1L, (Currency) null));
        }
    }

    @Nested
    @DisplayName("arithmetic")
    class Arithmetic {

        @Test
        void plusAddsMinorUnitsWithinSameCurrency() {
            assertThat(Money.of(100L, USD).plus(Money.of(250L, USD))).isEqualTo(Money.of(350L, USD));
        }

        @Test
        void minusSubtractsMinorUnitsWithinSameCurrency() {
            assertThat(Money.of(250L, USD).minus(Money.of(100L, USD))).isEqualTo(Money.of(150L, USD));
        }

        @Test
        void minusCanGoNegativeToSupportReversals() {
            assertThat(Money.of(100L, USD).minus(Money.of(250L, USD))).isEqualTo(Money.of(-150L, USD));
        }

        @Test
        void negateFlipsSign() {
            assertThat(Money.of(75L, USD).negate()).isEqualTo(Money.of(-75L, USD));
            assertThat(Money.of(-75L, USD).negate()).isEqualTo(Money.of(75L, USD));
        }

        @Test
        void plusRejectsCrossCurrency() {
            assertThatExceptionOfType(CurrencyMismatchException.class)
                    .isThrownBy(() -> Money.of(100L, USD).plus(Money.of(100L, EUR)))
                    .withMessageContaining("USD")
                    .withMessageContaining("EUR");
        }

        @Test
        void minusRejectsCrossCurrency() {
            assertThatExceptionOfType(CurrencyMismatchException.class)
                    .isThrownBy(() -> Money.of(100L, USD).minus(Money.of(100L, EUR)));
        }

        @Test
        void plusOverflowThrowsArithmeticException() {
            assertThatExceptionOfType(ArithmeticException.class)
                    .isThrownBy(() -> Money.of(Long.MAX_VALUE, USD).plus(Money.of(1L, USD)));
        }

        @Test
        void negateOfLongMinValueThrowsArithmeticException() {
            assertThatExceptionOfType(ArithmeticException.class)
                    .isThrownBy(() -> Money.of(Long.MIN_VALUE, USD).negate());
        }

        @Test
        void plusRejectsNullOperand() {
            assertThatNullPointerException().isThrownBy(() -> Money.of(1L, USD).plus(null));
        }
    }

    @Nested
    @DisplayName("sign predicates")
    class SignPredicates {

        @Test
        void recognisesPositive() {
            assertThat(Money.of(1L, USD).isPositive()).isTrue();
            assertThat(Money.of(0L, USD).isPositive()).isFalse();
            assertThat(Money.of(-1L, USD).isPositive()).isFalse();
        }

        @Test
        void recognisesNegative() {
            assertThat(Money.of(-1L, USD).isNegative()).isTrue();
            assertThat(Money.of(0L, USD).isNegative()).isFalse();
            assertThat(Money.of(1L, USD).isNegative()).isFalse();
        }

        @Test
        void recognisesZero() {
            assertThat(Money.zero(USD).isZero()).isTrue();
            assertThat(Money.of(1L, USD).isZero()).isFalse();
        }
    }

    @Nested
    @DisplayName("comparison")
    class Comparison {

        @Test
        void comparesMagnitudeWithinSameCurrency() {
            assertThat(Money.of(100L, USD).compareTo(Money.of(250L, USD))).isNegative();
            assertThat(Money.of(250L, USD).compareTo(Money.of(100L, USD))).isPositive();
            assertThat(Money.of(100L, USD).compareTo(Money.of(100L, USD))).isZero();
        }

        @Test
        void compareRejectsCrossCurrency() {
            assertThatExceptionOfType(CurrencyMismatchException.class)
                    .isThrownBy(() -> Money.of(100L, USD).compareTo(Money.of(100L, EUR)));
        }
    }
}
