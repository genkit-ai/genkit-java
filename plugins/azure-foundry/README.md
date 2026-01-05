# Genkit Azure AI Foundry Plugin

Azure AI Foundry model plugin for [Genkit Java](https://genkit.dev).

This plugin provides integration with Azure AI Foundry models, including GPT-5, o1, o3-mini, GPT-4o, Grok, Llama, DeepSeek, Claude 4.x, and other models deployed in Azure AI Foundry.

## Installation

Add the dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>com.google.genkit</groupId>
    <artifactId>genkit-plugin-azure-foundry</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

## Quick Start

```java
import com.google.genkit.Genkit;
import com.google.genkit.plugins.azurefoundry.AzureFoundryPlugin;

// Create Genkit with Azure Foundry plugin
Genkit genkit = Genkit.builder()
    .plugin(AzureFoundryPlugin.create(
        "https://my-project.eastus.models.ai.azure.com",
        "your-api-key"))
    .build();

// Generate text with GPT-4
ModelResponse response = genkit.generate(
    GenerateOptions.builder()
        .model("azure-foundry/gpt-4o")
        .prompt("Hello, Azure AI Foundry!")
        .build());

System.out.println(response.getText());
```

## Configuration

### Using API Key

```java
import com.google.genkit.Genkit;
import com.google.genkit.plugins.azurefoundry.AzureFoundryPlugin;
import com.google.genkit.plugins.azurefoundry.AzureFoundryPluginOptions;

Genkit genkit = Genkit.builder()
    .plugin(new AzureFoundryPlugin(
        AzureFoundryPluginOptions.builder()
            .endpoint("https://my-project.eastus.models.ai.azure.com")
            .apiKey("your-api-key")
            .build()))
    .build();
```

### Using Azure Managed Identity (Recommended for Azure)

```java
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.identity.ManagedIdentityCredential;
import com.azure.identity.ManagedIdentityCredentialBuilder;

// Use default Azure credential (works with Managed Identity, Azure CLI, etc.)
Genkit genkit = Genkit.builder()
    .plugin(new AzureFoundryPlugin(
        AzureFoundryPluginOptions.builder()
            .endpoint("https://my-project.eastus.models.ai.azure.com")
            .credential(new DefaultAzureCredentialBuilder().build())
            .build()))
    .build();

// Or use Managed Identity explicitly
ManagedIdentityCredential credential = new ManagedIdentityCredentialBuilder().build();

Genkit genkit = Genkit.builder()
    .plugin(new AzureFoundryPlugin(
        AzureFoundryPluginOptions.builder()
            .endpoint("https://my-project.eastus.models.ai.azure.com")
            .credential(credential)
            .build()))
    .build();
```

### Using Environment Variables

Set the `AZURE_AI_FOUNDRY_ENDPOINT` environment variable:

```bash
export AZURE_AI_FOUNDRY_ENDPOINT=https://my-project.eastus.models.ai.azure.com
```

Then create the plugin:

```java
// Will use DefaultAzureCredential for authentication
Genkit genkit = Genkit.builder()
    .plugin(AzureFoundryPlugin.create())
    .build();
```

## Supported Models

### Azure OpenAI Models (Global Standard & Provisioned)
- **GPT-5**: `gpt-5`, `gpt-5-mini`, `gpt-5-turbo`
- **o1**: `o1`
- **o3-mini**: `o3-mini-high`, `o3-mini-medium`, `o3-mini-low`
- **GPT-4o**: `gpt-4o`, `gpt-4o-mini`
- **GPT-4**: `gpt-4-turbo`, `gpt-4`, `gpt-35-turbo`

### Azure Models (Sold Directly by Azure)
- **MAI-DS**: `mai-ds-r1` - Deterministic, precision-focused reasoning
- **Grok**: `grok-4`, `grok-4-fast-reasoning`, `grok-4-fast-non-reasoning`, `grok-3`, `grok-3-mini`
- **Llama**: `llama-3-3-70b-instruct`, `llama-4-maverick-17b-128e-instruct-fp8`
- **DeepSeek**: `deepseek-v3-0324`, `deepseek-v3-1`, `deepseek-r1-0528`
- **GPT-OSS**: `gpt-oss-120b`

### Partner and Community Models
- **Claude 4.x**: `claude-opus-4-5`, `claude-opus-4-1`, `claude-sonnet-4-5`, `claude-haiku-4-5`

> **Note:** Model availability varies by region and Azure subscription. Hub-based projects are limited to gpt-4o, gpt-4o-mini, gpt-4, and gpt-35-turbo. See [Azure AI Foundry Model Region Support](https://learn.microsoft.com/en-us/azure/ai-foundry/agents/concepts/model-region-support) for details.

## Usage Examples

### Basic Text Generation

```java
ModelResponse response = genkit.generate(
    GenerateOptions.builder()
        .model("azure-foundry/gpt-4o")
        .prompt("Explain quantum computing in simple terms")
        .build());

System.out.println(response.getText());
```

### Streaming Responses

```java
genkit.generateStream(
    GenerateOptions.builder()
        .model("azure-foundry/llama-3-1-70b-instruct")
        .prompt("Write a short story about a robot")
        .build(),
    chunk -> System.out.print(chunk.getText())
);
```

### With Configuration Options

```java
ModelResponse response = genkit.generate(
    GenerateOptions.builder()
        .model("azure-foundry/gpt-4o")
        .prompt("What are the top 3 programming languages?")
        .temperature(0.7)
        .maxOutputTokens(1000)
        .topP(0.9)
        .build());
```

### Multimodal Input (Vision)

```java
ModelResponse response = genkit.generate(
    GenerateOptions.builder()
        .model("azure-foundry/llama-3-2-90b-vision-instruct")
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
        .model("azure-foundry/gpt-4o")
        .prompt("What's the weather like in Seattle?")
        .tools(List.of(weatherTool))
        .build());
```

### Using Custom Deployments

If you have custom deployment names in your Azure AI Foundry project (e.g., "gpt-4.1", "my-model"), register them using `customModel()`:

```java
import com.google.genkit.Genkit;
import com.google.genkit.plugins.azurefoundry.AzureFoundryPlugin;
import com.google.genkit.plugins.azurefoundry.AzureFoundryPluginOptions;

// Register custom deployment names
Genkit genkit = Genkit.builder()
    .plugin(new AzureFoundryPlugin(
        AzureFoundryPluginOptions.builder()
            .endpoint("https://my-project.eastus.models.ai.azure.com")
            .apiKey("your-api-key")
            .build())
        .customModel("gpt-4.1")           // Your custom deployment name
        .customModel("my-custom-model"))  // Another custom deployment
    .build();

// Use your custom deployment
ModelResponse response = genkit.generate(
    GenerateOptions.builder()
        .model("azure-foundry/gpt-4.1")
        .prompt("Hello from custom deployment!")
        .build());
```

> **Note**: The deployment name in Azure must match what you pass to `customModel()`. You can find deployment names in the Azure AI Foundry portal under your project's deployments.

### Specifying API Version

Azure API versions control which features and behaviors are available. You can specify a custom API version:

```java
Genkit genkit = Genkit.builder()
    .plugin(new AzureFoundryPlugin(
        AzureFoundryPluginOptions.builder()
            .endpoint("https://my-project.eastus.models.ai.azure.com")
            .apiKey("your-api-key")
            .apiVersion("2025-01-01-preview")  // Specify API version
            .build()))
    .build();
```

If not specified, the plugin defaults to `"2024-10-01-preview"`. The API version is appended as a query parameter to all requests.

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
        .model("azure-foundry/gpt-4o")
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
        .model("azure-foundry/gpt-4o")
        .messages(conversation)
        .build());
```

## Prerequisites

### 1. Create an Azure AI Foundry Project

1. Go to [Azure AI Foundry portal](https://ai.azure.com)
2. Create a new project or select an existing one
3. Note your project endpoint (e.g., `https://my-project.eastus.models.ai.azure.com`)

### 2. Deploy Models

1. In your Azure AI Foundry project, go to "Model catalog"
2. Select the models you want to use
3. Click "Deploy" and follow the deployment wizard
4. Note the deployment names

### 3. Get Authentication Credentials

#### Option A: API Key
1. Go to your project's "Keys and Endpoint" section
2. Copy your API key

#### Option B: Managed Identity (Recommended)
1. Enable Managed Identity on your Azure resource (VM, App Service, etc.)
2. Grant the managed identity access to your Azure AI Foundry project
3. Use `DefaultAzureCredential` in your code

## Authentication

Azure AI Foundry supports multiple authentication methods:

### 1. API Key Authentication

Simple and straightforward, best for development:

```java
.apiKey("your-api-key")
```

### 2. Azure Managed Identity

Best for production on Azure:

```java
.credential(new DefaultAzureCredentialBuilder().build())
```

### 3. Azure CLI

For local development:

```bash
az login
```

Then use:

```java
.credential(new DefaultAzureCredentialBuilder().build())
```

### 4. Service Principal

For CI/CD pipelines:

```java
ClientSecretCredential credential = new ClientSecretCredentialBuilder()
    .tenantId("tenant-id")
    .clientId("client-id")
    .clientSecret("client-secret")
    .build();

.credential(credential)
```

## IAM Permissions

When using Managed Identity or service principals, assign the following role:

- **Cognitive Services User** - Allows calling the inference API

Or use this custom role definition:

```json
{
    "Name": "AI Foundry Inference User",
    "Description": "Can call Azure AI Foundry inference endpoints",
    "Actions": [
        "Microsoft.CognitiveServices/accounts/read",
        "Microsoft.CognitiveServices/accounts/*/read"
    ],
    "DataActions": [
        "Microsoft.CognitiveServices/accounts/OpenAI/inference/action"
    ]
}
```

## Endpoint Format

Azure AI Foundry endpoints follow this format:

```
https://<project-name>.<region>.models.ai.azure.com
```

Examples:
- `https://my-project.eastus.models.ai.azure.com`
- `https://prod-ai.westeurope.models.ai.azure.com`
- `https://dev-foundry.westus2.models.ai.azure.com`

### Azure OpenAI Service Support

This plugin also works with Azure OpenAI Service (Cognitive Services) endpoints:

```
https://<resource-name>.openai.azure.com
https://<resource-name>.cognitiveservices.azure.com
```

Example with Azure OpenAI Service:
```java
Genkit genkit = Genkit.builder()
    .plugin(new AzureFoundryPlugin(
        AzureFoundryPluginOptions.builder()
            .endpoint("https://my-resource.openai.azure.com")
            .apiKey("your-api-key")
            .apiVersion("2025-01-01-preview")  // Required for Azure OpenAI
            .build())
        .customModel("gpt-4"))  // Your deployment name
    .build();
```

**Note**: When using Azure OpenAI Service endpoints, the plugin automatically detects the endpoint type and adjusts the URL structure accordingly.

## Features

- ✅ Text generation
- ✅ Streaming responses
- ✅ Multimodal input (vision) - for supported models
- ✅ Tool/Function calling
- ✅ Multi-turn conversations
- ✅ System messages
- ✅ JSON mode
- ✅ Configurable parameters (temperature, top-p, max tokens)
- ✅ Token usage tracking
- ✅ Azure Managed Identity support
- ✅ Multiple authentication methods

## Differences from Azure OpenAI Plugin

While both plugins can work with OpenAI models on Azure, they serve different purposes:

| Feature | Azure Foundry | Azure OpenAI |
|---------|---------------|--------------|
| Endpoint | Azure AI Foundry project endpoints | Azure OpenAI service endpoints |
| Models | Multiple providers (OpenAI, Meta, Mistral, etc.) | OpenAI models only |
| Authentication | API key, Managed Identity | API key, Managed Identity |
| Best For | Multi-model AI applications | OpenAI-specific workloads |

## Requirements

- Java 21 or higher
- Azure subscription
- Azure AI Foundry project
- Models deployed in your project

## Troubleshooting

### "Unauthorized" error

- Check that your API key is correct
- Verify that your Managed Identity has the correct permissions
- Ensure you're using the correct endpoint URL

### "Model not found" error

- Verify that the model is deployed in your Azure AI Foundry project
- Check the model name matches your deployment name
- Ensure you have access to the model in your region

### "Quota exceeded" error

- Check your Azure AI Foundry quota limits
- Consider upgrading your subscription or requesting a quota increase

## Learn More

- [Azure AI Foundry Documentation](https://learn.microsoft.com/en-us/azure/ai-foundry/)
- [Azure AI Foundry SDK for Java](https://learn.microsoft.com/en-us/java/api/overview/azure/ai-projects-readme)
- [Genkit Documentation](https://genkit.dev/)
- [Azure Identity Documentation](https://learn.microsoft.com/en-us/java/api/overview/azure/identity-readme)

## License

Apache 2.0 - See LICENSE file for details
