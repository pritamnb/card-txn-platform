package io.github.pritamnb.cardtxn.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(Aws aws, String tableName, String transactionTopicArn, Notifications notifications) {

    /** endpoint is only set locally (Moto); in AWS the SDK uses the real regional endpoint. */
    public record Aws(String region, String endpoint) {
    }

    public record Notifications(boolean enabled, String queueUrl) {
    }
}
