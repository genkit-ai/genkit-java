---
title: AWS Bedrock
description: Access 90+ models through AWS Bedrock including Nova, Claude, Llama, and Mistral.
---

The AWS Bedrock plugin provides access to 90+ AI models available on AWS Bedrock.

## Installation

```xml
<dependency>
    <groupId>com.google.genkit</groupId>
    <artifactId>genkit-plugin-aws-bedrock</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

## Configuration

Uses the AWS Default Credentials Provider Chain. Configure credentials via environment variables, shared credentials file, or IAM roles.

```bash
export AWS_ACCESS_KEY_ID=your-key
export AWS_SECRET_ACCESS_KEY=your-secret
export AWS_REGION=us-east-1
```

## Usage

```java
import com.google.genkit.plugins.awsbedrock.AwsBedrockPlugin;

Genkit genkit = Genkit.builder()
    .plugin(AwsBedrockPlugin.create())
    .build();

ModelResponse response = genkit.generate(
    GenerateOptions.builder()
        .model("aws-bedrock/amazon.nova-pro-v1:0")
        .prompt("Tell me about AI")
        .build());
```

## Available model families

- **Amazon Nova** — Nova Pro, Nova Lite, Nova Micro
- **Anthropic Claude** — Claude 4.5, Claude 4, Claude 3 families
- **Meta Llama** — Llama 3, Llama 4
- **Mistral** — Mistral Large, Small, Mixtral
- **Cohere** — Command R+, Command R
- **OpenAI** — Via Bedrock marketplace
- And many more...

## Embeddings

Bedrock embedding models (Amazon Titan and Cohere Embed) are available via the `InvokeModel` API:

- `aws-bedrock/amazon.titan-embed-text-v2:0`
- `aws-bedrock/cohere.embed-english-v3`
- `aws-bedrock/cohere.embed-multilingual-v3`

```java
EmbedResponse response =
    genkit.embed("aws-bedrock/amazon.titan-embed-text-v2:0", documents);
```

## Features

- Multi-provider model access
- INFERENCE_PROFILE support for advanced models
- Text generation, streaming, tool calling

## Session store

The plugin also ships `DynamoDbSessionStore`, a DynamoDB-backed agent session store. Construct it and pass it to an agent's `.store(...)` to persist server-managed sessions in DynamoDB:

```java
import com.google.genkit.plugins.awsbedrock.session.DynamoDbSessionStore;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

DynamoDbSessionStore<Map<String, Object>> store =
    new DynamoDbSessionStore<>(DynamoDbClient.create());
```

See [Session Stores](../../agents/session-stores#dynamodbsessionstore) for options and the agents-dynamodb-session sample.

## Sample

See the [aws-bedrock sample](https://github.com/genkit-ai/genkit-java/tree/main/samples/aws-bedrock).
