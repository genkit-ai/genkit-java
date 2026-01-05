# Genkit Azure AI Foundry Sample

This sample demonstrates how to use Genkit with Azure AI Foundry models.

## Features

- Multiple Azure AI Foundry models (GPT-4, Llama, Mistral, etc.)
- Tool/function calling
- Streaming responses
- Multi-turn conversations
- Model comparison
- Azure Managed Identity support

## Prerequisites

1. Java 21 or higher
2. Maven
3. Azure subscription
4. Azure AI Foundry project
5. Models deployed in your project
6. Azure credentials configured

## Azure AI Foundry Setup

### 1. Create a Project

1. Go to [Azure AI Foundry portal](https://ai.azure.com)
2. Click "Create new project"
3. Fill in project details
4. Note your project endpoint (e.g., `https://my-project.eastus.models.ai.azure.com`)

### 2. Deploy Models

1. In your project, go to "Model catalog"
2. Select models to deploy (e.g., GPT-4o, Llama 3.1 70B, Mistral Large)
3. Click "Deploy" for each model
4. Wait for deployment to complete

### 3. Get Authentication Credentials

#### Option A: API Key

1. In your project, go to "Keys and Endpoint"
2. Copy your API key
3. Set environment variable:

```bash
export AZURE_API_KEY=your-api-key
```

#### Option B: Azure CLI (for local development)

```bash
az login
```

#### Option C: Managed Identity (for Azure resources)

No additional setup needed - works automatically on Azure VMs, App Service, etc.

## Configuration

Set the required environment variable:

```bash
export AZURE_AI_FOUNDRY_ENDPOINT=https://my-project.eastus.models.ai.azure.com
```

Optional - if using API key authentication:

```bash
export AZURE_API_KEY=your-api-key
```

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
3. **chat** - Chat with GPT-4 on Azure Foundry
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
  -d '{"data": "Azure"}'
```

### Chat

```bash
curl -X POST http://localhost:8080/chat \
  -H 'Content-Type: application/json' \
  -d '{"data": "What is Azure AI Foundry?"}'
```

### Weather Assistant (with tool use)

```bash
curl -X POST http://localhost:8080/weatherAssistant \
  -H 'Content-Type: application/json' \
  -d '{"data": "What'\''s the weather like in Paris?"}'
```

### Compare Models

```bash
curl -X POST http://localhost:8080/compareModels \
  -H 'Content-Type: application/json' \
  -d '{"data": "What is machine learning?"}'
```

### Streaming Demo

```bash
curl -X POST http://localhost:8080/streamingDemo \
  -H 'Content-Type: application/json' \
  -d '{"data": "Tell me about quantum computing"}'
```

## Supported Models

This sample uses:

- `gpt-4o` - GPT-4 Optimized
- `llama-3-1-70b-instruct` - Llama 3.1 70B Instruct
- `mistral-large` - Mistral Large

See the plugin README for the full list of supported models.

## Authentication Methods

### 1. API Key (Simple)

```bash
export AZURE_AI_FOUNDRY_ENDPOINT=https://my-project.eastus.models.ai.azure.com
export AZURE_API_KEY=your-api-key
```

In code, uncomment:
```java
.apiKey("your-api-key")
```

### 2. Azure CLI (Local Development)

```bash
az login
export AZURE_AI_FOUNDRY_ENDPOINT=https://my-project.eastus.models.ai.azure.com
```

### 3. Managed Identity (Production on Azure)

No configuration needed when running on Azure VMs, App Service, AKS, etc.

### 4. Service Principal (CI/CD)

```bash
export AZURE_CLIENT_ID=your-client-id
export AZURE_TENANT_ID=your-tenant-id
export AZURE_CLIENT_SECRET=your-client-secret
export AZURE_AI_FOUNDRY_ENDPOINT=https://my-project.eastus.models.ai.azure.com
```

## IAM Permissions

When using Managed Identity or service principals, assign one of these roles:

1. **Cognitive Services User** (built-in role)
2. **Cognitive Services OpenAI User** (built-in role)

Or create a custom role:

```json
{
    "Name": "AI Foundry Inference User",
    "Actions": [],
    "DataActions": [
        "Microsoft.CognitiveServices/accounts/OpenAI/inference/action"
    ]
}
```

## Troubleshooting

### "Endpoint not set" error

Set the `AZURE_AI_FOUNDRY_ENDPOINT` environment variable:

```bash
export AZURE_AI_FOUNDRY_ENDPOINT=https://my-project.eastus.models.ai.azure.com
```

### "Unauthorized" or "403 Forbidden" error

- Check that your API key is correct
- Verify that your Managed Identity has the correct IAM role
- Ensure you're logged in with `az login` if using Azure CLI

### "Model not found" error

- Verify the model is deployed in your Azure AI Foundry project
- Check that the model name in the code matches your deployment
- Go to your project in Azure AI Foundry portal and verify deployments

### "Quota exceeded" error

- Check your Azure AI Foundry quota limits in the portal
- Consider upgrading your subscription
- Request a quota increase through Azure support

## Project Structure

```
azure-foundry/
├── pom.xml                 # Maven configuration
├── run.sh                  # Run script
├── README.md               # This file
└── src/
    └── main/
        └── java/
            └── com/google/genkit/samples/
                └── AzureFoundrySample.java
```

## Learn More

- [Azure AI Foundry Documentation](https://learn.microsoft.com/en-us/azure/ai-foundry/)
- [Azure AI Foundry Portal](https://ai.azure.com)
- [Genkit Documentation](https://genkit.dev/)
- [Azure Identity Documentation](https://learn.microsoft.com/en-us/java/api/overview/azure/identity-readme)

## Common Issues

### Model deployment takes time

After deploying a model in Azure AI Foundry, it may take a few minutes before it's ready to use. Wait for the deployment status to show "Succeeded".

### Region availability

Not all models are available in all Azure regions. If a model isn't available, try:
1. Deploying in a different region
2. Checking model availability in the Azure AI Foundry catalog
3. Requesting access to the model if needed

### Managed Identity not working

For Managed Identity to work:
1. Your Azure resource must have a managed identity enabled
2. The managed identity must be assigned the correct IAM role on your Azure AI Foundry project
3. You must be running on an Azure resource (VM, App Service, AKS, etc.)
