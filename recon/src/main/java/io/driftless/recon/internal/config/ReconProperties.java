package io.driftless.recon.internal.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed configuration for the Spec 07 reconciliation + reliability module, bound from {@code
 * driftless.recon.*}. Replaces scattered {@code @Value} reads with one validated POJO.
 *
 * <ul>
 *   <li>{@code driftless.recon.schedule.*} — the continuous balance-proof job's cadence and whether
 *       it runs on a timer at all (tests disable the timer and drive the run directly).
 *   <li>{@code driftless.recon.outbox-stuck-after} — how old a {@code PENDING} outbox event may be
 *       before the {@code OUTBOX_CONSISTENCY} check flags it as a stuck relay.
 *   <li>{@code driftless.recon.partner.*} — the partner-simulator control-plane base URL the
 *       fault-injection harness drives.
 * </ul>
 */
@ConfigurationProperties(prefix = "driftless.recon")
public class ReconProperties {

    /** Fixed delay between scheduled reconciliation runs. */
    private Duration scheduleInterval = Duration.ofMinutes(1);

    /**
     * How old a {@code PENDING} outbox event may be before it is treated as a stuck relay (a
     * reliability defect). Must comfortably exceed the relay's poll delay so a freshly appended event
     * mid-relay is never mistaken for stuck.
     */
    private Duration outboxStuckAfter = Duration.ofSeconds(30);

    /** Max number of offending entities surfaced per failed check (keeps a result row bounded). */
    private int maxOffendersPerCheck = 50;

    private final Partner partner = new Partner();

    public Duration getScheduleInterval() {
        return scheduleInterval;
    }

    public void setScheduleInterval(Duration scheduleInterval) {
        this.scheduleInterval = scheduleInterval;
    }

    public Duration getOutboxStuckAfter() {
        return outboxStuckAfter;
    }

    public void setOutboxStuckAfter(Duration outboxStuckAfter) {
        this.outboxStuckAfter = outboxStuckAfter;
    }

    public int getMaxOffendersPerCheck() {
        return maxOffendersPerCheck;
    }

    public void setMaxOffendersPerCheck(int maxOffendersPerCheck) {
        this.maxOffendersPerCheck = maxOffendersPerCheck;
    }

    public Partner getPartner() {
        return partner;
    }

    /** Partner-simulator control-plane location the fault-injection harness drives over HTTP. */
    public static class Partner {

        /** Base URL of the partner-simulator's control plane, e.g. {@code http://partner-simulator:8080}. */
        private String controlBaseUrl = "http://localhost:8081";

        /** Hard cap on establishing the TCP connection to the control plane. */
        private Duration connectTimeout = Duration.ofMillis(500);

        /** Hard cap on waiting for a control-plane response. */
        private Duration readTimeout = Duration.ofSeconds(2);

        public String getControlBaseUrl() {
            return controlBaseUrl;
        }

        public void setControlBaseUrl(String controlBaseUrl) {
            this.controlBaseUrl = controlBaseUrl;
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
    }
}
