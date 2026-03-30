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

## Features

- Multi-provider model access
- INFERENCE_PROFILE support for advanced models
- Text generation, streaming, tool calling

## Sample

See the [aws-bedrock sample](https://github.com/genkit-ai/genkit-java/tree/main/samples/aws-bedrock).
