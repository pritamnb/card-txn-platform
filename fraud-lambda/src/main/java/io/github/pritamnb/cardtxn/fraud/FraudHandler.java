package io.github.pritamnb.cardtxn.fraud;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.Select;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.sns.SnsClient;

/**
 * Lambda triggered by the fraud-check SQS queue (batch of up to 10 messages).
 *
 * For each TransactionCreated event:
 *  1. count recent swipes on the card (DynamoDB GSI query)
 *  2. apply FraudRules
 *  3. move the transaction PENDING -> APPROVED or FLAGGED (conditional update = idempotent)
 *  4. if FLAGGED, publish a FraudAlert to SNS
 *
 * Returns partial batch failures so only the failed messages are retried, not the whole batch.
 */
public class FraudHandler implements RequestHandler<SQSEvent, SQSBatchResponse> {

    private static final Logger log = LoggerFactory.getLogger(FraudHandler.class);
    // BigDecimal for JSON numbers: never let money pass through a double.
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES).build();

    private final DynamoDbClient dynamo;
    private final SnsClient sns;
    private final String table;
    private final String alertTopicArn;

    /** Used by the Lambda runtime. Clients are created once per container and reused across invocations. */
    public FraudHandler() {
        this(configure(DynamoDbClient.builder()).build(), configure(SnsClient.builder()).build(),
                env("TABLE_NAME", "transactions"), env("FRAUD_ALERT_TOPIC_ARN", ""));
    }

    FraudHandler(DynamoDbClient dynamo, SnsClient sns, String table, String alertTopicArn) {
        this.dynamo = dynamo;
        this.sns = sns;
        this.table = table;
        this.alertTopicArn = alertTopicArn;
        if (alertTopicArn == null || alertTopicArn.isBlank()) {
            throw new IllegalStateException("FRAUD_ALERT_TOPIC_ARN must be set"); // fail fast, not on first alert
        }
    }

    @Override
    public SQSBatchResponse handleRequest(SQSEvent event, Context context) {
        List<SQSBatchResponse.BatchItemFailure> failures = new ArrayList<>();
        for (SQSEvent.SQSMessage msg : event.getRecords()) {
            try {
                process(JSON.readTree(msg.getBody()));
            } catch (Exception e) {
                log.error("Failed message {}: {}", msg.getMessageId(), e.toString());
                failures.add(SQSBatchResponse.BatchItemFailure.builder()
                        .withItemIdentifier(msg.getMessageId()).build());
            }
        }
        return SQSBatchResponse.builder().withBatchItemFailures(failures).build();
    }

    void process(JsonNode event) {
        if (!"TransactionCreated".equals(event.path("type").asText())) {
            log.warn("Skipping event type {}", event.path("type").asText());
            return;
        }
        String txnId = event.path("txnId").asText();
        String cardId = event.path("cardId").asText();
        BigDecimal amount = event.path("amount").decimalValue();
        Instant createdAt = Instant.parse(event.path("createdAt").asText());

        int swipes = countSwipes(cardId, createdAt);
        Optional<String> reason = FraudRules.evaluate(amount, swipes);

        boolean updated = reason.isPresent() ? setStatus(txnId, "FLAGGED", reason.get()) : setStatus(txnId, "APPROVED", null);
        if (!updated) {
            // Already decided by an earlier delivery. If that attempt flagged it but crashed before the
            // alert was published, publish again now: at-least-once alerts, consumers tolerate duplicates.
            Map<String, AttributeValue> current = currentItem(txnId);
            if ("FLAGGED".equals(current.get("status").s())) {
                log.info("txnId={} already FLAGGED, re-publishing alert (redelivery)", txnId);
                publishAlert(txnId, cardId, amount, current.get("fraudReason").s());
            } else {
                log.info("txnId={} already processed (duplicate delivery), skipping", txnId);
            }
            return;
        }
        log.info("txnId={} card=****{} amount={} swipesIn60s={} -> {}", txnId, last4(cardId), amount.toPlainString(), swipes,
                reason.map(r -> "FLAGGED (" + r + ")").orElse("APPROVED"));
        reason.ifPresent(r -> publishAlert(txnId, cardId, amount, r));
    }

    /** Swipes on this card in the 60s up to and including this transaction (GSI cardId + createdAt). */
    private int countSwipes(String cardId, Instant createdAt) {
        Instant from = createdAt.minusSeconds(FraudRules.VELOCITY_WINDOW_SECONDS);
        return dynamo.query(QueryRequest.builder()
                .tableName(table)
                .indexName("cardId-createdAt-index")
                .keyConditionExpression("cardId = :c AND createdAt BETWEEN :from AND :to")
                .expressionAttributeValues(Map.of(
                        ":c", AttributeValue.fromS(cardId),
                        ":from", AttributeValue.fromS(from.toString()),
                        ":to", AttributeValue.fromS(createdAt.toString())))
                .select(Select.COUNT)
                .build()).count();
    }

    /** Only moves PENDING transactions, so a redelivered message cannot flip a decision twice. */
    private boolean setStatus(String txnId, String status, String reason) {
        StringBuilder update = new StringBuilder("SET #s = :new");
        Map<String, AttributeValue> values = new java.util.HashMap<>(Map.of(
                ":new", AttributeValue.fromS(status),
                ":pending", AttributeValue.fromS("PENDING")));
        if (reason != null) {
            update.append(", fraudReason = :r");
            values.put(":r", AttributeValue.fromS(reason));
        }
        try {
            dynamo.updateItem(UpdateItemRequest.builder()
                    .tableName(table)
                    .key(Map.of("txnId", AttributeValue.fromS(txnId)))
                    .updateExpression(update.toString())
                    .conditionExpression("#s = :pending")
                    .expressionAttributeNames(Map.of("#s", "status"))
                    .expressionAttributeValues(values)
                    .build());
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false;
        }
    }

    private Map<String, AttributeValue> currentItem(String txnId) {
        GetItemResponse r = dynamo.getItem(b -> b.tableName(table).consistentRead(true)
                .key(Map.of("txnId", AttributeValue.fromS(txnId))));
        return r.item();
    }

    private void publishAlert(String txnId, String cardId, BigDecimal amount, String reason) {
        String body = JSON.createObjectNode()
                .put("type", "FraudAlert")
                .put("txnId", txnId)
                .put("cardId", cardId)
                .put("amount", amount.toPlainString())
                .put("reason", reason)
                .toString();
        sns.publish(b -> b.topicArn(alertTopicArn).message(body));
    }

    private static <B extends AwsClientBuilder<B, ?>> B configure(B builder) {
        builder.region(Region.of(env("AWS_REGION", "eu-west-2")));
        String endpoint = env("AWS_ENDPOINT_URL", "");
        if (!endpoint.isBlank()) { // local emulator
            builder.endpointOverride(URI.create(endpoint));
            builder.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")));
        }
        return builder;
    }

    static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }

    private static String last4(String cardId) {
        return cardId.substring(Math.max(0, cardId.length() - 4));
    }
}
