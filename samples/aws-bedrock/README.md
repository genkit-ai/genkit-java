# Genkit AWS Bedrock Sample

This sample demonstrates how to use Genkit with AWS Bedrock models.

## Features

- Multiple AWS Bedrock models (Amazon Nova, Claude, Llama, etc.)
- Tool/function calling
- Streaming responses
- Multi-turn conversations
- Model comparison

## Prerequisites

1. Java 21 or higher
2. Maven
3. AWS account with Bedrock access
4. AWS credentials configured
5. Model access granted in AWS Bedrock console

## AWS Configuration

### Option 1: Environment Variables

```bash
export AWS_ACCESS_KEY_ID=your-access-key
export AWS_SECRET_ACCESS_KEY=your-secret-key
export AWS_REGION=us-east-1
```

### Option 2: AWS Credentials File

Configure `~/.aws/credentials`:

```ini
[default]
aws_access_key_id = your-access-key
aws_secret_access_key = your-secret-key
region = us-east-1
```

### Option 3: IAM Role (for EC2/ECS/EKS)

No configuration needed - the application will use the instance profile.

## Running the Sample

```bash
./run.sh
```

Or manually:

```bash
mvn clean compile exec:java
```

## Available Endpoints

Once running, the following endpoints are available:

- Genkit UI: http://localhost:3100
- HTTP endpoints: http://localhost:8080

### Available Flows

1. **greeting** - Simple greeting flow
2. **tellJoke** - Generate a joke about a topic
3. **chat** - Chat with Claude on Bedrock
4. **weatherAssistant** - Weather assistant with tool use
5. **compareModels** - Compare responses from multiple models
6. **streamingDemo** - Demonstrate streaming responses

## Example Requests

### Greeting

```bash
curl -X POST http://localhost:8080/greeting \
  -H 'Content-Type: application/json' \
  -d '{"data": "World"}'
```

### Generate a Joke

```bash
curl -X POST http://localhost:8080/tellJoke \
  -H 'Content-Type: application/json' \
  -d '{"data": "programming"}'
```

### Chat

```bash
curl -X POST http://localhost:8080/chat \
  -H 'Content-Type: application/json' \
  -d '{"data": "What is AWS Bedrock?"}'
```

### Weather Assistant (with tool use)

```bash
curl -X POST http://localhost:8080/weatherAssistant \
  -H 'Content-Type: application/json' \
  -d '{"data": "What'\''s the weather like in Seattle?"}'
```

### Compare Models

```bash
curl -X POST http://localhost:8080/compareModels \
  -H 'Content-Type: application/json' \
  -d '{"data": "What is the meaning of life?"}'
```

### Streaming Demo

```bash
curl -X POST http://localhost:8080/streamingDemo \
  -H 'Content-Type: application/json' \
  -d '{"data": "Tell me a story about space exploration"}'
```

## Supported Models

This sample uses:

- `amazon.nova-pro-v1:0` - Amazon Nova Pro (multimodal)
- `anthropic.claude-3-5-sonnet-20241022-v2:0` - Claude 3.5 Sonnet
- `meta.llama3-3-70b-instruct-v1:0` - Llama 3.3 70B

See the plugin README for the full list of supported models.

## Requesting Model Access

Before using a model, you need to request access:

1. Go to AWS Bedrock console
2. Navigate to "Model access"
3. Click "Manage model access"
4. Select the models you want to use
5. Click "Request model access"
6. Wait for approval (usually instant)

## IAM Permissions

Your AWS IAM role/user needs these permissions:

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

## Troubleshooting

### "Access denied" error

Make sure you've requested access to the models in the AWS Bedrock console.

### "Region not supported" error

Some models are only available in specific regions. Try changing the region to `us-east-1` or `us-west-2`.

### "Credentials not found" error

Configure your AWS credentials using one of the methods described above.

## Learn More

- [AWS Bedrock Documentation](https://docs.aws.amazon.com/bedrock/)
- [Genkit Documentation](https://firebase.google.com/docs/genkit)
- [AWS SDK for Java](https://aws.amazon.com/sdk-for-java/)
