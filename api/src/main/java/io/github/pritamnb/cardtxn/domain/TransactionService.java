package io.github.pritamnb.cardtxn.domain;

import io.github.pritamnb.cardtxn.cache.SpendCache;
import io.github.pritamnb.cardtxn.events.EventPublisher;
import io.github.pritamnb.cardtxn.repo.TransactionRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * The write path, as one non-blocking chain:
 * save to DynamoDB (idempotent) -> add to Redis spend -> publish to SNS.
 * The fraud check runs later and asynchronously, so the API answers fast.
 */
@Service
public class TransactionService {

    private final TransactionRepository repo;
    private final SpendCache spendCache;
    private final EventPublisher events;
    private final Clock clock;

    public TransactionService(TransactionRepository repo, SpendCache spendCache, EventPublisher events, Clock clock) {
        this.repo = repo;
        this.spendCache = spendCache;
        this.events = events;
        this.clock = clock;
    }

    public Mono<TxnResult> process(TxnRequest request) {
        Transaction txn = Transaction.pending(request, clock.instant());
        return repo.saveIfAbsent(txn).flatMap(created -> created
                ? spendCache.add(txn.cardId(), today(), txn.amount())
                        .then(events.publishCreated(txn))
                        .thenReturn(new TxnResult(txn, true))
                // Duplicate idempotency key: return the original, do not charge or publish again.
                : repo.findById(txn.txnId()).map(existing -> new TxnResult(existing, false)));
    }

    public Mono<Transaction> find(String txnId) {
        return repo.findById(txnId);
    }

    public Mono<BigDecimal> spendToday(String cardId) {
        return spendCache.get(cardId, today());
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
