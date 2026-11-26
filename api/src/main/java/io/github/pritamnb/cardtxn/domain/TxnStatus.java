package io.github.pritamnb.cardtxn.domain;

/** Lifecycle of a card transaction. The API writes PENDING; the fraud Lambda moves it on. */
public enum TxnStatus {
    PENDING,
    APPROVED,
    FLAGGED
}
