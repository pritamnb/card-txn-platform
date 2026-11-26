package io.github.pritamnb.cardtxn.repo;

import io.github.pritamnb.cardtxn.config.AppProperties;
import io.github.pritamnb.cardtxn.domain.Transaction;
import io.github.pritamnb.cardtxn.domain.TxnStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

/**
 * DynamoDB access. Table "transactions": partition key txnId.
 * GSI "cardId-createdAt-index" (cardId, createdAt) lets the fraud check find recent swipes per card.
 */
@Repository
public class TransactionRepository {

    private final DynamoDbAsyncClient dynamo;
    private final String table;

    public TransactionRepository(DynamoDbAsyncClient dynamo, AppProperties props) {
        this.dynamo = dynamo;
        this.table = props.tableName();
    }

    /**
     * Conditional put: only writes if no item with this txnId exists.
     * Emits true when created, false when it was a duplicate (idempotent replay).
     */
    public Mono<Boolean> saveIfAbsent(Transaction t) {
        PutItemRequest request = PutItemRequest.builder()
                .tableName(table)
                .item(toItem(t))
                .conditionExpression("attribute_not_exists(txnId)")
                .build();
        return Mono.fromFuture(() -> dynamo.putItem(request))
                .thenReturn(true)
                .onErrorResume(ConditionalCheckFailedException.class, e -> Mono.just(false));
    }

    public Mono<Transaction> findById(String txnId) {
        GetItemRequest request = GetItemRequest.builder()
                .tableName(table)
                .key(Map.of("txnId", s(txnId)))
                .consistentRead(true)
                .build();
        return Mono.fromFuture(() -> dynamo.getItem(request))
                .filter(r -> r.hasItem() && !r.item().isEmpty())
                .map(r -> fromItem(r.item()));
    }

    static Map<String, AttributeValue> toItem(Transaction t) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("txnId", s(t.txnId()));
        item.put("cardId", s(t.cardId()));
        item.put("amount", AttributeValue.fromN(t.amount().toPlainString()));
        item.put("currency", s(t.currency()));
        item.put("merchant", s(t.merchant()));
        item.put("status", s(t.status().name()));
        item.put("createdAt", s(t.createdAt().toString()));
        if (t.fraudReason() != null) {
            item.put("fraudReason", s(t.fraudReason()));
        }
        return item;
    }

    static Transaction fromItem(Map<String, AttributeValue> item) {
        AttributeValue reason = item.get("fraudReason");
        return new Transaction(
                item.get("txnId").s(),
                item.get("cardId").s(),
                new BigDecimal(item.get("amount").n()),
                item.get("currency").s(),
                item.get("merchant").s(),
                TxnStatus.valueOf(item.get("status").s()),
                reason == null ? null : reason.s(),
                Instant.parse(item.get("createdAt").s()));
    }

    private static AttributeValue s(String value) {
        return AttributeValue.fromS(value);
    }
}
