package io.github.pritamnb.cardtxn;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.github.pritamnb.cardtxn.api.TransactionController;
import io.github.pritamnb.cardtxn.domain.Transaction;
import io.github.pritamnb.cardtxn.domain.TransactionService;
import io.github.pritamnb.cardtxn.domain.TxnResult;
import io.github.pritamnb.cardtxn.domain.TxnStatus;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

@WebFluxTest(TransactionController.class)
class TransactionControllerTest {

    private static final String BODY = """
            {"idempotencyKey":"key-1","cardId":"4000123412341234","amount":25.50,"currency":"GBP","merchant":"Tesco"}
            """;
    private static final Transaction TXN = new Transaction("key-1", "4000123412341234", new BigDecimal("25.50"),
            "GBP", "Tesco", TxnStatus.PENDING, null, Instant.parse("2026-11-26T10:00:00Z"));

    @Autowired
    WebTestClient client;

    @MockitoBean
    TransactionService service;

    @Test
    void newTransactionReturns202WithMaskedCard() {
        when(service.process(any())).thenReturn(Mono.just(new TxnResult(TXN, true)));

        client.post().uri("/transactions").contentType(MediaType.APPLICATION_JSON).bodyValue(BODY)
                .exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.card").isEqualTo("**** 1234")
                .jsonPath("$.status").isEqualTo("PENDING");
    }

    @Test
    void replayReturns200() {
        when(service.process(any())).thenReturn(Mono.just(new TxnResult(TXN, false)));

        client.post().uri("/transactions").contentType(MediaType.APPLICATION_JSON).bodyValue(BODY)
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void invalidRequestReturns400() {
        client.post().uri("/transactions").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"idempotencyKey":"k","cardId":"123","amount":-1,"currency":"gbp","merchant":""}
                        """)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void unknownTransactionReturns404() {
        when(service.find("nope")).thenReturn(Mono.empty());

        client.get().uri("/transactions/nope").exchange().expectStatus().isNotFound();
    }
}
