package io.driftless.common.id;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class TypedIdTest {

    @Test
    void accountIdWrapsAndUnwrapsUuid() {
        UUID uuid = UUID.randomUUID();

        assertThat(AccountId.of(uuid).value()).isEqualTo(uuid);
        assertThat(AccountId.of(uuid.toString()).value()).isEqualTo(uuid);
        assertThat(AccountId.of(uuid)).hasToString(uuid.toString());
    }

    @Test
    void newIdMintsDistinctValues() {
        assertThat(TxId.newId()).isNotEqualTo(TxId.newId());
        assertThat(EntryId.newId()).isNotEqualTo(EntryId.newId());
        assertThat(TokenId.newId()).isNotEqualTo(TokenId.newId());
        assertThat(AccountId.newId()).isNotEqualTo(AccountId.newId());
    }

    @Test
    void typedIdsRejectNullValue() {
        assertThatNullPointerException().isThrownBy(() -> AccountId.of((UUID) null));
        assertThatNullPointerException().isThrownBy(() -> TxId.of((UUID) null));
        assertThatNullPointerException().isThrownBy(() -> EntryId.of((UUID) null));
        assertThatNullPointerException().isThrownBy(() -> TokenId.of((UUID) null));
    }

    @Test
    void sameUnderlyingUuidIsEqual() {
        UUID uuid = UUID.randomUUID();

        assertThat(TxId.of(uuid)).isEqualTo(TxId.of(uuid));
        assertThat(TokenId.of(uuid)).isEqualTo(TokenId.of(uuid));
    }
}
