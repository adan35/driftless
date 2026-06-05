package io.driftless.auth.web;

import io.driftless.auth.internal.AccountService;
import io.driftless.auth.internal.FundingReceipt;
import io.driftless.auth.web.dto.AccountResponse;
import io.driftless.auth.web.dto.FundAccountRequest;
import io.driftless.auth.web.dto.FundingResponse;
import io.driftless.auth.web.dto.OpenAccountRequest;
import io.driftless.auth.web.dto.StatementResponse;
import io.driftless.common.id.AccountId;
import io.driftless.ledger.api.Account;
import io.driftless.ledger.api.EntryCursor;
import io.driftless.ledger.api.EntryPage;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin REST surface for account lifecycle + funding + statements: routing and (de)serialization only
 * — all orchestration lives in {@link AccountService}.
 *
 * <p>So money moves through the documented API (not just the demo/load generator): {@code POST
 * /accounts} opens a ledger account, {@code POST /accounts/{id}/funding} loads it with a
 * <em>balanced</em> ledger post, {@code GET /accounts/{id}} reads its details, and {@code GET
 * /accounts/{id}/statement} pages its entries with a stable keyset cursor. The posted/available
 * balance endpoint stays on {@link AuthorizationController}.
 *
 * <p>Every mutating endpoint reads the {@code Idempotency-Key} header (missing/blank ⇒ {@code 400};
 * same key + different body ⇒ {@code 409}; replay ⇒ the original {@code 2xx}). JPA entities are never
 * returned — every response is a DTO mapped from the ledger api types.
 */
@Slf4j
@RestController
@RequestMapping("/accounts")
@RequiredArgsConstructor
public class AccountsController {

    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    private static final int DEFAULT_STATEMENT_LIMIT = 50;
    private static final int MAX_STATEMENT_LIMIT = 200;

    private final AccountService accounts;

    @PostMapping
    public ResponseEntity<AccountResponse> open(
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody OpenAccountRequest request) {
        Account account = accounts.open(request.toCommand(), requireKey(idempotencyKey));
        log.info("opened account {} via REST", account.id());
        return ResponseEntity.status(HttpStatus.CREATED).body(AccountResponse.from(account));
    }

    @PostMapping("/{id}/funding")
    public FundingResponse fund(
            @PathVariable UUID id,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody FundAccountRequest request) {
        FundingReceipt receipt = accounts.fund(AccountId.of(id), request.toMoney(), requireKey(idempotencyKey));
        return FundingResponse.from(receipt);
    }

    @GetMapping("/{id}")
    public AccountResponse find(@PathVariable UUID id) {
        return AccountResponse.from(accounts.find(AccountId.of(id)));
    }

    @GetMapping("/{id}/statement")
    public StatementResponse statement(
            @PathVariable UUID id,
            @RequestParam(value = "after", required = false) String after,
            @RequestParam(value = "limit", required = false) Integer limit) {
        int capped = Math.min(limit == null ? DEFAULT_STATEMENT_LIMIT : Math.max(1, limit), MAX_STATEMENT_LIMIT);
        EntryPage page = accounts.statement(AccountId.of(id), EntryCursor.parse(after), capped);
        return StatementResponse.from(id, page);
    }

    /** Enforce the convention: a mutating request without an {@code Idempotency-Key} is a 400. */
    private static String requireKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new MissingIdempotencyKeyException(IDEMPOTENCY_KEY_HEADER);
        }
        return idempotencyKey;
    }
}
