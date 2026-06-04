package io.driftless.rules;

import io.driftless.common.id.AccountId;
import io.driftless.common.money.Money;
import io.driftless.rules.api.AuthContext;
import io.driftless.rules.api.VelocitySnapshot;
import io.driftless.rules.config.RuleProperties;
import java.time.Instant;
import java.util.Currency;
import java.util.List;

/** Shared builders for rule-engine tests: clean {@link AuthContext} and authored {@link RuleProperties}. */
public final class RuleFixtures {

    public static final Currency USD = Currency.getInstance("USD");
    public static final AccountId ACCOUNT = AccountId.of("11111111-1111-1111-1111-111111111111");
    public static final Instant AT = Instant.parse("2026-06-01T12:00:00Z");
    public static final String GROCERIES_MCC = "5411";
    public static final String GAMBLING_MCC = "7995";

    private RuleFixtures() {}

    public static Money usd(long minor) {
        return Money.of(minor, USD);
    }

    /** A clean context: $50.00 at a groceries MCC, $1,000.00 available, empty velocity window. */
    public static AuthContext cleanContext() {
        return context(usd(5_000), GROCERIES_MCC, usd(100_000), new VelocitySnapshot(0, usd(0)));
    }

    public static AuthContext context(Money amount, String mcc, Money availableBalance, VelocitySnapshot velocity) {
        return new AuthContext(ACCOUNT, amount, mcc, "merchant-1", AT, availableBalance, velocity);
    }

    /** A per-transaction limit rule capping a single authorization at {@code maxMinor}. */
    public static RuleProperties.PerTransactionLimitRule perTxnLimit(String id, int priority, long maxMinor) {
        RuleProperties.PerTransactionLimitRule rule = new RuleProperties.PerTransactionLimitRule();
        rule.setRuleId(id);
        rule.setPriority(priority);
        rule.setMaxAmountMinor(maxMinor);
        return rule;
    }

    /** A velocity rule capping the rolling-window count at {@code maxCount}. */
    public static RuleProperties.VelocityRule velocityCount(String id, int priority, int maxCount) {
        RuleProperties.VelocityRule rule = new RuleProperties.VelocityRule();
        rule.setRuleId(id);
        rule.setPriority(priority);
        rule.setMaxCount(maxCount);
        return rule;
    }

    /** A velocity rule capping the rolling-window summed amount at {@code maxMinor}. */
    public static RuleProperties.VelocityRule velocityAmount(String id, int priority, long maxMinor) {
        RuleProperties.VelocityRule rule = new RuleProperties.VelocityRule();
        rule.setRuleId(id);
        rule.setPriority(priority);
        rule.setMaxAmountMinor(maxMinor);
        return rule;
    }

    /** A periodic spend cap over the window of {@code capMinor}. */
    public static RuleProperties.PeriodicCapRule periodicCap(String id, int priority, long capMinor) {
        RuleProperties.PeriodicCapRule rule = new RuleProperties.PeriodicCapRule();
        rule.setRuleId(id);
        rule.setPriority(priority);
        rule.setCapAmountMinor(capMinor);
        return rule;
    }

    /** An MCC block-list rule denying the given codes. */
    public static RuleProperties.MccRule mccBlock(String id, int priority, String... codes) {
        RuleProperties.MccRule rule = new RuleProperties.MccRule();
        rule.setRuleId(id);
        rule.setPriority(priority);
        rule.setMode(RuleProperties.MccMode.BLOCK);
        rule.setCodes(List.of(codes));
        return rule;
    }

    /** An MCC allow-list rule denying everything but the given codes. */
    public static RuleProperties.MccRule mccAllow(String id, int priority, String... codes) {
        RuleProperties.MccRule rule = new RuleProperties.MccRule();
        rule.setRuleId(id);
        rule.setPriority(priority);
        rule.setMode(RuleProperties.MccMode.ALLOW);
        rule.setCodes(List.of(codes));
        return rule;
    }
}
