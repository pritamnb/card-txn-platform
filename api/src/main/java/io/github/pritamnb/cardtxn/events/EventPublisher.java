package io.github.pritamnb.cardtxn.events;

import io.github.pritamnb.cardtxn.config.AppProperties;
import io.github.pritamnb.cardtxn.domain.Transaction;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.sns.SnsAsyncClient;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import tools.jackson.databind.json.JsonMapper;

/** Publishes domain events to SNS. SNS fans each message out to every subscribed SQS queue. */
@Component
public class EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);

    private final SnsAsyncClient sns;
    private final JsonMapper json;
    private final String topicArn;

    public EventPublisher(SnsAsyncClient sns, JsonMapper json, AppProperties props) {
        this.sns = sns;
        this.json = json;
        this.topicArn = props.transactionTopicArn();
    }

    public Mono<String> publishCreated(Transaction t) {
        TransactionCreatedEvent event = TransactionCreatedEvent.from(t);
        PublishRequest request = PublishRequest.builder()
                .topicArn(topicArn)
                .message(json.writeValueAsString(event))
                .messageAttributes(Map.of("type", MessageAttributeValue.builder()
                        .dataType("String").stringValue(event.type()).build()))
                .build();
        return Mono.fromFuture(() -> sns.publish(request))
                .map(r -> r.messageId())
                .doOnNext(id -> log.info("Published {} txnId={} messageId={}", event.type(), t.txnId(), id));
    }
}
