package io.github.pritamnb.cardtxn.config;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.sns.SnsAsyncClient;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/**
 * Local profile only: creates the same resources the CDK stack creates in AWS
 * (table + GSI, two topics, two queues with DLQs, raw-delivery subscriptions) on the Moto emulator.
 * Runs once at startup, before the consumer starts. All calls are idempotent.
 */
@Component
@Profile("local")
public class LocalResourceBootstrap implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(LocalResourceBootstrap.class);

    private final DynamoDbAsyncClient dynamo;
    private final SnsAsyncClient sns;
    private final SqsAsyncClient sqs;
    private final AppProperties props;

    public LocalResourceBootstrap(DynamoDbAsyncClient dynamo, SnsAsyncClient sns, SqsAsyncClient sqs,
                                  AppProperties props) {
        this.dynamo = dynamo;
        this.sns = sns;
        this.sqs = sqs;
        this.props = props;
    }

    @Override
    public void afterPropertiesSet() {
        createTable();
        String txnTopic = sns.createTopic(b -> b.name("transaction-created")).join().topicArn();
        String alertTopic = sns.createTopic(b -> b.name("fraud-alerts")).join().topicArn();

        String fraudQueue = queueWithDlq("fraud-check-queue");
        String notifyQueue = queueWithDlq("notifications-queue");

        subscribe(txnTopic, fraudQueue);
        subscribe(txnTopic, notifyQueue);
        subscribe(alertTopic, notifyQueue);
        log.info("Local AWS resources ready (table {}, topics {}, {})", props.tableName(), txnTopic, alertTopic);
    }

    private void createTable() {
        try {
            dynamo.createTable(b -> b.tableName(props.tableName())
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    .attributeDefinitions(
                            attr("txnId"), attr("cardId"), attr("createdAt"))
                    .keySchema(key("txnId", KeyType.HASH))
                    .globalSecondaryIndexes(GlobalSecondaryIndex.builder()
                            .indexName("cardId-createdAt-index")
                            .keySchema(key("cardId", KeyType.HASH), key("createdAt", KeyType.RANGE))
                            .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                            .build())).join();
        } catch (java.util.concurrent.CompletionException e) {
            if (!(e.getCause() instanceof ResourceInUseException)) {
                throw e;
            }
        }
    }

    private String queueWithDlq(String name) {
        String dlqUrl = sqs.createQueue(b -> b.queueName(name + "-dlq")).join().queueUrl();
        String dlqArn = arnOf(dlqUrl);
        String redrive = "{\"deadLetterTargetArn\":\"" + dlqArn + "\",\"maxReceiveCount\":\"3\"}";
        return sqs.createQueue(b -> b.queueName(name).attributes(Map.of(
                QueueAttributeName.REDRIVE_POLICY, redrive,
                QueueAttributeName.VISIBILITY_TIMEOUT, "30"))).join().queueUrl();
    }

    private void subscribe(String topicArn, String queueUrl) {
        // Raw delivery: the SQS body is our event JSON, not the SNS envelope.
        sns.subscribe(b -> b.topicArn(topicArn).protocol("sqs").endpoint(arnOf(queueUrl))
                .attributes(Map.of("RawMessageDelivery", "true"))).join();
    }

    private String arnOf(String queueUrl) {
        return sqs.getQueueAttributes(b -> b.queueUrl(queueUrl).attributeNames(QueueAttributeName.QUEUE_ARN))
                .join().attributes().get(QueueAttributeName.QUEUE_ARN);
    }

    private static AttributeDefinition attr(String name) {
        return AttributeDefinition.builder().attributeName(name).attributeType(ScalarAttributeType.S).build();
    }

    private static KeySchemaElement key(String name, KeyType type) {
        return KeySchemaElement.builder().attributeName(name).keyType(type).build();
    }
}
