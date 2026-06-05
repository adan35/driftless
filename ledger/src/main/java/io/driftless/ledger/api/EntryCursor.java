package io.driftless.ledger.api;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * An opaque keyset (seek) cursor into an account's append-only entry stream.
 *
 * <p>The cursor carries the global, immutable, strictly-monotonic {@code entry_seq} of the last entry
 * already returned to the caller. The next page is everything with a <em>greater</em> {@code
 * entry_seq}, so paging is gap-free and duplicate-free for entries committed in sequence order, and
 * never pays the scan-from-the-start cost of {@code OFFSET}. Because Postgres sequences are
 * non-transactional, a row assigned a lower {@code entry_seq} may commit after a higher-sequence row;
 * a page whose cursor has already advanced past the higher sequence can transiently omit that
 * still-in-flight lower row. No entry is ever lost or duplicated in the durable ledger — a fresh read
 * from {@link #START} always returns the complete set once writers commit.
 *
 * <p>{@link #START} (sequence {@code 0}) is the beginning of the stream; {@code entry_seq} values are
 * {@code >= 1}. {@link #token()} renders the cursor as a URL-safe opaque string for a REST {@code
 * after=} parameter; {@link #parse(String)} is its inverse.
 *
 * <p><strong>Additive contract.</strong> This type was introduced alongside {@link
 * Ledger#entriesAfter} during the API-refinement pass; it adds to, and does not alter, any existing
 * frozen {@code io.driftless.ledger.api} shape.
 *
 * @param afterSequence the last-seen entry's global sequence; {@code 0} means "from the beginning"
 */
public record EntryCursor(long afterSequence) {

    /** The start of the stream: a page from {@code START} returns the oldest entries first. */
    public static final EntryCursor START = new EntryCursor(0L);

    public EntryCursor {
        if (afterSequence < 0L) {
            throw new IllegalArgumentException("afterSequence must be >= 0 (was " + afterSequence + ")");
        }
    }

    /** A cursor positioned just after the entry with the given global sequence. */
    public static EntryCursor after(long sequence) {
        return new EntryCursor(sequence);
    }

    /** {@code true} when this cursor is the beginning of the stream. */
    public boolean isStart() {
        return afterSequence == 0L;
    }

    /** URL-safe opaque token for a REST {@code after=} parameter. */
    public String token() {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(Long.toString(afterSequence).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Parse an opaque token produced by {@link #token()} back into a cursor. A {@code null}/blank
     * token is treated as {@link #START}; a malformed token is rejected with {@link
     * IllegalArgumentException}.
     */
    public static EntryCursor parse(String token) {
        if (token == null || token.isBlank()) {
            return START;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            return after(Long.parseLong(decoded));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("malformed entry cursor token");
        }
    }
}
