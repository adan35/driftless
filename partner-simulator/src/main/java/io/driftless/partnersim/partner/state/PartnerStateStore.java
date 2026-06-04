package io.driftless.partnersim.partner.state;

import io.driftless.partnersim.fault.PartnerRoute;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * In-memory record of the simulator's own state: request id → completed response (for idempotent
 * replay) and the side effects performed server-side. Holds no money or ledger semantics; it exists
 * purely so retries are safe and so a test can prove {@code FAIL_BEFORE_RESPONSE} did the work even
 * though the caller never got a success.
 */
@Slf4j
@Component
public class PartnerStateStore {

    /** Mutable per-request id state. */
    private static final class Entry {
        private volatile RequestRecord record;
        private final List<SideEffect> sideEffects = new CopyOnWriteArrayList<>();
    }

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    private Entry entry(String requestId) {
        return entries.computeIfAbsent(requestId, id -> new Entry());
    }

    /** The completed record for a request id, if one has been stored (used for replay). */
    public Optional<RequestRecord> findRecord(String requestId) {
        Entry entry = entries.get(requestId);
        return entry == null ? Optional.empty() : Optional.ofNullable(entry.record);
    }

    /**
     * Append a server-side side effect for a request id. Logged so QA can trace that the simulator
     * "did the work" — central to proving the drift trigger.
     */
    public void recordSideEffect(String requestId, SideEffect sideEffect) {
        entry(requestId).sideEffects.add(sideEffect);
        log.info(
                "side effect recorded requestId={} route={} partnerRef={} delivered={}",
                requestId,
                sideEffect.route(),
                sideEffect.partnerRef(),
                sideEffect.delivered());
    }

    /** Store the original response so a later replay of the same request id echoes it. */
    public void recordCompletion(String requestId, PartnerRoute route, Object response) {
        entry(requestId).record = new RequestRecord(requestId, route, response);
        log.info("completion recorded requestId={} route={}", requestId, route);
    }

    /** Build the read-only state view for the inspection endpoint. */
    public RequestStateView view(String requestId) {
        Entry entry = entries.get(requestId);
        if (entry == null) {
            return RequestStateView.unknown(requestId);
        }
        RequestRecord record = entry.record;
        List<SideEffect> effects = List.copyOf(entry.sideEffects);
        PartnerRoute route = record != null
                ? record.route()
                : effects.stream().map(SideEffect::route).findFirst().orElse(null);
        return new RequestStateView(
                requestId,
                true,
                route,
                record != null,
                !effects.isEmpty(),
                effects,
                record == null ? null : record.response());
    }

    /** Drop all state (used to isolate tests / demo runs). */
    public void clear() {
        entries.clear();
        log.info("partner state cleared");
    }
}
