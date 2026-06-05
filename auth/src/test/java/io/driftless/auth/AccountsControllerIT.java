package io.driftless.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.driftless.auth.web.dto.FundAccountRequest;
import io.driftless.auth.web.dto.OpenAccountRequest;
import io.driftless.common.id.AccountId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * HTTP-level behaviour of the account-lifecycle + funding + statement surface: idempotency-key
 * handling, the balanced-funding zero-drift invariant, keyset statement paging and the stable
 * machine-readable error codes.
 */
@AutoConfigureRestTestClient
class AccountsControllerIT extends AbstractAuthIT {

    @Autowired
    RestTestClient client;

    // --- open ------------------------------------------------------------------------------------

    @Test
    void openReturns201WithAccountAndReplaysOnSameKey() {
        String key = key();
        Map first = open(new OpenAccountRequest("LIABILITY", CURRENCY, "cardholder-a"), key, HttpStatus.CREATED);
        assertThat(first).containsEntry("type", "LIABILITY").containsEntry("currency", CURRENCY);
        assertThat(first.get("id")).isNotNull();

        // Replay with the same key returns the SAME account (no second account opened).
        Map replay = open(new OpenAccountRequest("LIABILITY", CURRENCY, "cardholder-a"), key, HttpStatus.CREATED);
        assertThat(replay.get("id")).isEqualTo(first.get("id"));
    }

    @Test
    void openDefaultsTypeToLiabilityWhenOmitted() {
        Map body = open(new OpenAccountRequest(null, CURRENCY, "defaulted"), key(), HttpStatus.CREATED);
        assertThat(body).containsEntry("type", "LIABILITY");
    }

    @Test
    void openWithoutIdempotencyKeyIs400WithCode() {
        Map problem = client.post()
                .uri("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new OpenAccountRequest("LIABILITY", CURRENCY, "no-key"))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.BAD_REQUEST)
                .expectBody(Map.class)
                .returnResult()
                .getResponseBody();
        assertThat(problem).containsEntry("code", "MISSING_IDEMPOTENCY_KEY");
    }

    @Test
    void openWithUnknownCurrencyIs400() {
        client.post()
                .uri("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key())
                .body(new OpenAccountRequest("LIABILITY", "ZZZ", "bad-ccy"))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void openWithUnknownTypeIs400() {
        client.post()
                .uri("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key())
                .body(new OpenAccountRequest("WALLET", CURRENCY, "bad-type"))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void sameOpenKeyDifferentBodyIs409WithCode() {
        String key = key();
        open(new OpenAccountRequest("LIABILITY", CURRENCY, "first"), key, HttpStatus.CREATED);
        Map problem = client.post()
                .uri("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key)
                .body(new OpenAccountRequest("LIABILITY", CURRENCY, "different-name"))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.CONFLICT)
                .expectBody(Map.class)
                .returnResult()
                .getResponseBody();
        assertThat(problem).containsEntry("code", "IDEMPOTENCY_CONFLICT");
    }

    // --- funding ---------------------------------------------------------------------------------

    @Test
    void fundingPostsABalancedTransactionAndKeepsGlobalSumZero() {
        UUID id = openAccountId("to-fund");

        Map receipt = fund(id, 100_000, key(), HttpStatus.OK);
        assertThat(receipt.get("transactionId")).isNotNull();
        assertThat(receipt.get("postedAt")).isNotNull();

        // The funded account's posted balance reflects the load (debit-positive convention).
        assertThat(posted(AccountId.of(id))).isEqualTo(100_000);

        // The signature invariant: the global signed sum of every journal entry is exactly zero.
        assertThat(journalEntries.globalSignedSumMinor(CURRENCY)).isZero();
    }

    @Test
    void fundingReplaysOnSameKeyWithoutDoublePosting() {
        UUID id = openAccountId("replay-fund");
        String key = key();
        Map first = fund(id, 25_000, key, HttpStatus.OK);
        Map replay = fund(id, 25_000, key, HttpStatus.OK);

        assertThat(replay.get("transactionId")).isEqualTo(first.get("transactionId"));
        assertThat(posted(AccountId.of(id))).isEqualTo(25_000); // not 50_000
        assertThat(journalEntries.globalSignedSumMinor(CURRENCY)).isZero();
    }

    @Test
    void fundingUnknownAccountIs404WithCode() {
        Map problem = client.post()
                .uri("/accounts/{id}/funding", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key())
                .body(new FundAccountRequest(10_000, CURRENCY))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.NOT_FOUND)
                .expectBody(Map.class)
                .returnResult()
                .getResponseBody();
        assertThat(problem).containsEntry("code", "ACCOUNT_NOT_FOUND");
    }

    @Test
    void fundingWithMismatchedCurrencyIs422WithCode() {
        UUID id = openAccountId("usd-only");
        Map problem = client.post()
                .uri("/accounts/{id}/funding", id)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key())
                .body(new FundAccountRequest(10_000, "EUR"))
                .exchange()
                .expectStatus()
                .isEqualTo(org.springframework.http.HttpStatusCode.valueOf(422))
                .expectBody(Map.class)
                .returnResult()
                .getResponseBody();
        assertThat(problem).containsEntry("code", "CURRENCY_MISMATCH");
    }

    @Test
    void fundingWithoutIdempotencyKeyIs400() {
        UUID id = openAccountId("no-key-fund");
        client.post()
                .uri("/accounts/{id}/funding", id)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new FundAccountRequest(10_000, CURRENCY))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void fundingWithNonPositiveAmountIs400WithCode() {
        UUID id = openAccountId("bad-amount");
        Map problem = client.post()
                .uri("/accounts/{id}/funding", id)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key())
                .body(new FundAccountRequest(0, CURRENCY)) // @Min(1) -> MethodArgumentNotValidException
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.BAD_REQUEST)
                .expectBody(Map.class)
                .returnResult()
                .getResponseBody();
        assertThat(problem).containsEntry("code", "VALIDATION_FAILED");
    }

    // --- read + statement ------------------------------------------------------------------------

    @Test
    void getAccountReturnsDetailsAnd404ForUnknown() {
        UUID id = openAccountId("readable");
        Map body = client.get()
                .uri("/accounts/{id}", id)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Map.class)
                .returnResult()
                .getResponseBody();
        assertThat(body).containsEntry("name", "readable").containsEntry("currency", CURRENCY);

        client.get()
                .uri("/accounts/{id}", UUID.randomUUID())
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void statementPagesEveryEntryViaKeysetCursor() {
        UUID id = openAccountId("statement");
        // Six funding loads => six debit entries on the account.
        for (int i = 0; i < 6; i++) {
            fund(id, 1_000 + i, key(), HttpStatus.OK);
        }

        List<Object> seen = new ArrayList<>();
        String after = null;
        int pages = 0;
        while (true) {
            String uri = after == null
                    ? "/accounts/" + id + "/statement?limit=2"
                    : "/accounts/" + id + "/statement?limit=2&after=" + after;
            Map page = client.get()
                    .uri(uri)
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(Map.class)
                    .returnResult()
                    .getResponseBody();
            List<Map<String, Object>> entries = (List<Map<String, Object>>) page.get("entries");
            entries.forEach(e -> seen.add(e.get("id")));
            pages++;
            after = (String) page.get("nextCursor");
            if (after == null) {
                break;
            }
            assertThat(pages).isLessThan(10); // guard against a non-terminating cursor
        }

        assertThat(seen).doesNotHaveDuplicates();
        assertThat(seen).hasSize(6);
    }

    @Test
    void fundingSameKeyDifferentAmountIs409AndDoesNotPostAgain() {
        UUID id = openAccountId("fund-conflict");
        String key = key();
        Map first = fund(id, 30_000, key, HttpStatus.OK);
        assertThat(first.get("transactionId")).isNotNull();

        // Same idempotency key, different amount => request-hash mismatch => 409 IDEMPOTENCY_CONFLICT.
        Map problem = client.post()
                .uri("/accounts/{id}/funding", id)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key)
                .body(new FundAccountRequest(99_999, CURRENCY))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.CONFLICT)
                .expectBody(Map.class)
                .returnResult()
                .getResponseBody();
        assertThat(problem).containsEntry("code", "IDEMPOTENCY_CONFLICT");

        // No second post happened: the balance still reflects only the first funding load.
        assertThat(posted(AccountId.of(id))).isEqualTo(30_000);
        assertThat(journalEntries.globalSignedSumMinor(CURRENCY)).isZero();
    }

    @Test
    void statementWithMalformedCursorIs400WithCode() {
        UUID id = openAccountId("bad-cursor");
        // "Zm9v" is valid base64url ("foo") but not a numeric sequence, so the opaque cursor rejects it
        // safely as a malformed token rather than mis-seeking.
        Map problem = client.get()
                .uri("/accounts/{id}/statement?after=Zm9v", id)
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.BAD_REQUEST)
                .expectBody(Map.class)
                .returnResult()
                .getResponseBody();
        assertThat(problem).containsEntry("code", "VALIDATION_FAILED");
    }

    @Test
    void statementForUnknownAccountIs404() {
        client.get()
                .uri("/accounts/{id}/statement", UUID.randomUUID())
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // --- helpers ---------------------------------------------------------------------------------

    private UUID openAccountId(String name) {
        Map body = open(new OpenAccountRequest("LIABILITY", CURRENCY, name), key(), HttpStatus.CREATED);
        return UUID.fromString((String) body.get("id"));
    }

    private Map open(OpenAccountRequest request, String key, HttpStatus expected) {
        return client.post()
                .uri("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key)
                .body(request)
                .exchange()
                .expectStatus()
                .isEqualTo(expected)
                .expectBody(Map.class)
                .returnResult()
                .getResponseBody();
    }

    private Map fund(UUID id, long amountMinor, String key, HttpStatus expected) {
        return client.post()
                .uri("/accounts/{id}/funding", id)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key)
                .body(new FundAccountRequest(amountMinor, CURRENCY))
                .exchange()
                .expectStatus()
                .isEqualTo(expected)
                .expectBody(Map.class)
                .returnResult()
                .getResponseBody();
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}
