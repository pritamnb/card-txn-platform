package io.github.pritamnb.cardtxn.infra;

import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;

public final class InfraApp {
    public static void main(String[] args) {
        App app = new App();
        StackProps.Builder props = StackProps.builder()
                .description("Card transaction + fraud alert platform (demo)");
        // When deploying with credentials, the CLI sets these and the stack is pinned to that account/region.
        // Without them (e.g. `cdk synth` in CI) the template stays environment-agnostic.
        String account = System.getenv("CDK_DEFAULT_ACCOUNT");
        if (account != null) {
            props.env(Environment.builder().account(account).region(System.getenv("CDK_DEFAULT_REGION")).build());
        }
        new CardTxnStack(app, "CardTxnStack", props.build());
        app.synth();
    }
}
