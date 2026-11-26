package io.github.pritamnb.cardtxn.domain;

/** Result of processing a request: created=false means it was an idempotent replay. */
public record TxnResult(Transaction transaction, boolean created) {
}
