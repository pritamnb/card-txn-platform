package io.github.pritamnb.cardtxn.notify;

import io.github.pritamnb.cardtxn.config.AppProperties;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reactive SQS consumer for the notifications queue (subscribed to both SNS topics).
 * Long-polls, handles each message, and deletes it only after success. A message that
 * keeps failing is redelivered after the visibility timeout and finally lands in the DLQ.
 */
@Component
@ConditionalOnProperty(name = "app.notifications.enabled", havingValue = "true")
public class NotificationConsumer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(NotificationConsumer.class);

    private final SqsAsyncClient sqs;
    private final JsonMapper json;
    private final String queueUrl;
    private volatile Disposable subscription;

    public NotificationConsumer(SqsAsyncClient sqs, JsonMapper json, AppProperties props) {
        this.sqs = sqs;
        // Parse JSON numbers as BigDecimal so amounts keep their scale (25.50, not 25.5).
        this.json = json.rebuild().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
        this.queueUrl = props.notifications().queueUrl();
    }

    @Override
    public void start() {
        ReceiveMessageRequest receive = ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(10)
                .waitTimeSeconds(10) // long polling: fewer empty responses, lower cost
                .build();

        subscription = Mono.defer(() -> Mono.fromFuture(sqs.receiveMessage(receive)))
                .repeat()
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1)).maxBackoff(Duration.ofSeconds(30))
                        .doBeforeRetry(s -> log.warn("SQS receive failed, retrying: {}", s.failure().toString())))
                .flatMapIterable(ReceiveMessageResponse::messages)
                .concatMap(msg -> handle(msg)
                        .then(delete(msg))
                        .onErrorResume(e -> {
                            log.error("Failed to process message {}, leaving it for redelivery", msg.messageId(), e);
                            return Mono.empty();
                        }))
                .subscribe();
        log.info("Notification consumer started on {}", queueUrl);
    }

    Mono<Void> handle(Message msg) {
        return Mono.fromRunnable(() -> {
            JsonNode event = json.readTree(msg.body());
            String type = event.path("type").asString();
            String card = "**** " + last4(event.path("cardId").asString());
            switch (type) {
                case "TransactionCreated" -> log.info("NOTIFY  {}: payment of {} {} at {}", card,
                        event.path("amount").asString(), event.path("currency").asString(),
                        event.path("merchant").asString());
                case "FraudAlert" -> log.warn("ALERT   {}: transaction {} flagged ({}). Was this you?", card,
                        event.path("txnId").asString(), event.path("reason").asString());
                default -> log.warn("Ignoring unknown event type '{}'", type);
            }
        });
    }

    private Mono<Void> delete(Message msg) {
        return Mono.fromFuture(() -> sqs.deleteMessage(DeleteMessageRequest.builder()
                .queueUrl(queueUrl).receiptHandle(msg.receiptHandle()).build())).then();
    }

    private static String last4(String cardId) {
        return cardId.length() <= 4 ? cardId : cardId.substring(cardId.length() - 4);
    }

    @Override
    public void stop() {
        Disposable s = subscription;
        if (s != null) {
            s.dispose();
        }
    }

    @Override
    public boolean isRunning() {
        Disposable s = subscription;
        return s != null && !s.isDisposed();
    }
}
