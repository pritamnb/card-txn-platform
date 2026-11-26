package io.github.pritamnb.cardtxn;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.Message;

/**
 * End-to-end through real (emulated) infrastructure: Moto for DynamoDB/SNS/SQS and real Redis,
 * both started by Testcontainers. Proves the API writes to DynamoDB, counts spend in Redis and
 * that SNS fans the event out to the fraud-check queue.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("local")
class TransactionFlowIT {

    @Container
    static final GenericContainer<?> MOTO = new GenericContainer<>("motoserver/moto:latest").withExposedPorts(5000);

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("app.aws.endpoint", () -> "http://" + MOTO.getHost() + ":" + MOTO.getMappedPort(5000));
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("app.notifications.enabled", () -> "false"); // we read the queues ourselves below
    }

    @Autowired
    WebTestClient client;

    @Autowired
    SqsAsyncClient sqs;

    @Test
    void transactionIsStoredCountedAndFannedOut() {
        String body = """
                {"idempotencyKey":"it-1","cardId":"4000123412341234","amount":12.34,"currency":"GBP","merchant":"Boots"}
                """;
        client.post().uri("/transactions").contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isAccepted();

        // Idempotent replay: 200, and spend is not counted twice
        client.post().uri("/transactions").contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isOk();

        client.get().uri("/transactions/it-1").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("PENDING");

        client.get().uri("/cards/4000123412341234/spend/today").exchange()
                .expectBody().jsonPath("$.totalToday").isEqualTo(12.34);

        // SNS delivered exactly one TransactionCreated event to the fraud-check queue (raw delivery)
        String queueUrl = sqs.getQueueUrl(b -> b.queueName("fraud-check-queue")).join().queueUrl();
        List<Message> messages = sqs.receiveMessage(b -> b.queueUrl(queueUrl).maxNumberOfMessages(10)
                .waitTimeSeconds((int) Duration.ofSeconds(5).toSeconds())).join().messages();
        assertThat(messages).hasSize(1);
        assertThat(messages.getFirst().body()).contains("\"type\":\"TransactionCreated\"", "\"txnId\":\"it-1\"");
    }
}
