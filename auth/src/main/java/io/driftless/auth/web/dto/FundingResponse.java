package io.driftless.auth.web.dto;

import io.driftless.auth.internal.FundingReceipt;
import java.time.Instant;
import java.util.UUID;

/**
 * Response for {@code POST /accounts/{id}/funding}: the balanced funding transaction id and its
 * posted business time.
 *
 * @param transactionId the funding transaction's ledger id
 * @param postedAt the ledger {@code postedAt} of that transaction
 */
public record FundingResponse(UUID transactionId, Instant postedAt) {

    public static FundingResponse from(FundingReceipt receipt) {
        return new FundingResponse(receipt.transactionId(), receipt.postedAt());
    }
}
