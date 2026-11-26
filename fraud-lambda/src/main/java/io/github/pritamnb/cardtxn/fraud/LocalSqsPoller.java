package io.github.pritamnb.cardtxn.fraud;

import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;

/**
 * LOCAL ONLY. In AWS, Lambda's SQS event source mapping polls the queue and invokes FraudHandler.
 * Locally there is no Lambda service, so this does the same job: long-poll the queue, wrap the
 * messages in an SQSEvent, call the real handler, and delete the messages that succeeded.
 */
public final class LocalSqsPoller {

    private static final Logger log = LoggerFactory.getLogger(LocalSqsPoller.class);

    public static void main(String[] args) throws InterruptedException {
        String endpoint = FraudHandler.env("AWS_ENDPOINT_URL", "http://localhost:4566");
        String queueName = FraudHandler.env("FRAUD_QUEUE_NAME", "fraud-check-queue");
        SqsClient sqs = SqsClient.builder()
                .region(Region.of(FraudHandler.env("AWS_REGION", "eu-west-2")))
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
        FraudHandler handler = new FraudHandler();

        String queueUrl = waitForQueue(sqs, queueName);
        log.info("Polling {} (stand-in for the Lambda SQS trigger)", queueUrl);
        while (true) {
            List<Message> messages = sqs.receiveMessage(b -> b.queueUrl(queueUrl)
                    .maxNumberOfMessages(10).waitTimeSeconds(10)).messages();
            if (messages.isEmpty()) {
                continue;
            }
            SQSBatchResponse response = handler.handleRequest(toEvent(messages), null);
            Set<String> failed = response.getBatchItemFailures().stream()
                    .map(SQSBatchResponse.BatchItemFailure::getItemIdentifier).collect(Collectors.toSet());
            for (Message m : messages) {
                if (!failed.contains(m.messageId())) {
                    sqs.deleteMessage(b -> b.queueUrl(queueUrl).receiptHandle(m.receiptHandle()));
                }
            }
        }
    }

    private static SQSEvent toEvent(List<Message> messages) {
        SQSEvent event = new SQSEvent();
        event.setRecords(messages.stream().map(m -> {
            SQSEvent.SQSMessage msg = new SQSEvent.SQSMessage();
            msg.setMessageId(m.messageId());
            msg.setBody(m.body());
            return msg;
        }).toList());
        return event;
    }

    /** The API creates the queues on startup, so wait for them. */
    private static String waitForQueue(SqsClient sqs, String name) throws InterruptedException {
        while (true) {
            try {
                return sqs.getQueueUrl(b -> b.queueName(name)).queueUrl();
            } catch (Exception e) {
                log.info("Waiting for queue {} ...", name);
                Thread.sleep(2000);
            }
        }
    }
}
