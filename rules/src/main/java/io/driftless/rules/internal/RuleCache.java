package io.driftless.rules.internal;

import io.driftless.rules.config.RuleProperties;
import jakarta.annotation.PostConstruct;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Holds the live {@link CompiledRuleSet} and refreshes it <strong>off the hot path</strong>.
 *
 * <p>The hot path calls {@link #current()}, a single volatile read of an {@link AtomicReference} — no
 * lock, no I/O, no recompile. Refreshing (at startup, on the configured interval, or on demand) reads
 * the authored {@link RuleProperties} and compiles a fresh immutable set, then swaps the reference in
 * one atomic publish. In-flight evaluations keep using the snapshot they read; the next evaluation
 * sees the new one. This is how a rule change takes effect without a restart.
 *
 * <p>If a refresh fails to compile (e.g. a bad edit), the previous good set is kept and the error is
 * logged — the hot path never sees an empty or half-built rule set because of a typo.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RuleCache {

    private final RuleProperties properties;
    private final RuleCompiler compiler;

    private final AtomicReference<CompiledRuleSet> active = new AtomicReference<>(CompiledRuleSet.empty());

    /** Compile once at startup so the very first authorization sees the authored rules. */
    @PostConstruct
    void initialize() {
        refresh();
    }

    /**
     * The current immutable rule set — the only thing the hot path reads. Never {@code null}: it is an
     * empty (approve-all) set until the first successful compile.
     */
    public CompiledRuleSet current() {
        return active.get();
    }

    /**
     * Re-read the authored rules and atomically publish a freshly compiled set. Safe to call from the
     * scheduler or a management trigger; on a compile error the previous good set is retained.
     *
     * @return {@code true} if a new set was published, {@code false} if compilation failed and the
     *     previous set was kept
     */
    public boolean refresh() {
        try {
            CompiledRuleSet recompiled = compiler.compile(properties);
            CompiledRuleSet previous = active.getAndSet(recompiled);
            log.info("rule cache refreshed: {} -> {} active rules", previous.size(), recompiled.size());
            return true;
        } catch (RuntimeException ex) {
            log.error(
                    "rule cache refresh failed; keeping {} previously active rules",
                    current().size(),
                    ex);
            return false;
        }
    }

    /**
     * Scheduled off-hot-path reload. The fixed delay is bound from {@code
     * driftless.rules.refresh-interval} (default 30s). Spring's scheduler requires a positive
     * duration, so a non-positive value fails fast at startup rather than silently disabling refresh;
     * each tick simply re-reads and recompiles via {@link #refresh()}.
     */
    @Scheduled(fixedDelayString = "${driftless.rules.refresh-interval:30s}")
    void scheduledRefresh() {
        refresh();
    }
}
