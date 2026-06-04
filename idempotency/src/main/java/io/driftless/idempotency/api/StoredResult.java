package io.driftless.idempotency.api;

import java.util.Objects;

/**
 * What a guarded operation hands back to the {@link IdempotencyGuard}: the live result value plus its
 * canonical serialized form, which the guard persists into the idempotency record's {@code
 * response_blob}.
 *
 * <p>The caller serializes the value (it owns the wire shape of its own result), so the guard never
 * needs to know the concrete type to <em>store</em> a result. On a replay the guard reconstructs the
 * value from {@code responseJson} into {@code valueType} — which is why the type is captured here:
 * it lets a replay (even in a fresh process after a crash) rebuild the typed value without re-running
 * the side effect.
 *
 * @param value the live operation result, returned as-is on the first (non-replayed) execution
 * @param responseJson the canonical serialized form of {@code value}, stored verbatim and used to
 *     rebuild the value on replay; must not be blank
 * @param valueType the runtime type of {@code value}, used to deserialize {@code responseJson} on
 *     replay
 * @param <T> the operation's result type
 */
public record StoredResult<T>(T value, String responseJson, Class<T> valueType) {

    public StoredResult {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(valueType, "valueType");
        if (responseJson == null || responseJson.isBlank()) {
            throw new IllegalArgumentException("responseJson must not be blank");
        }
    }

    /**
     * Convenience factory that infers {@code valueType} from the value's runtime class.
     *
     * @param value the live operation result (must be non-null so its type can be captured)
     * @param responseJson the canonical serialized form of {@code value}
     */
    @SuppressWarnings("unchecked")
    public static <T> StoredResult<T> of(T value, String responseJson) {
        Objects.requireNonNull(value, "value");
        return new StoredResult<>(value, responseJson, (Class<T>) value.getClass());
    }
}
