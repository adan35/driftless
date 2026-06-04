package io.driftless.rules.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Rules authored as data: the externalized source the engine compiles into its in-memory form. Bound
 * from {@code driftless.rules.*} (YAML/properties) and re-read by the cache off the hot path, so a
 * rule change takes effect after a refresh with no restart.
 *
 * <p>This is a mutable Spring-binding POJO by necessity (relaxed binding needs setters); it is
 * <em>never</em> read on the hot path. {@link io.driftless.rules.internal.RuleCompiler} turns a
 * point-in-time copy of it into an immutable {@link io.driftless.rules.internal.CompiledRuleSet}.
 *
 * <p>Each entry carries a {@code ruleId} (surfaced on a decline) and a {@code priority} (lower runs
 * first); thresholds are integer <strong>minor units</strong> — no floating point ever defines a
 * money limit.
 */
@ConfigurationProperties(prefix = "driftless.rules")
public class RuleProperties {

    /**
     * How often the cache reloads-and-recompiles off the hot path. A non-positive value disables the
     * scheduled refresh (an explicit {@code refresh()} still works). Default: 30s.
     */
    private Duration refreshInterval = Duration.ofSeconds(30);

    /**
     * Length of the rolling window the velocity store counts over (e.g. authorizations within the last
     * {@code velocityWindow}). Read by the store, not compiled into rules. Default: 24h.
     */
    private Duration velocityWindow = Duration.ofHours(24);

    private List<PerTransactionLimitRule> perTransactionLimits = new ArrayList<>();
    private List<PeriodicCapRule> periodicCaps = new ArrayList<>();
    private List<VelocityRule> velocity = new ArrayList<>();
    private List<MccRule> mcc = new ArrayList<>();

    public Duration getRefreshInterval() {
        return refreshInterval;
    }

    public void setRefreshInterval(Duration refreshInterval) {
        this.refreshInterval = refreshInterval;
    }

    public Duration getVelocityWindow() {
        return velocityWindow;
    }

    public void setVelocityWindow(Duration velocityWindow) {
        this.velocityWindow = velocityWindow;
    }

    public List<PerTransactionLimitRule> getPerTransactionLimits() {
        return perTransactionLimits;
    }

    public void setPerTransactionLimits(List<PerTransactionLimitRule> perTransactionLimits) {
        this.perTransactionLimits = perTransactionLimits;
    }

    public List<PeriodicCapRule> getPeriodicCaps() {
        return periodicCaps;
    }

    public void setPeriodicCaps(List<PeriodicCapRule> periodicCaps) {
        this.periodicCaps = periodicCaps;
    }

    public List<VelocityRule> getVelocity() {
        return velocity;
    }

    public void setVelocity(List<VelocityRule> velocity) {
        this.velocity = velocity;
    }

    public List<MccRule> getMcc() {
        return mcc;
    }

    public void setMcc(List<MccRule> mcc) {
        this.mcc = mcc;
    }

    /** Per-transaction maximum: declines when a single authorization exceeds {@code maxAmountMinor}. */
    public static class PerTransactionLimitRule {
        private String ruleId;
        private int priority;
        private long maxAmountMinor;

        public String getRuleId() {
            return ruleId;
        }

        public void setRuleId(String ruleId) {
            this.ruleId = ruleId;
        }

        public int getPriority() {
            return priority;
        }

        public void setPriority(int priority) {
            this.priority = priority;
        }

        public long getMaxAmountMinor() {
            return maxAmountMinor;
        }

        public void setMaxAmountMinor(long maxAmountMinor) {
            this.maxAmountMinor = maxAmountMinor;
        }
    }

    /**
     * Periodic spend cap: declines when windowed spend-so-far plus this authorization would exceed
     * {@code capAmountMinor}. Spend-so-far comes from the velocity snapshot.
     */
    public static class PeriodicCapRule {
        private String ruleId;
        private int priority;
        private long capAmountMinor;

        public String getRuleId() {
            return ruleId;
        }

        public void setRuleId(String ruleId) {
            this.ruleId = ruleId;
        }

        public int getPriority() {
            return priority;
        }

        public void setPriority(int priority) {
            this.priority = priority;
        }

        public long getCapAmountMinor() {
            return capAmountMinor;
        }

        public void setCapAmountMinor(long capAmountMinor) {
            this.capAmountMinor = capAmountMinor;
        }
    }

    /**
     * Velocity rule over the rolling window: set {@code maxCount} for a count cap, {@code
     * maxAmountMinor} for a summed-amount cap, or both. A non-positive bound disables that facet.
     */
    public static class VelocityRule {
        private String ruleId;
        private int priority;
        private int maxCount;
        private long maxAmountMinor;

        public String getRuleId() {
            return ruleId;
        }

        public void setRuleId(String ruleId) {
            this.ruleId = ruleId;
        }

        public int getPriority() {
            return priority;
        }

        public void setPriority(int priority) {
            this.priority = priority;
        }

        public int getMaxCount() {
            return maxCount;
        }

        public void setMaxCount(int maxCount) {
            this.maxCount = maxCount;
        }

        public long getMaxAmountMinor() {
            return maxAmountMinor;
        }

        public void setMaxAmountMinor(long maxAmountMinor) {
            this.maxAmountMinor = maxAmountMinor;
        }
    }

    /** MCC allow/block list. {@code mode} is {@code BLOCK} (deny listed) or {@code ALLOW} (deny unlisted). */
    public static class MccRule {
        private String ruleId;
        private int priority;
        private MccMode mode = MccMode.BLOCK;
        private List<String> codes = new ArrayList<>();

        public String getRuleId() {
            return ruleId;
        }

        public void setRuleId(String ruleId) {
            this.ruleId = ruleId;
        }

        public int getPriority() {
            return priority;
        }

        public void setPriority(int priority) {
            this.priority = priority;
        }

        public MccMode getMode() {
            return mode;
        }

        public void setMode(MccMode mode) {
            this.mode = mode;
        }

        public List<String> getCodes() {
            return codes;
        }

        public void setCodes(List<String> codes) {
            this.codes = codes;
        }
    }

    /** Whether an {@link MccRule} denies the listed codes or denies everything but the listed codes. */
    public enum MccMode {
        BLOCK,
        ALLOW
    }
}
