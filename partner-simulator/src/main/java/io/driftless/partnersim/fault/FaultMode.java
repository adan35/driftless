package io.driftless.partnersim.fault;

/**
 * The misbehaviours the simulator can inject on demand. Each value models a distinct failure the
 * auth saga (Spec 03) claims to survive; Spec 07 drives these to prove "injected downstream failures
 * never produce drift".
 */
public enum FaultMode {

    /** Behave normally: perform the side effect and return a successful response. */
    NONE,

    /** Add a fixed (optionally jittered) delay before responding normally. Models an SLA mismatch. */
    LATENCY,

    /**
     * Perform the side effect server-side, record it, then fail with a 500 WITHOUT a successful
     * response. The canonical drift trigger: the partner did the work but the caller never learns it.
     */
    FAIL_BEFORE_RESPONSE,

    /**
     * Delay beyond the caller's bounded timeout so the caller's timeout path fires. The work is NOT
     * performed (the request is held, then a late response is produced for inspection).
     */
    TIMEOUT,

    /** Return an approve=false decline at a configured probability (seedable / deterministic). */
    DECLINE,

    /** Return an HTTP 500 error at a configured probability (seedable / deterministic). */
    ERROR_RATE,

    /**
     * Perform the side effect once but emit a duplicate response marker so a caller / test can prove
     * the simulator answered twice for one request id.
     */
    DUPLICATE,

    /**
     * Respond after a delay that may land after the saga has already compensated (a late response).
     * Distinct from {@link #TIMEOUT} in intent: the work IS performed and a normal (late) response is
     * returned.
     */
    LATE_RESPONSE
}
