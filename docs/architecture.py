"""
Simple system design diagram as code, using the official AWS architecture icons.

    pip install diagrams      # also needs Graphviz: brew install graphviz
    python docs/architecture.py

Writes docs/architecture.png.
Blue = request path (the client waits). Orange = event path (runs after the response).
"""
from pathlib import Path

from diagrams import Cluster, Diagram, Edge
from diagrams.aws.compute import Fargate, Lambda
from diagrams.aws.database import Dynamodb, ElasticacheForRedis
from diagrams.aws.general import Client, MobileClient
from diagrams.aws.integration import SNS, SQS
from diagrams.aws.network import ALB

FONT = "Helvetica"
SYNC = "#2c6fd1"
ASYNC = "#c2700f"


def sync(label=""):
    return Edge(label=label, color=SYNC, fontcolor=SYNC, penwidth="2", fontname=FONT, fontsize="13")


def event(label="", back=False):
    return Edge(label=label, color=ASYNC, fontcolor=ASYNC, style="dashed", penwidth="2", fontname=FONT,
                fontsize="13", constraint="false" if back else "true")


graph_attr = {"fontname": FONT, "fontsize": "22", "labelloc": "t", "pad": "0.5",
              "nodesep": "1.0", "ranksep": "1.2", "bgcolor": "white"}
node_attr = {"fontname": FONT, "fontsize": "13"}

with Diagram("Card Transaction Platform", filename=str(Path(__file__).with_name("architecture")),
             outformat="png", show=False, direction="LR", graph_attr=graph_attr, node_attr=node_attr):
    terminal = Client("Card terminal")

    with Cluster("AWS", graph_attr={"bgcolor": "#f7f9fc", "pencolor": "#232f3e", "style": "rounded",
                                    "fontname": FONT, "fontsize": "15", "margin": "24"}):
        alb = ALB("Load balancer")
        api = Fargate("Transaction API\nSpring WebFlux")
        redis = ElasticacheForRedis("Redis\ndaily spend")
        db = Dynamodb("DynamoDB\ntransactions")
        topic = SNS("SNS\nnew transaction")
        queue = SQS("SQS\nfraud check")
        fraud = Lambda("Lambda\nfraud rules")
        alerts = SNS("SNS\nfraud alerts")

    customer = MobileClient("Customer alert")

    # Request path: save, cache, publish, respond 202
    terminal >> sync("swipe") >> alb >> sync() >> api
    api >> sync("save") >> db
    api >> sync("add spend") >> redis
    api >> sync("publish") >> topic

    # Event path: fraud check after the response
    topic >> event() >> queue >> event() >> fraud
    fraud >> event("approve / flag", back=True) >> db
    fraud >> event("if flagged") >> alerts >> event() >> customer
