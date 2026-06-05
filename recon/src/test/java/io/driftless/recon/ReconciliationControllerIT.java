package io.driftless.recon;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.driftless.common.id.AccountId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * The thin REST surface: on-demand run, latest, by-id, and the RCA report — the reads Spec 08's
 * dashboard consumes. Driven over HTTP against the random-port server.
 */
@AutoConfigureRestTestClient
class ReconciliationControllerIT extends AbstractReconIT {

    @Autowired
    RestTestClient client;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void runLatestFindAndRcaAreServedOverHttp() throws Exception {
        AccountId account = openFundedAccount(100_000);
        injectUnbalancedEntry(account, 4_321);

        // POST /reconciliation/run -> a fresh, persisted, failing result (we injected drift).
        String runBodyJson = client.post()
                .uri("/reconciliation/run")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        JsonNode runBody = mapper.readTree(runBodyJson);
        assertThat(runBody.get("passed").asBoolean()).isFalse();
        assertThat(runBody.get("totalDriftMinor").asLong()).isEqualTo(4_321L);
        String id = runBody.get("id").asText();

        // GET /reconciliation/latest -> the same run.
        String latestJson = client.get()
                .uri("/reconciliation/latest")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        assertThat(mapper.readTree(latestJson).get("id").asText()).isEqualTo(id);

        // GET /reconciliation/{id} -> the run by id.
        client.get().uri("/reconciliation/" + id).exchange().expectStatus().isOk();

        // GET /reconciliation/{id}/rca -> the RCA report naming the offender + candidate cause.
        String rcaJson = client.get()
                .uri("/reconciliation/" + id + "/rca")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        JsonNode rcaBody = mapper.readTree(rcaJson);
        assertThat(rcaBody.get("drifted").asBoolean()).isTrue();
        assertThat(rcaBody.get("candidateCause").asText()).containsIgnoringCase("unbalanced");
    }

    @Test
    void unknownReconciliationIdIs404() {
        client.get()
                .uri("/reconciliation/" + UUID.randomUUID())
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.NOT_FOUND);

        client.get()
                .uri("/reconciliation/" + UUID.randomUUID() + "/rca")
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
