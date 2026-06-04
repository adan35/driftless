package io.driftless.rules.internal;

import io.driftless.rules.api.AuthContext;
import io.driftless.rules.api.RuleEngine;
import io.driftless.rules.api.RuleResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * The Spec 05 implementation of the frozen {@link RuleEngine} contract: a pure, deterministic,
 * I/O-free evaluator over a pre-compiled, in-memory rule set.
 *
 * <p>Hot-path guarantees enforced by construction:
 *
 * <ul>
 *   <li><b>No I/O</b> — the only collaborator is {@link RuleCache}, whose {@link RuleCache#current()}
 *       is a lock-free reference read; there is no datasource, repository, or network dependency on
 *       this class or anything it touches during {@code evaluate}.
 *   <li><b>Deterministic</b> — the result is a pure function of the {@link AuthContext} and the
 *       current immutable rule set; equal contexts against the same snapshot give equal results.
 *   <li><b>Fast</b> — evaluation is an in-order scan of integer comparisons, stopping at the first
 *       declining rule (single-digit-millisecond p99, see {@code BENCHMARK.md}).
 * </ul>
 *
 * <p>Declines are logged at INFO with the deciding rule id and reason code (no PAN, no card data is
 * present in the context) so QA can trace which rule fired; a clean approve logs at DEBUG only.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuleEvaluator implements RuleEngine {

    private final RuleCache cache;

    @Override
    public RuleResult evaluate(AuthContext context) {
        RuleResult result = cache.current().evaluate(context);
        if (result.isApproved()) {
            log.debug("auth approved account={} mcc={} amount={}", context.account(), context.mcc(), context.amount());
        } else {
            log.info(
                    "auth declined account={} mcc={} amount={} ruleId={} reasonCode={}",
                    context.account(),
                    context.mcc(),
                    context.amount(),
                    result.ruleId(),
                    result.reasonCode());
        }
        return result;
    }
}
