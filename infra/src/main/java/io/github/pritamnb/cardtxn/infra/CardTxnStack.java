package io.github.pritamnb.cardtxn.infra;

import java.util.List;
import java.util.Map;
import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.dynamodb.Attribute;
import software.amazon.awscdk.services.dynamodb.AttributeType;
import software.amazon.awscdk.services.dynamodb.BillingMode;
import software.amazon.awscdk.services.dynamodb.GlobalSecondaryIndexProps;
import software.amazon.awscdk.services.dynamodb.PointInTimeRecoverySpecification;
import software.amazon.awscdk.services.dynamodb.Table;
import software.amazon.awscdk.services.ec2.Peer;
import software.amazon.awscdk.services.ec2.Port;
import software.amazon.awscdk.services.ec2.SecurityGroup;
import software.amazon.awscdk.services.ec2.SubnetConfiguration;
import software.amazon.awscdk.services.ec2.SubnetType;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.ecs.Cluster;
import software.amazon.awscdk.services.ecs.ContainerImage;
import software.amazon.awscdk.services.ecs.patterns.ApplicationLoadBalancedFargateService;
import software.amazon.awscdk.services.ecs.patterns.ApplicationLoadBalancedTaskImageOptions;
import software.amazon.awscdk.services.elasticache.CfnCacheCluster;
import software.amazon.awscdk.services.elasticache.CfnSubnetGroup;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.lambda.eventsources.SqsEventSource;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.amazon.awscdk.services.sns.Topic;
import software.amazon.awscdk.services.sns.subscriptions.SqsSubscription;
import software.amazon.awscdk.services.sqs.DeadLetterQueue;
import software.amazon.awscdk.services.sqs.Queue;
import software.constructs.Construct;

/**
 * Everything the platform needs in AWS:
 *
 *  API (ECS Fargate, private subnets, behind an ALB)
 *    -> DynamoDB "transactions" (+ GSI cardId/createdAt)
 *    -> ElastiCache Redis (private subnets, only the API's security group can reach it)
 *    -> SNS "transaction-created" --fan-out--> SQS fraud-check  -> Lambda (fraud rules)
 *                                          \-> SQS notifications -> API consumer
 *  Lambda -> SNS "fraud-alerts" -> SQS notifications
 *  Every queue has a DLQ (3 receives, then dead-lettered).
 *
 * NAT gateway: lets tasks in private subnets reach AWS APIs and pull images. It bills by the hour,
 * so this demo uses ONE (production would use one per AZ, or VPC endpoints to cut cost).
 */
public class CardTxnStack extends Stack {

    /**
     * @param lite true = data + messaging + Lambda only (no VPC/NAT, Redis, ECS, ALB): nothing billed
     *             by the hour, pennies at demo volume. Deploy with: cdk deploy -c mode=lite
     */
    public CardTxnStack(Construct scope, String id, StackProps props, boolean lite) {
        super(scope, id, props);

        // ---------- Data ----------
        Table table = Table.Builder.create(this, "Transactions")
                .tableName("transactions")
                .partitionKey(attr("txnId"))
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .pointInTimeRecoverySpecification(PointInTimeRecoverySpecification.builder()
                        .pointInTimeRecoveryEnabled(true).build())
                .removalPolicy(RemovalPolicy.DESTROY) // demo only: production would RETAIN
                .build();
        table.addGlobalSecondaryIndex(GlobalSecondaryIndexProps.builder()
                .indexName("cardId-createdAt-index")
                .partitionKey(attr("cardId"))
                .sortKey(attr("createdAt"))
                .build());

        // ---------- Messaging ----------
        Topic txnTopic = Topic.Builder.create(this, "TransactionCreated").topicName("transaction-created").build();
        Topic alertTopic = Topic.Builder.create(this, "FraudAlerts").topicName("fraud-alerts").build();

        Queue fraudQueue = queueWithDlq("FraudCheck", "fraud-check-queue", Duration.seconds(60));
        Queue notifyQueue = queueWithDlq("Notifications", "notifications-queue", Duration.seconds(30));

        // Raw delivery: consumers receive our event JSON, not the SNS envelope.
        txnTopic.addSubscription(SqsSubscription.Builder.create(fraudQueue).rawMessageDelivery(true).build());
        txnTopic.addSubscription(SqsSubscription.Builder.create(notifyQueue).rawMessageDelivery(true).build());
        alertTopic.addSubscription(SqsSubscription.Builder.create(notifyQueue).rawMessageDelivery(true).build());

        // ---------- Fraud Lambda (Java 21) ----------
        Function fraudFn = Function.Builder.create(this, "FraudCheckFn")
                .runtime(Runtime.JAVA_21)
                .handler("io.github.pritamnb.cardtxn.fraud.FraudHandler::handleRequest")
                .code(Code.fromAsset("../fraud-lambda/target/fraud-lambda-0.1.0.jar"))
                .memorySize(1024)
                .logGroup(LogGroup.Builder.create(this, "FraudCheckLogs")
                        .retention(RetentionDays.ONE_WEEK)
                        .removalPolicy(RemovalPolicy.DESTROY).build())
                .timeout(Duration.seconds(30)) // must be < queue visibility timeout (60s)
                .environment(Map.of(
                        "TABLE_NAME", table.getTableName(),
                        "FRAUD_ALERT_TOPIC_ARN", alertTopic.getTopicArn()))
                .build();
        fraudFn.addEventSource(SqsEventSource.Builder.create(fraudQueue)
                .batchSize(10)
                .reportBatchItemFailures(true) // retry only failed messages
                .build());
        table.grantReadWriteData(fraudFn);
        alertTopic.grantPublish(fraudFn);

        CfnOutput.Builder.create(this, "FraudFunctionName").value(fraudFn.getFunctionName()).build();
        CfnOutput.Builder.create(this, "TransactionTopicArn").value(txnTopic.getTopicArn()).build();
        CfnOutput.Builder.create(this, "NotificationsQueueUrl").value(notifyQueue.getQueueUrl()).build();
        CfnOutput.Builder.create(this, "FraudDlqUrl").value(fraudQueue.getDeadLetterQueue().getQueue().getQueueUrl()).build();
        CfnOutput.Builder.create(this, "TableName").value(table.getTableName()).build();

        if (lite) {
            return; // lite mode stops here: no VPC, NAT gateway, Redis, ECS or ALB
        }

        // ---------- Network ----------
        Vpc vpc = Vpc.Builder.create(this, "Vpc")
                .maxAzs(2)
                .natGateways(1)
                .subnetConfiguration(List.of(
                        SubnetConfiguration.builder().name("public").subnetType(SubnetType.PUBLIC).cidrMask(24).build(),
                        SubnetConfiguration.builder().name("private").subnetType(SubnetType.PRIVATE_WITH_EGRESS).cidrMask(24).build()))
                .build();

        // ---------- Redis (ElastiCache) ----------
        SecurityGroup apiSg = SecurityGroup.Builder.create(this, "ApiSg").vpc(vpc).build();
        SecurityGroup redisSg = SecurityGroup.Builder.create(this, "RedisSg").vpc(vpc).allowAllOutbound(false).build();
        redisSg.addIngressRule(Peer.securityGroupId(apiSg.getSecurityGroupId()), Port.tcp(6379), "API to Redis");

        CfnSubnetGroup redisSubnets = CfnSubnetGroup.Builder.create(this, "RedisSubnets")
                .description("Private subnets for Redis")
                .subnetIds(vpc.selectSubnets(software.amazon.awscdk.services.ec2.SubnetSelection.builder()
                        .subnetType(SubnetType.PRIVATE_WITH_EGRESS).build()).getSubnetIds())
                .build();
        CfnCacheCluster redis = CfnCacheCluster.Builder.create(this, "Redis")
                .engine("redis")
                .cacheNodeType("cache.t4g.micro")
                .numCacheNodes(1)
                .cacheSubnetGroupName(redisSubnets.getRef())
                .vpcSecurityGroupIds(List.of(redisSg.getSecurityGroupId()))
                .build();

        // ---------- API (Spring WebFlux on ECS Fargate) ----------
        Cluster cluster = Cluster.Builder.create(this, "Cluster").vpc(vpc).build();
        ApplicationLoadBalancedFargateService api = ApplicationLoadBalancedFargateService.Builder.create(this, "Api")
                .cluster(cluster)
                .cpu(512)
                .memoryLimitMiB(1024)
                .desiredCount(1)
                .minHealthyPercent(100)
                .circuitBreaker(software.amazon.awscdk.services.ecs.DeploymentCircuitBreaker.builder().rollback(true).build())
                .securityGroups(List.of(apiSg))
                .taskImageOptions(ApplicationLoadBalancedTaskImageOptions.builder()
                        .image(ContainerImage.fromAsset("../api"))
                        .containerPort(8080)
                        .environment(Map.of(
                                "AWS_REGION", getRegion(),
                                "TABLE_NAME", table.getTableName(),
                                "TRANSACTION_TOPIC_ARN", txnTopic.getTopicArn(),
                                "NOTIFICATIONS_QUEUE_URL", notifyQueue.getQueueUrl(),
                                "REDIS_HOST", redis.getAttrRedisEndpointAddress(),
                                "REDIS_PORT", redis.getAttrRedisEndpointPort()))
                        .build())
                .build();
        api.getTargetGroup().configureHealthCheck(software.amazon.awscdk.services.elasticloadbalancingv2.HealthCheck.builder()
                .path("/actuator/health").build());

        // Least privilege: the API task role gets only what it uses.
        table.grantReadWriteData(api.getTaskDefinition().getTaskRole());
        txnTopic.grantPublish(api.getTaskDefinition().getTaskRole());
        notifyQueue.grantConsumeMessages(api.getTaskDefinition().getTaskRole());

        CfnOutput.Builder.create(this, "ApiUrl").value("http://" + api.getLoadBalancer().getLoadBalancerDnsName()).build();
    }

    private Queue queueWithDlq(String id, String name, Duration visibilityTimeout) {
        Queue dlq = Queue.Builder.create(this, id + "Dlq")
                .queueName(name + "-dlq")
                .retentionPeriod(Duration.days(14))
                .build();
        return Queue.Builder.create(this, id + "Queue")
                .queueName(name)
                .visibilityTimeout(visibilityTimeout)
                .deadLetterQueue(DeadLetterQueue.builder().queue(dlq).maxReceiveCount(3).build())
                .build();
    }

    private static Attribute attr(String name) {
        return Attribute.builder().name(name).type(AttributeType.STRING).build();
    }
}
