package io.driftless.rules.internal;

import io.driftless.rules.config.RuleProperties;
import io.driftless.rules.config.RuleProperties.MccMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Compiles authored {@link RuleProperties} into an immutable {@link CompiledRuleSet}.
 *
 * <p>This runs <strong>off the hot path</strong> — only at startup and on a cache refresh — so it can
 * afford to validate (non-blank, unique {@code ruleId}s; sane bounds) and allocate freely. The cost
 * of turning config into integer-comparison predicates is paid here, once per change, not per
 * authorization.
 *
 * <p>A velocity entry may declare a count cap, an amount cap, or both; each enabled facet becomes its
 * own {@link CompiledRule} sharing the authored {@code ruleId}, so a decline still names the rule.
 */
@Slf4j
@Component
public class RuleCompiler {

    /**
     * Validate and compile a point-in-time snapshot of the authored rules.
     *
     * @throws IllegalArgumentException if a rule has a blank or duplicate id (a misconfiguration the
     *     operator must fix; surfaced loudly off the hot path rather than swallowed)
     */
    public CompiledRuleSet compile(RuleProperties properties) {
        List<CompiledRule> compiled = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();

        for (RuleProperties.PerTransactionLimitRule rule : properties.getPerTransactionLimits()) {
            String id = requireUniqueId(rule.getRuleId(), seenIds);
            compiled.add(new CompiledRule.PerTransactionLimit(id, rule.getPriority(), rule.getMaxAmountMinor()));
        }

        for (RuleProperties.PeriodicCapRule rule : properties.getPeriodicCaps()) {
            String id = requireUniqueId(rule.getRuleId(), seenIds);
            compiled.add(new CompiledRule.PeriodicSpendCap(id, rule.getPriority(), rule.getCapAmountMinor()));
        }

        for (RuleProperties.VelocityRule rule : properties.getVelocity()) {
            String id = requireUniqueId(rule.getRuleId(), seenIds);
            boolean any = false;
            if (rule.getMaxCount() > 0) {
                compiled.add(new CompiledRule.VelocityCount(id, rule.getPriority(), rule.getMaxCount()));
                any = true;
            }
            if (rule.getMaxAmountMinor() > 0) {
                compiled.add(new CompiledRule.VelocityAmount(id, rule.getPriority(), rule.getMaxAmountMinor()));
                any = true;
            }
            if (!any) {
                throw new IllegalArgumentException(
                        "velocity rule '" + id + "' must set a positive maxCount and/or maxAmountMinor");
            }
        }

        for (RuleProperties.MccRule rule : properties.getMcc()) {
            String id = requireUniqueId(rule.getRuleId(), seenIds);
            Set<String> codes = Set.copyOf(rule.getCodes());
            compiled.add(
                    rule.getMode() == MccMode.ALLOW
                            ? new CompiledRule.MccAllowList(id, rule.getPriority(), codes)
                            : new CompiledRule.MccBlockList(id, rule.getPriority(), codes));
        }

        CompiledRuleSet ruleSet = new CompiledRuleSet(compiled);
        log.info("compiled {} authored entries into {} active rules", authoredCount(properties), ruleSet.size());
        return ruleSet;
    }

    private static String requireUniqueId(String ruleId, Set<String> seenIds) {
        if (ruleId == null || ruleId.isBlank()) {
            throw new IllegalArgumentException("every rule must declare a non-blank ruleId");
        }
        if (!seenIds.add(ruleId)) {
            throw new IllegalArgumentException("duplicate ruleId '" + ruleId + "' across authored rules");
        }
        return ruleId;
    }

    private static int authoredCount(RuleProperties properties) {
        return properties.getPerTransactionLimits().size()
                + properties.getPeriodicCaps().size()
                + properties.getVelocity().size()
                + properties.getMcc().size();
    }
}
