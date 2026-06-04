package io.driftless.tokens;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.driftless.tokens.web.dto.CreateTokenRequest;
import io.driftless.tokens.web.dto.TokenResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Integration tests for the {@link io.driftless.tokens.web.TokenController} REST surface against real
 * Postgres (Testcontainers), driven through MockMvc. Covers the {@code Idempotency-Key} contract
 * (missing ⇒ 400, same key + different body ⇒ 409, replay ⇒ original 2xx), the strict state machine
 * mapping (illegal transition ⇒ 422), not-found ⇒ 404, and the status read endpoint.
 */
@SpringBootTest
@Import(PostgresTestContainer.class)
class TokenControllerIT {

    static final Instant NOW = Instant.parse("2026-06-01T10:15:30Z");
    static final String CARD_REF = "card-ref-web-not-a-pan";

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    WebApplicationContext context;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbc;

    private MockMvc mvc;

    private MockMvc mvc() {
        if (mvc == null) {
            mvc = MockMvcBuilders.webAppContextSetup(context).build();
        }
        return mvc;
    }

    private String body(String cardRef, UUID tokenId) throws Exception {
        return objectMapper.writeValueAsString(new CreateTokenRequest(cardRef, tokenId));
    }

    private TokenResponse parse(MvcResult result) throws Exception {
        return objectMapper.readValue(result.getResponse().getContentAsString(), TokenResponse.class);
    }

    private long eventCount(UUID id) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = 'TokenStatusChanged'",
                Long.class,
                id.toString());
    }

    private UUID createToken(String key) throws Exception {
        MvcResult result = mvc().perform(post("/tokens")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(CARD_REF, UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("INACTIVE"))
                .andReturn();
        return parse(result).id();
    }

    @Test
    void createThenActivateReturnsTheUpdatedStatus() throws Exception {
        UUID id = createToken("web-create-1");

        mvc().perform(post("/tokens/{id}/activate", id).header("Idempotency-Key", "web-activate-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.cardRef").value(CARD_REF));

        mvc().perform(get("/tokens/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void aMutatingRequestWithoutAnIdempotencyKeyIs400() throws Exception {
        mvc().perform(post("/tokens")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(CARD_REF, UUID.randomUUID())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aReplayUnderTheSameKeyReturnsTheOriginalResultAndWritesNothingNew() throws Exception {
        String key = "web-replay-1";
        UUID tokenId = UUID.randomUUID();
        String requestBody = body(CARD_REF, tokenId);

        MvcResult first = mvc().perform(post("/tokens")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andReturn();
        long eventsAfterFirst = eventCount(tokenId);

        MvcResult replay = mvc().perform(post("/tokens")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andReturn();

        assertThat(parse(replay)).isEqualTo(parse(first));
        assertThat(eventCount(tokenId)).isEqualTo(eventsAfterFirst);
    }

    @Test
    void theSameKeyWithADifferentBodyIs409() throws Exception {
        String key = "web-conflict-1";
        mvc().perform(post("/tokens")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("card-ref-A", UUID.randomUUID())))
                .andExpect(status().isCreated());

        mvc().perform(post("/tokens")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("card-ref-B", UUID.randomUUID())))
                .andExpect(status().isConflict());
    }

    @Test
    void aFreshIllegalTransitionIs422() throws Exception {
        UUID id = createToken("web-illegal-1");
        mvc().perform(post("/tokens/{id}/activate", id).header("Idempotency-Key", "web-illegal-act"))
                .andExpect(status().isOk());

        // resume of an already-ACTIVE token under a fresh key: the strict state machine rejects it.
        mvc().perform(post("/tokens/{id}/resume", id).header("Idempotency-Key", "web-illegal-resume"))
                .andExpect(status().isUnprocessableEntity());

        mvc().perform(get("/tokens/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void anInvalidCreateBodyIs400() throws Exception {
        mvc().perform(post("/tokens")
                        .header("Idempotency-Key", "web-invalid-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("   ", null)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void transitioningAnUnknownTokenIs404() throws Exception {
        mvc().perform(post("/tokens/{id}/activate", UUID.randomUUID()).header("Idempotency-Key", "web-404-1"))
                .andExpect(status().isNotFound());

        mvc().perform(get("/tokens/{id}", UUID.randomUUID())).andExpect(status().isNotFound());
    }
}
