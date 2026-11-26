package io.github.pritamnb.cardtxn;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pritamnb.cardtxn.cache.SpendCache;
import io.github.pritamnb.cardtxn.domain.Transaction;
import io.github.pritamnb.cardtxn.domain.TransactionService;
import io.github.pritamnb.cardtxn.domain.TxnRequest;
import io.github.pritamnb.cardtxn.domain.TxnStatus;
import io.github.pritamnb.cardtxn.events.EventPublisher;
import io.github.pritamnb.cardtxn.repo.TransactionRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class TransactionServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-26T10:00:00Z");
    private static final TxnRequest REQUEST =
            new TxnRequest("key-1", "4000123412341234", new BigDecimal("25.50"), "GBP", "Tesco");

    private TransactionRepository repo;
    private SpendCache spendCache;
    private EventPublisher events;
    private TransactionService service;

    @BeforeEach
    void setUp() {
        repo = mock(TransactionRepository.class);
        spendCache = mock(SpendCache.class);
        events = mock(EventPublisher.class);
        service = new TransactionService(repo, spendCache, events, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void newTransactionIsSavedCountedAndPublished() {
        when(repo.saveIfAbsent(any())).thenReturn(Mono.just(true));
        when(spendCache.add(anyString(), any(), any())).thenReturn(Mono.just(new BigDecimal("25.50")));
        when(events.publishCreated(any())).thenReturn(Mono.just("msg-1"));

        StepVerifier.create(service.process(REQUEST))
                .assertNext(result -> {
                    assert result.created();
                    assert result.transaction().status() == TxnStatus.PENDING;
                    assert result.transaction().createdAt().equals(NOW);
                })
                .verifyComplete();

        verify(spendCache).add("4000123412341234", LocalDate.of(2026, 11, 26), new BigDecimal("25.50"));
        verify(events).publishCreated(any());
    }

    @Test
    void duplicateKeyReturnsOriginalWithoutChargingOrPublishingAgain() {
        Transaction original = new Transaction("key-1", "4000123412341234", new BigDecimal("25.50"), "GBP",
                "Tesco", TxnStatus.APPROVED, null, NOW.minusSeconds(30));
        when(repo.saveIfAbsent(any())).thenReturn(Mono.just(false));
        when(repo.findById("key-1")).thenReturn(Mono.just(original));

        StepVerifier.create(service.process(REQUEST))
                .assertNext(result -> {
                    assert !result.created();
                    assert result.transaction() == original;
                })
                .verifyComplete();

        verify(spendCache, never()).add(anyString(), any(), any());
        verify(events, never()).publishCreated(any());
    }

    @Test
    void publishFailurePropagatesSoTheClientCanRetry() {
        when(repo.saveIfAbsent(any())).thenReturn(Mono.just(true));
        when(spendCache.add(anyString(), any(), any())).thenReturn(Mono.just(BigDecimal.ONE));
        when(events.publishCreated(any())).thenReturn(Mono.error(new IllegalStateException("SNS down")));

        StepVerifier.create(service.process(REQUEST))
                .expectErrorMessage("SNS down")
                .verify();
    }
}
