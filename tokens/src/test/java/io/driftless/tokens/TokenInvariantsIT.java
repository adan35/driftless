package io.driftless.tokens;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.driftless.common.id.TokenId;
import io.driftless.tokens.api.CreateTokenCommand;
import io.driftless.tokens.api.Token;
import io.driftless.tokens.api.TokenService;
import io.driftless.tokens.api.TokenStatus;
import io.driftless.tokens.internal.KeyedTokenService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * QA-authored invariant probes for Spec 06 that the developer's suite did not cover directly:
 *
 * <ul>
 *   <li>the database-level append-only trigger on {@code token_status_history} rejects UPDATE/DELETE;
 *   <li>a keyed (caller-key) retry of a transition replays the original result and writes no
 *       duplicate history row or outbox event;
 *   <li>the keyed surface enforces the strict state machine (illegal transition under a fresh key
 *       raises and changes nothing — no idempotency record, no history row, no event).
 * </ul>
 */
@SpringBootTest
@Import(PostgresTestContainer.class)
class TokenInvariantsIT {

    static final Instant NOW = Instant.parse("2026-06-01T10:15:30Z");
    static final String CARD_REF = "card-ref-inv-not-a-pan";

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    KeyedTokenService tokens;

    @Autowired
    TokenService keyless;

    @Autowired
    JdbcTemplate jdbc;

    private long historyCount(TokenId id) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM token_status_history WHERE token_id = ?", Long.class, id.value());
    }

    private long eventCount(TokenId id) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = 'TokenStatusChanged'",
                Long.class,
                id.toString());
    }

    private long keyCount(String key) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE idempotency_key = ?", Long.class, key);
    }

    private TokenStatus storedStatus(TokenId id) {
        return TokenStatus.valueOf(
                jdbc.queryForObject("SELECT status FROM token WHERE id = ?", String.class, id.value()));
    }

    private TokenId createKeyed(String key) {
        return tokens.create(CreateTokenCommand.forCard(CARD_REF, TokenId.newId()), key)
                .id();
    }

    // --- criterion 8: append-only trigger rejects UPDATE and DELETE at the DB ----------------------

    @Test
    void theAppendOnlyTriggerRejectsUpdateOnTokenStatusHistory() {
        TokenId id = createKeyed("inv-trig-upd-create");
        UUID rowId = jdbc.queryForObject(
                "SELECT id FROM token_status_history WHERE token_id = ? LIMIT 1", UUID.class, id.value());

        assertThatThrownBy(() ->
                        jdbc.update("UPDATE token_status_history SET to_status = 'DEACTIVATED' WHERE id = ?", rowId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");

        // The row is untouched.
        assertThat(jdbc.queryForObject("SELECT to_status FROM token_status_history WHERE id = ?", String.class, rowId))
                .isEqualTo("INACTIVE");
    }

    @Test
    void theAppendOnlyTriggerRejectsDeleteOnTokenStatusHistory() {
        TokenId id = createKeyed("inv-trig-del-create");
        long before = historyCount(id);

        assertThatThrownBy(() -> jdbc.update("DELETE FROM token_status_history WHERE token_id = ?", id.value()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");

        assertThat(historyCount(id)).isEqualTo(before);
    }

    // --- keyed idempotency: a retry under the same key replays, no duplicate effect ----------------

    @Test
    void aKeyedActivateRetryReplaysAndWritesNothingNew() {
        TokenId id = createKeyed("inv-keyed-create");
        var first = tokens.activate(id, "inv-keyed-activate");
        long historyAfter = historyCount(id);
        long eventsAfter = eventCount(id);

        var replay = tokens.activate(id, "inv-keyed-activate");

        assertThat(replay).isEqualTo(first);
        assertThat(replay.status()).isEqualTo(TokenStatus.ACTIVE);
        assertThat(historyCount(id)).isEqualTo(historyAfter);
        assertThat(eventCount(id)).isEqualTo(eventsAfter);
    }

    // --- keyed surface enforces the strict state machine; illegal transition changes nothing -------

    @Test
    void aKeyedIllegalTransitionRaisesAndRecordsNoKeyHistoryOrEvent() {
        TokenId id = createKeyed("inv-keyed-illegal-create");
        tokens.activate(id, "inv-keyed-illegal-activate");
        long historyAfter = historyCount(id);
        long eventsAfter = eventCount(id);
        String illegalKey = "inv-keyed-illegal-resume";

        assertThatThrownBy(() -> tokens.resume(id, illegalKey))
                .isInstanceOf(io.driftless.tokens.api.IllegalTokenTransition.class);

        // Nothing changed and — crucially — the idempotency claim rolled back, so the same key is
        // free to be reused once the token is legitimately in a state that permits the transition.
        assertThat(storedStatus(id)).isEqualTo(TokenStatus.ACTIVE);
        assertThat(historyCount(id)).isEqualTo(historyAfter);
        assertThat(eventCount(id)).isEqualTo(eventsAfter);
        assertThat(keyCount(illegalKey)).isZero();
    }

    // --- W2: a web caller key colliding with the derived scheme must not replay an internal op -------

    @Test
    void aWebCallerKeyCollidingWithTheDerivedSchemeDoesNotReplayAnInternalTransition() {
        TokenId id = createKeyed("inv-collide-create"); // one history row (CREATE)
        keyless.activate(id); // internal derived key "tokens:<id>:ACTIVATE:1"; token now ACTIVE
        long historyAfterActivate = historyCount(id);

        // A web caller hands in a raw Idempotency-Key that is byte-identical to that derived internal
        // key. Pre-fix it landed on the same (scope,key) and false-replayed/conflicted the internal
        // activate; post-fix web keys live under the reserved "tokens.web:" prefix, so the suspend runs.
        String collidingKey = "tokens:" + id + ":ACTIVATE:1";
        Token suspended = tokens.suspend(id, collidingKey);

        assertThat(suspended.status()).isEqualTo(TokenStatus.SUSPENDED);
        assertThat(storedStatus(id)).isEqualTo(TokenStatus.SUSPENDED);
        // The suspend really executed (a new history row), proving no aliasing with the internal key.
        assertThat(historyCount(id)).isEqualTo(historyAfterActivate + 1);
    }
}
