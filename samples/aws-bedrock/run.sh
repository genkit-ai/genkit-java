#!/bin/bash

# Genkit AWS Bedrock Sample Runner
# This script runs the AWS Bedrock sample application

# Check if AWS credentials are configured
if [ -z "$AWS_ACCESS_KEY_ID" ] && [ ! -f ~/.aws/credentials ]; then
    echo "Warning: AWS credentials not found!"
    echo "Please set AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY environment variables"
    echo "or configure ~/.aws/credentials file"
    echo ""
fi

# Build and run
echo "Building and running AWS Bedrock sample..."
mvn clean compile exec:java
