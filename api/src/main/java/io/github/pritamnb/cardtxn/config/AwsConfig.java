package io.github.pritamnb.cardtxn.config;

import java.net.URI;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.sns.SnsAsyncClient;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

/**
 * Async AWS SDK v2 clients. They return CompletableFuture, which we wrap in Mono,
 * so no WebFlux event-loop thread ever blocks on network I/O.
 */
@Configuration
public class AwsConfig {

    private final AppProperties props;

    public AwsConfig(AppProperties props) {
        this.props = props;
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    DynamoDbAsyncClient dynamoDbAsyncClient() {
        return configure(DynamoDbAsyncClient.builder()).build();
    }

    @Bean
    SnsAsyncClient snsAsyncClient() {
        return configure(SnsAsyncClient.builder()).build();
    }

    @Bean
    SqsAsyncClient sqsAsyncClient() {
        return configure(SqsAsyncClient.builder()).build();
    }

    private <B extends AwsClientBuilder<B, ?>> B configure(B builder) {
        builder.region(Region.of(props.aws().region()));
        String endpoint = props.aws().endpoint();
        if (endpoint != null && !endpoint.isBlank()) {
            // Local emulator: fixed dummy credentials, custom endpoint.
            builder.endpointOverride(URI.create(endpoint));
            builder.credentialsProvider(localCredentials());
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.builder().build());
        }
        return builder;
    }

    private static AwsCredentialsProvider localCredentials() {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
    }
}
