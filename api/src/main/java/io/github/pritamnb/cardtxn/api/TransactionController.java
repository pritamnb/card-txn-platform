package io.github.pritamnb.cardtxn.api;

import io.github.pritamnb.cardtxn.domain.Transaction;
import io.github.pritamnb.cardtxn.domain.TransactionService;
import io.github.pritamnb.cardtxn.domain.TxnRequest;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
public class TransactionController {

    private final TransactionService service;

    public TransactionController(TransactionService service) {
        this.service = service;
    }

    /** 202 Accepted for a new transaction (fraud check still pending); 200 OK for a replay. */
    @PostMapping("/transactions")
    public Mono<ResponseEntity<TxnResponse>> create(@Valid @RequestBody TxnRequest request) {
        return service.process(request).map(result -> ResponseEntity
                .status(result.created() ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .body(TxnResponse.from(result.transaction())));
    }

    @GetMapping("/transactions/{txnId}")
    public Mono<ResponseEntity<TxnResponse>> get(@PathVariable String txnId) {
        return service.find(txnId)
                .map(t -> ResponseEntity.ok(TxnResponse.from(t)))
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @GetMapping("/cards/{cardId}/spend/today")
    public Mono<SpendResponse> spendToday(@PathVariable String cardId) {
        return service.spendToday(cardId).map(total -> new SpendResponse("**** " + cardId.substring(Math.max(0, cardId.length() - 4)), total));
    }

    public record SpendResponse(String card, BigDecimal totalToday) {
    }

    /** API view: masks the card number. */
    public record TxnResponse(String txnId, String card, BigDecimal amount, String currency, String merchant,
                              String status, String fraudReason, String createdAt) {
        static TxnResponse from(Transaction t) {
            return new TxnResponse(t.txnId(), t.maskedCard(), t.amount(), t.currency(), t.merchant(),
                    t.status().name(), t.fraudReason(), t.createdAt().toString());
        }
    }
}
