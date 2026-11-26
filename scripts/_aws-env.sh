# Shared settings for the aws-*.sh scripts (sourced, not run).
REGION=${AWS_REGION:-$(aws configure get region 2>/dev/null || echo eu-north-1)}
export AWS_REGION=$REGION
STACK=CardTxnStack

if [ -d /opt/homebrew/opt/openjdk@21 ] && ! java -version 2>&1 | grep -q '"21'; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@21
  export PATH="$JAVA_HOME/bin:$PATH"
fi

# Read a CloudFormation output of the deployed stack, e.g.: output FraudFunctionName
output() {
  aws cloudformation describe-stacks --stack-name "$STACK" \
    --query "Stacks[0].Outputs[?OutputKey=='$1'].OutputValue" --output text
}
