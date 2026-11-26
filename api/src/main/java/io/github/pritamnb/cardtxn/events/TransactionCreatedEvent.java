package io.github.pritamnb.cardtxn.events;

import io.github.pritamnb.cardtxn.domain.Transaction;
import java.math.BigDecimal;
import java.time.Instant;

/** Event published to the transaction-created SNS topic. Consumers must tolerate duplicates. */
public record TransactionCreatedEvent(
        String type,
        String txnId,
        String cardId,
        BigDecimal amount,
        String currency,
        String merchant,
        Instant createdAt) {

    public static final String TYPE = "TransactionCreated";

    public static TransactionCreatedEvent from(Transaction t) {
        return new TransactionCreatedEvent(TYPE, t.txnId(), t.cardId(), t.amount(), t.currency(),
                t.merchant(), t.createdAt());
    }
}
