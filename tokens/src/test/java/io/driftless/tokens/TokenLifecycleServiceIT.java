package io.driftless.tokens;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.driftless.common.id.TokenId;
import io.driftless.outbox.internal.OutboxRelay;
import io.driftless.tokens.api.CreateTokenCommand;
import io.driftless.tokens.api.IllegalTokenTransition;
import io.driftless.tokens.api.Token;
import io.driftless.tokens.api.TokenNotFound;
import io.driftless.tokens.api.TokenService;
import io.driftless.tokens.api.TokenStatus;
import io.driftless.tokens.internal.event.TokenStatusChanged;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Integration tests for the keyless {@link TokenService} against real Postgres (Testcontainers).
 * Each test maps to a Spec 06 acceptance criterion: create yields INACTIVE; every legal transition
 * lands in the right state and appends exactly one history row + one outbox event; every illegal
 * transition is rejected and changes nothing; keyless transitions are idempotent on the resulting
 * state; and the raw PAN is never stored.
 */
@SpringBootTest
@Import(PostgresTestContainer.class)
class TokenLifecycleServiceIT {

    static final Instant NOW = Instant.parse("2026-06-01T10:15:30Z");
    static final String CARD_REF = "card-ref-7f3a-not-a-pan";

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        RecordingEventPublisher recordingEventPublisher() {
            return new RecordingEventPublisher();
        }
    }

    @Autowired
    TokenService tokens;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    OutboxRelay relay;

    @Autowired
    RecordingEventPublisher publisher;

    @Autowired
    ObjectMapper objectMapper;

    @BeforeEach
    void resetPublisher() {
        publisher.reset();
    }

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

    private TokenStatus storedStatus(TokenId id) {
        return TokenStatus.valueOf(
                jdbc.queryForObject("SELECT status FROM token WHERE id = ?", String.class, id.value()));
    }

    private Token create() {
        return tokens.create(CreateTokenCommand.forCard(CARD_REF, TokenId.newId()));
    }

    // --- create -> INACTIVE ----------------------------------------------------------------------

    @Test
    void createYieldsInactiveTokenWithOneHistoryRowAndOneEvent() {
        Token token = create();

        assertThat(token.status()).isEqualTo(TokenStatus.INACTIVE);
        assertThat(token.cardRef()).isEqualTo(CARD_REF);
        assertThat(token.updatedAt()).isEqualTo(NOW);
        assertThat(storedStatus(token.id())).isEqualTo(TokenStatus.INACTIVE);
        assertThat(historyCount(token.id())).isEqualTo(1);
        assertThat(eventCount(token.id())).isEqualTo(1);
    }

    // --- each legal transition lands in the right state, one history row + one event each ---------

    @Test
    void theFullLegalLifecycleAdvancesThroughEveryStateOneStepAtATime() {
        Token created = create();
        TokenId id = created.id();

        assertThat(tokens.activate(id).status()).isEqualTo(TokenStatus.ACTIVE);
        assertThat(tokens.suspend(id).status()).isEqualTo(TokenStatus.SUSPENDED);
        assertThat(tokens.resume(id).status()).isEqualTo(TokenStatus.ACTIVE);
        assertThat(tokens.deactivate(id).status()).isEqualTo(TokenStatus.DEACTIVATED);

        // CREATE + ACTIVATE + SUSPEND + RESUME + DEACTIVATE = 5 history rows and 5 events.
        assertThat(historyCount(id)).isEqualTo(5);
        assertThat(eventCount(id)).isEqualTo(5);
        assertThat(storedStatus(id)).isEqualTo(TokenStatus.DEACTIVATED);
    }

    @Test
    void deactivateIsLegalFromInactiveAndFromSuspended() {
        Token fromInactive = create();
        assertThat(tokens.deactivate(fromInactive.id()).status()).isEqualTo(TokenStatus.DEACTIVATED);

        Token fromSuspended = create();
        tokens.activate(fromSuspended.id());
        tokens.suspend(fromSuspended.id());
        assertThat(tokens.deactivate(fromSuspended.id()).status()).isEqualTo(TokenStatus.DEACTIVATED);
    }

    // --- illegal transitions are rejected and change nothing --------------------------------------

    @Test
    void resumeFromInactiveIsIllegalAndChangesNothing() {
        Token created = create();
        TokenId id = created.id();

        assertThatThrownBy(() -> tokens.resume(id))
                .isInstanceOf(IllegalTokenTransition.class)
                .extracting("tokenId", "from", "attempted")
                .containsExactly(id, TokenStatus.INACTIVE, io.driftless.tokens.api.TokenTransition.RESUME);

        assertThat(storedStatus(id)).isEqualTo(TokenStatus.INACTIVE);
        assertThat(historyCount(id)).isEqualTo(1);
        assertThat(eventCount(id)).isEqualTo(1);
    }

    @Test
    void suspendFromInactiveAndActivateFromSuspendedAreIllegal() {
        Token a = create();
        assertThatThrownBy(() -> tokens.suspend(a.id())).isInstanceOf(IllegalTokenTransition.class);
        assertThat(storedStatus(a.id())).isEqualTo(TokenStatus.INACTIVE);

        Token b = create();
        tokens.activate(b.id());
        tokens.suspend(b.id());
        assertThatThrownBy(() -> tokens.activate(b.id())).isInstanceOf(IllegalTokenTransition.class);
        assertThat(storedStatus(b.id())).isEqualTo(TokenStatus.SUSPENDED);
    }

    @Test
    void nothingExitsTheTerminalDeactivatedState() {
        Token created = create();
        TokenId id = created.id();
        tokens.deactivate(id);
        long historyAfterDeactivate = historyCount(id);
        long eventsAfterDeactivate = eventCount(id);

        assertThatThrownBy(() -> tokens.activate(id)).isInstanceOf(IllegalTokenTransition.class);
        assertThatThrownBy(() -> tokens.suspend(id)).isInstanceOf(IllegalTokenTransition.class);
        assertThatThrownBy(() -> tokens.resume(id)).isInstanceOf(IllegalTokenTransition.class);

        assertThat(storedStatus(id)).isEqualTo(TokenStatus.DEACTIVATED);
        assertThat(historyCount(id)).isEqualTo(historyAfterDeactivate);
        assertThat(eventCount(id)).isEqualTo(eventsAfterDeactivate);
    }

    @Test
    void findOnAnUnknownTokenThrowsTokenNotFound() {
        TokenId unknown = TokenId.newId();
        assertThatThrownBy(() -> tokens.find(unknown)).isInstanceOf(TokenNotFound.class);
        assertThatThrownBy(() -> tokens.activate(unknown)).isInstanceOf(TokenNotFound.class);
    }

    // --- keyless idempotency: a retry that finds the target state is a no-op replay ---------------

    @Test
    void aKeylessRetryAlreadyInTheTargetStateIsANoOpReplay() {
        Token created = create();
        TokenId id = created.id();
        Token firstActivate = tokens.activate(id);
        long historyAfterActivate = historyCount(id);
        long eventsAfterActivate = eventCount(id);

        Token replay = tokens.activate(id);

        assertThat(replay.status()).isEqualTo(TokenStatus.ACTIVE);
        assertThat(replay).isEqualTo(firstActivate);
        // No duplicate history row or event was produced.
        assertThat(historyCount(id)).isEqualTo(historyAfterActivate);
        assertThat(eventCount(id)).isEqualTo(eventsAfterActivate);
    }

    @Test
    void createWithAnExplicitIdIsIdempotent() {
        TokenId id = TokenId.newId();
        Token first = tokens.create(CreateTokenCommand.forCard(CARD_REF, id));
        Token second = tokens.create(CreateTokenCommand.forCard(CARD_REF, id));

        assertThat(second).isEqualTo(first);
        assertThat(historyCount(id)).isEqualTo(1);
        assertThat(eventCount(id)).isEqualTo(1);
    }

    @Test
    void suspendAfterResumeIsAGenuineNewTransitionNotAReplay() {
        Token created = create();
        TokenId id = created.id();
        tokens.activate(id);
        tokens.suspend(id);
        tokens.resume(id);

        // A second suspend must actually suspend (not replay the first suspend's result).
        assertThat(tokens.suspend(id).status()).isEqualTo(TokenStatus.SUSPENDED);
        assertThat(storedStatus(id)).isEqualTo(TokenStatus.SUSPENDED);
        // CREATE, ACTIVATE, SUSPEND, RESUME, SUSPEND.
        assertThat(historyCount(id)).isEqualTo(5);
        assertThat(eventCount(id)).isEqualTo(5);
    }

    // --- W1: only a SAME-transition retry replays; a wrong-state transition is strictly rejected ----

    @Test
    void keylessResumeOfAnActiveTokenLastAdvancedByActivateIsIllegalAndChangesNothing() {
        Token created = create();
        TokenId id = created.id();
        tokens.activate(id); // ACTIVE, last transition ACTIVATE
        long historyAfterActivate = historyCount(id);
        long eventsAfterActivate = eventCount(id);

        // resume points at ACTIVE, but ACTIVE was last reached via ACTIVATE — not a replay of resume,
        // so the strict edge check rejects it (spec's explicit illegal example).
        assertThatThrownBy(() -> tokens.resume(id))
                .isInstanceOf(IllegalTokenTransition.class)
                .extracting("tokenId", "from", "attempted")
                .containsExactly(id, TokenStatus.ACTIVE, io.driftless.tokens.api.TokenTransition.RESUME);

        assertThat(storedStatus(id)).isEqualTo(TokenStatus.ACTIVE);
        assertThat(historyCount(id)).isEqualTo(historyAfterActivate);
        assertThat(eventCount(id)).isEqualTo(eventsAfterActivate);
    }

    @Test
    void keylessActivateOfAnActiveTokenLastAdvancedByResumeIsIllegalAndChangesNothing() {
        Token created = create();
        TokenId id = created.id();
        tokens.activate(id);
        tokens.suspend(id);
        tokens.resume(id); // ACTIVE, but last transition RESUME (not ACTIVATE)
        long historyAfterResume = historyCount(id);
        long eventsAfterResume = eventCount(id);

        assertThatThrownBy(() -> tokens.activate(id)).isInstanceOf(IllegalTokenTransition.class);

        assertThat(storedStatus(id)).isEqualTo(TokenStatus.ACTIVE);
        assertThat(historyCount(id)).isEqualTo(historyAfterResume);
        assertThat(eventCount(id)).isEqualTo(eventsAfterResume);
    }

    @Test
    void keylessResumeAfterResumeIsANoOpReplay() {
        Token created = create();
        TokenId id = created.id();
        tokens.activate(id);
        tokens.suspend(id);
        Token firstResume = tokens.resume(id); // ACTIVE, last transition RESUME
        long historyAfterResume = historyCount(id);
        long eventsAfterResume = eventCount(id);

        Token replay = tokens.resume(id);

        assertThat(replay).isEqualTo(firstResume);
        assertThat(replay.status()).isEqualTo(TokenStatus.ACTIVE);
        assertThat(historyCount(id)).isEqualTo(historyAfterResume);
        assertThat(eventCount(id)).isEqualTo(eventsAfterResume);
    }

    // --- propagation: events relay out as TokenStatusChanged with the right from/to ---------------

    @Test
    void transitionsPropagateAsTokenStatusChangedEvents() throws Exception {
        Token created = create();
        TokenId id = created.id();
        tokens.activate(id);

        relay.sweep();

        var delivered = publisher.publishedOfType(TokenStatusChanged.EVENT_TYPE).stream()
                .filter(e -> e.aggregateId().equals(id.toString()))
                .toList();
        assertThat(delivered).hasSize(2);

        TokenStatusChanged createEvent =
                objectMapper.readValue(delivered.get(0).payloadJson(), TokenStatusChanged.class);
        TokenStatusChanged activateEvent =
                objectMapper.readValue(delivered.get(1).payloadJson(), TokenStatusChanged.class);
        assertThat(createEvent.from()).isNull();
        assertThat(createEvent.to()).isEqualTo(TokenStatus.INACTIVE);
        assertThat(activateEvent.from()).isEqualTo(TokenStatus.INACTIVE);
        assertThat(activateEvent.to()).isEqualTo(TokenStatus.ACTIVE);
        assertThat(activateEvent.tokenId()).isEqualTo(id);
    }

    // --- PAN safety: only the reference is stored; there is no PAN column -------------------------

    @Test
    void onlyTheCardReferenceIsStoredNeverARawPan() {
        Token created = create();

        String storedCardRef = jdbc.queryForObject(
                "SELECT card_ref FROM token WHERE id = ?",
                String.class,
                created.id().value());
        assertThat(storedCardRef).isEqualTo(CARD_REF);

        Long panColumns = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_name = 'token' AND lower(column_name) = 'pan'",
                Long.class);
        assertThat(panColumns).isZero();
    }
}
