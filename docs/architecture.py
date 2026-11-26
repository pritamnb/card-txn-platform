"""
System design diagram as code, using the official AWS architecture icons.

    pip install diagrams      # also needs Graphviz: brew install graphviz
    python docs/architecture.py

Writes docs/architecture.png. Blue numbered edges = synchronous request path;
orange lettered edges = asynchronous event path.
"""
from pathlib import Path

from diagrams import Cluster, Diagram, Edge
from diagrams.aws.compute import Fargate, Lambda
from diagrams.aws.database import Dynamodb, ElasticacheForRedis
from diagrams.aws.general import Client
from diagrams.aws.integration import SNS, SQS
from diagrams.aws.management import Cloudformation, Cloudwatch
from diagrams.aws.network import ALB, NATGateway

SYNC = "#2c6fd1"
ASYNC = "#c2700f"
GREY = "#7a8594"
FONT = "Helvetica"


# back=True: an arrow that points "backwards" in the flow; it is drawn but does not affect the
# left-to-right layout (Graphviz constraint=false), which keeps the main path straight.
def sync(label, back=False):
    return Edge(label=label, color=SYNC, fontcolor=SYNC, style="bold", fontname=FONT, fontsize="12",
                constraint="false" if back else "true")


def async_(label="", back=False):
    return Edge(label=label, color=ASYNC, fontcolor=ASYNC, style="dashed", fontname=FONT, fontsize="12",
                constraint="false" if back else "true")


def faint(label="", back=False):
    return Edge(label=label, color=GREY, fontcolor=GREY, style="dotted", fontname=FONT, fontsize="11",
                constraint="false" if back else "true")


HIDDEN = Edge(style="invis")


graph_attr = {
    "fontname": FONT,
    "fontsize": "20",
    "labelloc": "t",
    "pad": "0.6",
    "nodesep": "0.7",
    "ranksep": "1.1",
    "splines": "spline",
    "bgcolor": "white",
}
node_attr = {"fontname": FONT, "fontsize": "12"}
cluster = lambda bg, pen="#9aa5b4", style="rounded": {  # noqa: E731
    "bgcolor": bg, "pencolor": pen, "style": style, "fontname": FONT, "fontsize": "13", "margin": "18"}

out = Path(__file__).with_name("architecture")

with Diagram("Card Transaction Platform: System Design", filename=str(out), outformat="png", show=False,
             direction="LR", graph_attr=graph_attr, node_attr=node_attr):
    client = Client("Card terminal / app")

    with Cluster("AWS Cloud  ·  eu-north-1", graph_attr=cluster("#fbfcfd", "#232f3e")):
        with Cluster("VPC  ·  2 Availability Zones", graph_attr=cluster("#f2f6fb", "#7aa1d6", "rounded,dashed")):
            with Cluster("Public subnets", graph_attr=cluster("#eaf5ea", "#7fb77f", "rounded,dashed")):
                alb = ALB("Application\nLoad Balancer")
                nat = NATGateway("NAT Gateway")
            with Cluster("Private subnets", graph_attr=cluster("#eef3fb", "#7aa1d6", "rounded,dashed")):
                api = Fargate("Card Txn API\nSpring WebFlux · Java 21\n(ECS Fargate)")
                redis = ElasticacheForRedis("ElastiCache Redis\ndaily spend (pence)")

        with Cluster("Event-driven backend  (deployed in lite mode)", graph_attr=cluster("#fdf8f1", "#d6a865")):
            txn_topic = SNS("SNS\ntransaction-created")
            fraud_q = SQS("SQS\nfraud-check")
            notify_q = SQS("SQS\nnotifications")
            fraud_fn = Lambda("Lambda\nfraud rules")
            alert_topic = SNS("SNS\nfraud-alerts")
            table = Dynamodb("DynamoDB\ntransactions\n(GSI cardId+createdAt)")
            with Cluster("Dead-letter queues", graph_attr=cluster("#f6f1ea", "#c9b28e", "rounded,dashed")):
                fraud_dlq = SQS("fraud-check-dlq")
                notify_dlq = SQS("notifications-dlq")

        with Cluster("Operations", graph_attr=cluster("#f5f6f8")):
            logs = Cloudwatch("CloudWatch\nlogs · alarms")
            cdk = Cloudformation("AWS CDK (Java)\n→ CloudFormation")

    # Synchronous request path: the client waits for these
    client >> sync("1  POST /transactions") >> alb
    alb >> sync("2") >> api
    api >> sync("3  conditional put") >> table
    api >> sync("4  INCRBY") >> redis
    api >> sync("5  publish") >> txn_topic

    # Asynchronous event path: after the 202 response, at least once
    txn_topic >> async_("a  fan-out") >> fraud_q
    txn_topic >> async_("a") >> notify_q
    fraud_q >> async_("b  trigger (batch 10)") >> fraud_fn
    fraud_fn >> async_("c  set status", back=True) >> table
    fraud_fn >> async_("d  FraudAlert") >> alert_topic
    alert_topic >> async_("e", back=True) >> notify_q
    notify_q >> async_("f  long-poll", back=True) >> api

    # Failure handling, egress and observability
    fraud_q >> faint("after 3 failures") >> fraud_dlq
    notify_q >> faint("after 3 failures") >> notify_dlq
    api >> faint("egress", back=True) >> nat
    fraud_fn >> faint("logs", back=True) >> logs

    # Layout only: keep the NAT gateway beside the load balancer and Operations at the far right
    client >> HIDDEN >> nat
    alert_topic >> HIDDEN >> logs
    alert_topic >> HIDDEN >> cdk
