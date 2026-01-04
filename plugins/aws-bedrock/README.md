# Genkit AWS Bedrock Plugin

AWS Bedrock model plugin for [Genkit Java](https://genkit.dev).

This plugin provides integration with 90+ AWS Bedrock text generation models from multiple providers including Amazon, Anthropic, Meta, Mistral AI, Cohere, AI21 Labs, DeepSeek, Google, Qwen, NVIDIA, OpenAI, Writer, MiniMax, Moonshot, and TwelveLabs.

## Installation

Add the dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>com.google.genkit</groupId>
    <artifactId>genkit-plugin-aws-bedrock</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

## Quick Start

```java
import com.google.genkit.Genkit;
import com.google.genkit.plugins.awsbedrock.AwsBedrockPlugin;

// Create Genkit with AWS Bedrock plugin
Genkit genkit = Genkit.builder()
    .plugin(AwsBedrockPlugin.create("us-east-1"))
    .build();

// Generate text with Amazon Nova
ModelResponse response = genkit.generate(
    GenerateOptions.builder()
        .model("aws-bedrock/amazon.nova-lite-v1:0")
        .prompt("Hello, AWS Bedrock!")
        .build());

System.out.println(response.getText());
```

## Supported Models

### Amazon Models (ON_DEMAND)
- **Nova**: `amazon.nova-pro-v1:0`, `amazon.nova-lite-v1:0`, `amazon.nova-micro-v1:0`, `amazon.nova-premier-v1:0`, `amazon.nova-2-lite-v1:0`, `amazon.nova-sonic-v1:0`, `amazon.nova-2-sonic-v1:0`
- **Titan**: `amazon.titan-tg1-large`, `amazon.titan-text-express-v1`

### Anthropic Models (INFERENCE_PROFILE required for Claude 4.x and 3.5+)
- **Claude 4**: `anthropic.claude-sonnet-4-20250514-v1:0`, `anthropic.claude-sonnet-4-5-20250929-v1:0`, `anthropic.claude-haiku-4-5-20251001-v1:0`, `anthropic.claude-opus-4-1-20250805-v1:0`, `anthropic.claude-opus-4-5-20251101-v1:0`
- **Claude 3.x**: `anthropic.claude-3-7-sonnet-20250219-v1:0`, `anthropic.claude-3-5-sonnet-20241022-v2:0`, `anthropic.claude-3-5-sonnet-20240620-v1:0`, `anthropic.claude-3-5-haiku-20241022-v1:0`
- **Claude 3** (ON_DEMAND): `anthropic.claude-3-opus-20240229-v1:0`, `anthropic.claude-3-sonnet-20240229-v1:0`, `anthropic.claude-3-haiku-20240307-v1:0`

### Meta Llama Models (INFERENCE_PROFILE)
- **Llama 4**: `meta.llama4-scout-17b-instruct-v1:0`, `meta.llama4-maverick-17b-instruct-v1:0`
- **Llama 3.3**: `meta.llama3-3-70b-instruct-v1:0`
- **Llama 3.2**: `meta.llama3-2-90b-instruct-v1:0`, `meta.llama3-2-11b-instruct-v1:0`, `meta.llama3-2-3b-instruct-v1:0`, `meta.llama3-2-1b-instruct-v1:0`
- **Llama 3.1**: `meta.llama3-1-70b-instruct-v1:0`, `meta.llama3-1-8b-instruct-v1:0`
- **Llama 3** (ON_DEMAND): `meta.llama3-70b-instruct-v1:0`, `meta.llama3-8b-instruct-v1:0`

### Mistral AI Models
- **Pixtral** (INFERENCE_PROFILE): `mistral.pixtral-large-2502-v1:0`
- **Mistral Large** (ON_DEMAND): `mistral.mistral-large-3-675b-instruct`, `mistral.mistral-large-2402-v1:0`
- **Mistral Medium**: `mistral.magistral-small-2509`, `mistral.mistral-small-2402-v1:0`
- **Ministral**: `mistral.ministral-3-14b-instruct`, `mistral.ministral-3-8b-instruct`, `mistral.ministral-3-3b-instruct`
- **Mixtral**: `mistral.mixtral-8x7b-instruct-v0:1`, `mistral.mistral-7b-instruct-v0:2`
- **Voxtral**: `mistral.voxtral-mini-3b-2507`, `mistral.voxtral-small-24b-2507`

### Other Providers (ON_DEMAND)
- **DeepSeek**: `deepseek.r1-v1:0` (INFERENCE_PROFILE)
- **Cohere**: `cohere.command-r-plus-v1:0`, `cohere.command-r-v1:0`
- **AI21 Labs**: `ai21.jamba-1-5-large-v1:0`, `ai21.jamba-1-5-mini-v1:0`
- **Google Gemma**: `google.gemma-3-27b-it`, `google.gemma-3-12b-it`, `google.gemma-3-4b-it`
- **Qwen**: `qwen.qwen3-next-80b-a3b`, `qwen.qwen3-vl-235b-a22b`, `qwen.qwen3-32b-v1:0`, `qwen.qwen3-coder-30b-a3b-v1:0`
- **NVIDIA Nemotron**: `nvidia.nemotron-nano-12b-v2`, `nvidia.nemotron-nano-9b-v2`, `nvidia.nemotron-nano-3-30b`
- **OpenAI**: `openai.gpt-oss-120b-1:0`, `openai.gpt-oss-20b-1:0`, `openai.gpt-oss-safeguard-120b`, `openai.gpt-oss-safeguard-20b`
- **Writer** (INFERENCE_PROFILE): `writer.palmyra-x5-v1:0`, `writer.palmyra-x4-v1:0`
- **MiniMax**: `minimax.minimax-m2`
- **Moonshot**: `moonshot.kimi-k2-thinking`
- **TwelveLabs**: `twelvelabs.pegasus-1-2-v1:0`

**Note**: Models marked with "INFERENCE_PROFILE" require using an AWS Bedrock inference profile ARN instead of the model ID directly. See [AWS Bedrock Inference Profiles](https://docs.aws.amazon.com/bedrock/latest/userguide/cross-region-inference.html) for details.

## Configuration

### Using Default AWS Credentials

The plugin uses the AWS Default Credentials Provider Chain, which checks:
1. Environment variables (`AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY`)
2. Java system properties
3. AWS credentials file (`~/.aws/credentials`)
4. IAM role for EC2/ECS/EKS

```java
import com.google.genkit.Genkit;
import com.google.genkit.plugins.awsbedrock.AwsBedrockPlugin;
import com.google.genkit.plugins.awsbedrock.AwsBedrockPluginOptions;

// Default credentials with custom region
Genkit genkit = Genkit.builder()
    .plugin(new AwsBedrockPlugin(
        AwsBedrockPluginOptions.builder()
            .region("us-west-2")
            .build()))
    .build();
```

### Using Explicit Credentials

```java
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;

AwsBasicCredentials credentials = AwsBasicCredentials.create(
    "your-access-key-id",
    "your-secret-access-key"
);

Genkit genkit = Genkit.builder()
    .plugin(new AwsBedrockPlugin(
        AwsBedrockPluginOptions.builder()
            .region(Region.US_EAST_1)
            .credentialsProvider(StaticCredentialsProvider.create(credentials))
            .build()))
    .build();
```

### Using IAM Role (Recommended for AWS Environments)

When running on AWS (EC2, ECS, EKS, Lambda), use IAM roles:

```java
// Credentials will be automatically obtained from the IAM role
Genkit genkit = Genkit.builder()
    .plugin(AwsBedrockPlugin.create("us-east-1"))
    .build();
```

## Using Inference Profiles

Some models require using AWS Bedrock **Inference Profiles** for cross-region inference. Inference profiles enable routing requests across multiple AWS regions for improved availability and throughput.

### Models Requiring Inference Profiles

- **Claude 4.x**: All Claude Sonnet 4.x, Claude Haiku 4.5, Claude Opus 4.x models
- **Claude 3.5+**: Claude 3.5 Sonnet v2, Claude 3.7 Sonnet
- **Meta Llama 3.1+**: Llama 3.1, 3.2, 3.3, 4.x models
- **DeepSeek R1**: All versions
- **Mistral Pixtral**: Pixtral Large
- **Writer**: Palmyra models

### Using Inference Profile IDs

Inference profile IDs have the format: `{region-prefix}.{provider}.{model-name}`

```java
// Using US inference profile for Claude Sonnet 4
ModelResponse response = genkit.generate(
    GenerateOptions.builder()
        .model("aws-bedrock/us.anthropic.claude-sonnet-4-20250514-v1:0")
        .prompt("Hello!")
        .build());

// Using cross-region inference profile for Llama
ModelResponse response = genkit.generate(
    GenerateOptions.builder()
        .model("aws-bedrock/us.meta.llama3-3-70b-instruct-v1:0")
        .prompt("Hello!")
        .build());
```

### Common Inference Profile Prefixes

- `us.*` - US cross-region profile (routes across US regions)
- `eu.*` - EU cross-region profile (routes across EU regions)
- `apac.*` - Asia Pacific cross-region profile

### Using Custom Models or Profiles

For models not in the default list or custom inference profiles:

```java
AwsBedrockPlugin plugin = AwsBedrockPlugin.create("us-east-1");
AwsBedrockModel customModel = plugin.customModel("us.anthropic.claude-sonnet-4-5-v2:0");

// Register with Genkit
Genkit genkit = Genkit.builder()
    .plugin(plugin)
    .build();

// Use the custom model
ModelResponse response = genkit.generate(
    GenerateOptions.builder()
        .model("aws-bedrock/us.anthropic.claude-sonnet-4-5-v2:0")
        .prompt("Hello!")
        .build());
```

### Finding Inference Profiles

List available inference profiles in your region:

```bash
aws bedrock list-inference-profiles --region us-east-1
```

For more information, see [AWS Bedrock Cross-Region Inference](https://docs.aws.amazon.com/bedrock/latest/userguide/cross-region-inference.html).

## Usage Examples

### Using Models with ON_DEMAND Support

```java
// Amazon Nova (ON_DEMAND)
ModelResponse response = genkit.generate(
    GenerateOptions.builder()
        .model("aws-bedrock/amazon.nova-lite-v1:0")
        .prompt("Explain quantum computing")
        .build());

// Claude 3 Haiku (ON_DEMAND)
ModelResponse response = genkit.generate(
    GenerateOptions.builder()
        .model("aws-bedrock/anthropic.claude-3-haiku-20240307-v1:0")
        .prompt("Write a haiku about coding")
        .build());
```

### Using Models with Inference Profiles

```java
// Claude Sonnet 4 (requires inference profile)
ModelResponse response = genkit.generate(
    GenerateOptions.builder()
        .model("aws-bedrock/us.anthropic.claude-sonnet-4-20250514-v1:0")
        .prompt("Explain machine learning")
        .build());

// Llama 3.3 (requires inference profile)
ModelResponse response = genkit.generate(
    GenerateOptions.builder()
        .model("aws-bedrock/us.meta.llama3-3-70b-instruct-v1:0")
        .prompt("What is artificial intelligence?")
        .build());
```
import software.amazon.awssdk.auth.credentials.InstanceProfileCredentialsProvider;

Genkit genkit = Genkit.builder()
    .plugin(new AwsBedrockPlugin(
        AwsBedrockPluginOptions.builder()
            .region("us-east-1")
            .credentialsProvider(InstanceProfileCredentialsProvider.create())
            .build()))
    .build();
```

## Supported Models

### Amazon Nova Models
- `amazon.nova-pro-v1:0` - Nova Pro (multimodal, latest generation)
- `amazon.nova-lite-v1:0` - Nova Lite (fast and efficient)
- `amazon.nova-micro-v1:0` - Nova Micro (ultra-fast)

### Amazon Titan Models
- `amazon.titan-text-premier-v1:0` - Titan Text Premier
- `amazon.titan-text-express-v1` - Titan Text Express
- `amazon.titan-text-lite-v1` - Titan Text Lite

### Anthropic Claude Models (on Bedrock)
- `anthropic.claude-3-5-sonnet-20241022-v2:0` - Claude 3.5 Sonnet v2 (latest)
- `anthropic.claude-3-5-sonnet-20240620-v1:0` - Claude 3.5 Sonnet
- `anthropic.claude-3-5-haiku-20241022-v1:0` - Claude 3.5 Haiku
- `anthropic.claude-3-opus-20240229-v1:0` - Claude 3 Opus
- `anthropic.claude-3-sonnet-20240229-v1:0` - Claude 3 Sonnet
- `anthropic.claude-3-haiku-20240307-v1:0` - Claude 3 Haiku
- `anthropic.claude-v2:1` - Claude 2.1
- `anthropic.claude-v2` - Claude 2.0

### AI21 Jurassic Models
- `ai21.jamba-1-5-large-v1:0` - Jamba 1.5 Large
- `ai21.jamba-1-5-mini-v1:0` - Jamba 1.5 Mini
- `ai21.j2-ultra-v1` - Jurassic-2 Ultra
- `ai21.j2-mid-v1` - Jurassic-2 Mid

### Meta Llama Models
- `meta.llama3-3-70b-instruct-v1:0` - Llama 3.3 70B Instruct
- `meta.llama3-2-90b-instruct-v1:0` - Llama 3.2 90B Instruct
- `meta.llama3-2-11b-instruct-v1:0` - Llama 3.2 11B Instruct
- `meta.llama3-2-3b-instruct-v1:0` - Llama 3.2 3B Instruct
- `meta.llama3-2-1b-instruct-v1:0` - Llama 3.2 1B Instruct
- `meta.llama3-1-405b-instruct-v1:0` - Llama 3.1 405B Instruct
- `meta.llama3-1-70b-instruct-v1:0` - Llama 3.1 70B Instruct
- `meta.llama3-1-8b-instruct-v1:0` - Llama 3.1 8B Instruct

### Cohere Command Models
- `cohere.command-r-plus-v1:0` - Command R+
- `cohere.command-r-v1:0` - Command R
- `cohere.command-text-v14` - Command (text)
- `cohere.command-light-text-v14` - Command Light

### Mistral Models
- `mistral.mistral-large-2407-v1:0` - Mistral Large (July 2024)
- `mistral.mistral-large-2402-v1:0` - Mistral Large (Feb 2024)
- `mistral.mistral-small-2402-v1:0` - Mistral Small
- `mistral.mixtral-8x7b-instruct-v0:1` - Mixtral 8x7B Instruct
- `mistral.mistral-7b-instruct-v0:2` - Mistral 7B Instruct

## Usage Examples

### Basic Text Generation

```java
ModelResponse response = genkit.generate(
    GenerateOptions.builder()
        .model("aws-bedrock/amazon.nova-pro-v1:0")
        .prompt("Explain quantum computing in simple terms")
        .build());

System.out.println(response.getText());
```

### Streaming Responses

```java
genkit.generateStream(
    GenerateOptions.builder()
        .model("aws-bedrock/meta.llama3-3-70b-instruct-v1:0")
        .prompt("Write a short story about a robot")
        .build(),
    chunk -> System.out.print(chunk.getText())
);
```

### With Configuration Options

```java
ModelResponse response = genkit.generate(
    GenerateOptions.builder()
        .model("aws-bedrock/anthropic.claude-3-5-sonnet-20241022-v2:0")
        .prompt("What are the top 3 programming languages?")
        .temperature(0.7)
        .maxOutputTokens(1000)
        .topP(0.9)
        .stopSequences(List.of("\n\n"))
        .build());
```

### Multimodal Input (Vision)

```java
ModelResponse response = genkit.generate(
    GenerateOptions.builder()
        .model("aws-bedrock/amazon.nova-pro-v1:0")
        .messages(List.of(
            Message.builder()
                .role(Role.USER)
                .content(List.of(
                    Part.text("What's in this image?"),
                    Part.media(Media.builder()
                        .url("data:image/png;base64,iVBORw0KG...")
                        .contentType("image/png")
                        .build())
                ))
                .build()
        ))
        .build());
```

### Using Tools

```java
Tool weatherTool = Tool.builder()
    .name("get_weather")
    .description("Get the current weather for a location")
    .inputSchema(Map.of(
        "type", "object",
        "properties", Map.of(
            "location", Map.of("type", "string")
        ),
        "required", List.of("location")
    ))
    .build();

ModelResponse response = genkit.generate(
    GenerateOptions.builder()
        .model("aws-bedrock/anthropic.claude-3-5-sonnet-20241022-v2:0")
        .prompt("What's the weather like in Seattle?")
        .tools(List.of(weatherTool))
        .build());
```

### Multi-turn Conversation

```java
List<Message> conversation = new ArrayList<>();

// First turn
conversation.add(Message.builder()
    .role(Role.USER)
    .content(List.of(Part.text("What is the capital of France?")))
    .build());

ModelResponse response1 = genkit.generate(
    GenerateOptions.builder()
        .model("aws-bedrock/cohere.command-r-plus-v1:0")
        .messages(conversation)
        .build());

conversation.add(response1.getCandidates().get(0).getMessage());

// Second turn
conversation.add(Message.builder()
    .role(Role.USER)
    .content(List.of(Part.text("What is its population?")))
    .build());

ModelResponse response2 = genkit.generate(
    GenerateOptions.builder()
        .model("aws-bedrock/cohere.command-r-plus-v1:0")
        .messages(conversation)
        .build());
```

## IAM Permissions

Your AWS IAM role/user needs the following permissions:

```json
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Effect": "Allow",
            "Action": [
                "bedrock:InvokeModel",
                "bedrock:InvokeModelWithResponseStream"
            ],
            "Resource": [
                "arn:aws:bedrock:*::foundation-model/*"
            ]
        }
    ]
}
```

## Model Access

Before using a model, you need to request access in the AWS Bedrock console:
1. Go to AWS Bedrock console
2. Navigate to "Model access"
3. Request access for the models you want to use
4. Wait for approval (usually instant for most models)

## Cross-Region Inference

Some models support cross-region inference for better availability and performance. The plugin automatically uses the region specified in the configuration.

## Features

- ✅ Text generation
- ✅ Streaming responses
- ✅ Multimodal input (vision)
- ✅ Tool/Function calling
- ✅ Multi-turn conversations
- ✅ System messages
- ✅ JSON mode
- ✅ Configurable parameters (temperature, top-p, max tokens)
- ✅ Token usage tracking

## Requirements

- Java 21 or higher
- AWS SDK 2.x
- Active AWS account with Bedrock access
- Model access granted in AWS Bedrock console

## License

Apache 2.0 - See LICENSE file for details
