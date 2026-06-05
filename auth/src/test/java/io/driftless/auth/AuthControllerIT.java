package io.driftless.auth;

import io.driftless.auth.web.dto.AuthorizeRequest;
import io.driftless.common.id.AccountId;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

/** HTTP-level behaviour: idempotency-key handling, status codes, and the DTO surface. */
@AutoConfigureRestTestClient
class AuthControllerIT extends AbstractAuthIT {

    @Autowired
    RestTestClient client;

    @Test
    void authorizeReturns201WithIdAndStatus() {
        AccountId account = openFundedAccount(100_000);
        Map bodyMap = authorize(account, 30_000, APPROVE_MCC, key(), HttpStatus.CREATED);
        org.assertj.core.api.Assertions.assertThat(bodyMap).containsEntry("status", "AUTHORIZED");
        org.assertj.core.api.Assertions.assertThat(bodyMap.get("authId")).isNotNull();
    }

    @Test
    void mutatingRequestWithoutIdempotencyKeyIs400() {
        AccountId account = openFundedAccount(100_000);
        client.post()
                .uri("/authorizations")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body(account, 30_000, APPROVE_MCC))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void sameKeyDifferentBodyIs409() {
        AccountId account = openFundedAccount(100_000);
        String key = key();
        authorize(account, 30_000, APPROVE_MCC, key, HttpStatus.CREATED);

        client.post()
                .uri("/authorizations")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key)
                .body(body(account, 40_000, APPROVE_MCC))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void replayWithSameKeyReturnsTheOriginalAuthorization() {
        AccountId account = openFundedAccount(100_000);
        String key = key();
        Map first = authorize(account, 30_000, APPROVE_MCC, key, HttpStatus.CREATED);
        Map replay = authorize(account, 30_000, APPROVE_MCC, key, HttpStatus.CREATED);

        org.assertj.core.api.Assertions.assertThat(replay.get("authId")).isEqualTo(first.get("authId"));
    }

    @Test
    void captureDrivesStatusAndBalanceEndpointReflectsHolds() {
        AccountId account = openFundedAccount(100_000);
        String authId = (String) authorize(account, 30_000, APPROVE_MCC, key(), HttpStatus.CREATED)
                .get("authId");

        Map balance = client.get()
                .uri("/accounts/{id}/balance", account.value())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Map.class)
                .returnResult()
                .getResponseBody();
        org.assertj.core.api.Assertions.assertThat(((Number) balance.get("posted")).longValue())
                .isEqualTo(100_000);
        org.assertj.core.api.Assertions.assertThat(((Number) balance.get("available")).longValue())
                .isEqualTo(70_000);

        Map captured = client.post()
                .uri("/authorizations/{id}/capture", authId)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key())
                .body(Map.of("amountMinor", 30_000))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Map.class)
                .returnResult()
                .getResponseBody();
        org.assertj.core.api.Assertions.assertThat(captured).containsEntry("status", "CAPTURED");

        Map fullState = client.get()
                .uri("/authorizations/{id}", authId)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Map.class)
                .returnResult()
                .getResponseBody();
        org.assertj.core.api.Assertions.assertThat(fullState).containsEntry("status", "CAPTURED");
        org.assertj.core.api.Assertions.assertThat(fullState.get("partnerRef")).isNotNull();
    }

    @Test
    void reverseEndpointReversesAnAuthorization() {
        AccountId account = openFundedAccount(100_000);
        String authId = (String) authorize(account, 30_000, APPROVE_MCC, key(), HttpStatus.CREATED)
                .get("authId");

        Map reversed = client.post()
                .uri("/authorizations/{id}/reverse", authId)
                .header("Idempotency-Key", key())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Map.class)
                .returnResult()
                .getResponseBody();

        org.assertj.core.api.Assertions.assertThat(reversed).containsEntry("status", "REVERSED");
        org.assertj.core.api.Assertions.assertThat(activeHoldTotal(account)).isZero();
    }

    @Test
    void unknownAuthorizationGetIs404() {
        client.get()
                .uri("/authorizations/{id}", UUID.randomUUID())
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void authorizeWithUnknownTokenIs404() {
        AccountId account = openFundedAccount(100_000);
        AuthorizeRequest req = new AuthorizeRequest(
                account.value().toString(),
                30_000,
                CURRENCY,
                APPROVE_MCC,
                MERCHANT,
                UUID.randomUUID().toString());
        client.post()
                .uri("/authorizations")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key())
                .body(req)
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void authorizeAgainstUnknownAccountIs422() {
        AuthorizeRequest req =
                new AuthorizeRequest(UUID.randomUUID().toString(), 30_000, CURRENCY, APPROVE_MCC, MERCHANT, null);
        client.post()
                .uri("/authorizations")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key())
                .body(req)
                .exchange()
                .expectStatus()
                .isEqualTo(org.springframework.http.HttpStatusCode.valueOf(422));
    }

    @Test
    void invalidBodyIs400() {
        AccountId account = openFundedAccount(100_000);
        // Blank currency violates @NotBlank/@Size -> MethodArgumentNotValidException -> 400.
        AuthorizeRequest req =
                new AuthorizeRequest(account.value().toString(), 30_000, "", APPROVE_MCC, MERCHANT, null);
        client.post()
                .uri("/authorizations")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key())
                .body(req)
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void captureForMoreThanAuthorizedIs400() {
        AccountId account = openFundedAccount(100_000);
        String authId = (String) authorize(account, 30_000, APPROVE_MCC, key(), HttpStatus.CREATED)
                .get("authId");
        client.post()
                .uri("/authorizations/{id}/capture", authId)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key())
                .body(Map.of("amountMinor", 50_000))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void captureAfterReverseIs409() {
        AccountId account = openFundedAccount(100_000);
        String authId = (String) authorize(account, 30_000, APPROVE_MCC, key(), HttpStatus.CREATED)
                .get("authId");
        client.post()
                .uri("/authorizations/{id}/reverse", authId)
                .header("Idempotency-Key", key())
                .exchange()
                .expectStatus()
                .isOk();
        client.post()
                .uri("/authorizations/{id}/capture", authId)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key())
                .body(Map.of("amountMinor", 30_000))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.CONFLICT);
    }

    private Map authorize(AccountId account, long amount, String mcc, String key, HttpStatus expected) {
        return client.post()
                .uri("/authorizations")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key)
                .body(body(account, amount, mcc))
                .exchange()
                .expectStatus()
                .isEqualTo(expected)
                .expectBody(Map.class)
                .returnResult()
                .getResponseBody();
    }

    private static AuthorizeRequest body(AccountId account, long amount, String mcc) {
        return new AuthorizeRequest(account.value().toString(), amount, CURRENCY, mcc, MERCHANT, null);
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}
