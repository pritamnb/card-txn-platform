package io.github.pritamnb.cardtxn.domain;

import java.math.BigDecimal;
import java.time.Instant;

/** Immutable transaction as stored in DynamoDB. txnId is the client's idempotency key. */
public record Transaction(
        String txnId,
        String cardId,
        BigDecimal amount,
        String currency,
        String merchant,
        TxnStatus status,
        String fraudReason,
        Instant createdAt) {

    public static Transaction pending(TxnRequest req, Instant now) {
        return new Transaction(req.idempotencyKey(), req.cardId(), req.amount(), req.currency(),
                req.merchant(), TxnStatus.PENDING, null, now);
    }

    /** Last four digits only: never log or return a full card number. */
    public String maskedCard() {
        return "**** " + cardId.substring(cardId.length() - 4);
    }
}
