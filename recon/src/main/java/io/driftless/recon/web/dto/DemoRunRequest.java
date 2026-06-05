package io.driftless.recon.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Optional request body for {@code POST /demo/run} (Spec 09). Every field is nullable; the demo
 * runner substitutes a sensible default for any omitted value, so an empty body runs the full fault
 * story. Present values are bounded so the seed-only demo can never be asked to exhaust threads or the
 * database. Plain DTO — never a JPA entity.
 *
 * @param count authorize operations to drive (1..5000)
 * @param concurrency worker threads issuing them (1..64)
 * @param accounts funded accounts to draw against (1..1000)
 * @param fundingMinor minor units funded into each account (1..1_000_000_000_000)
 * @param faultMix named partner fault mix ({@code NONE|TIMEOUT|FAIL_BEFORE_RESPONSE|DECLINE|DUPLICATE|MIXED})
 * @param seed RNG seed making the run reproducible
 */
public record DemoRunRequest(
        @Min(1) @Max(5_000) Integer count,
        @Min(1) @Max(64) Integer concurrency,
        @Min(1) @Max(1_000) Integer accounts,
        @Min(1) @Max(1_000_000_000_000L) Long fundingMinor,
        String faultMix,
        Long seed) {

    /** An all-defaults request, used when the caller sends no body. */
    public static DemoRunRequest empty() {
        return new DemoRunRequest(null, null, null, null, null, null);
    }
}
