package io.driftless.auth.internal.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed configuration for the Spec 03 saga, bound from {@code auth.*}. Replaces scattered {@code
 * @Value} reads with one validated POJO.
 *
 * <ul>
 *   <li>{@code auth.partner.*} — where the partner-simulator lives and the hard, bounded timeouts the
 *       saga calls it under. A read-timeout breach is what triggers the compensating reversal.
 *   <li>{@code auth.recovery.*} — the crash-recovery sweep: how stale an in-flight authorization must
 *       be before the sweep adopts it, and how often the sweep runs.
 * </ul>
 */
@ConfigurationProperties(prefix = "auth")
public class AuthProperties {

    private final Partner partner = new Partner();
    private final Recovery recovery = new Recovery();

    public Partner getPartner() {
        return partner;
    }

    public Recovery getRecovery() {
        return recovery;
    }

    /** Partner-simulator location and the bounded-timeout budget for each leg. */
    public static class Partner {

        /** Base URL of the partner-simulator, e.g. {@code http://partner-simulator:8080}. */
        private String baseUrl = "http://localhost:8081";

        /** Hard cap on establishing the TCP connection to the partner. */
        private Duration connectTimeout = Duration.ofMillis(500);

        /**
         * Hard cap on waiting for the partner's response. A breach is treated as "no response" and
         * drives the compensating reversal — the literal bounded-timeout fix.
         */
        private Duration readTimeout = Duration.ofMillis(800);

        /** How many times the compensating reversal retries the (idempotent) partner reverse leg. */
        private int compensationMaxAttempts = 3;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getReadTimeout() {
            return readTimeout;
        }

        public void setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }

        public int getCompensationMaxAttempts() {
            return compensationMaxAttempts;
        }

        public void setCompensationMaxAttempts(int compensationMaxAttempts) {
            this.compensationMaxAttempts = compensationMaxAttempts;
        }
    }

    /** Crash-recovery sweep tuning. */
    public static class Recovery {

        /**
         * How long an authorization may sit in an in-flight state ({@code AUTHORIZING}/{@code
         * COMPENSATING}) before the sweep adopts it and drives it to terminal. Must exceed the partner
         * read timeout so the normal in-line finalize wins the common case.
         */
        private Duration stuckAfter = Duration.ofSeconds(5);

        /**
         * How long to wait, measured from the authorize request, for a <em>late</em> partner response
         * before a compensation may declare "no partner side effect" and reach terminal {@code REVERSED}.
         * It must exceed the partner read timeout so a timeout-then-late-approve is reconciled (the
         * partner reverse is sent) rather than prematurely sealed. Should be {@code <= stuck-after} so a
         * row the sweep adopts has already passed its grace.
         */
        private Duration partnerGrace = Duration.ofSeconds(2);

        /** Fixed delay between scheduled sweeps. */
        private Duration pollDelay = Duration.ofSeconds(10);

        public Duration getStuckAfter() {
            return stuckAfter;
        }

        public void setStuckAfter(Duration stuckAfter) {
            this.stuckAfter = stuckAfter;
        }

        public Duration getPartnerGrace() {
            return partnerGrace;
        }

        public void setPartnerGrace(Duration partnerGrace) {
            this.partnerGrace = partnerGrace;
        }

        public Duration getPollDelay() {
            return pollDelay;
        }

        public void setPollDelay(Duration pollDelay) {
            this.pollDelay = pollDelay;
        }
    }
}
