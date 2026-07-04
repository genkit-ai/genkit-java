---
title: Azure AI Foundry
description: Access Azure-hosted AI models including GPT-5, o1, Llama, and more.
---

The Azure AI Foundry plugin provides access to models hosted on Azure.

## Installation

```xml
<dependency>
    <groupId>com.google.genkit</groupId>
    <artifactId>genkit-plugin-azure-foundry</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

## Configuration

```bash
export AZURE_AI_FOUNDRY_ENDPOINT=https://your-endpoint.inference.ai.azure.com
export AZURE_AI_FOUNDRY_API_KEY=your-api-key
```

Alternatively, supports Azure Managed Identity authentication.

## Usage

```java
import com.google.genkit.plugins.azurefoundry.AzureFoundryPlugin;

Genkit genkit = Genkit.builder()
    .plugin(AzureFoundryPlugin.create())
    .build();

ModelResponse response = genkit.generate(
    GenerateOptions.builder()
        .model("azure-foundry/gpt-4o")
        .prompt("Tell me about AI")
        .build());
```

## Available models

- GPT-5, GPT-4o, o1, o3-mini
- Grok, Llama, DeepSeek
- Claude 4.x via Azure marketplace
- And many more

## Session store

The plugin also ships `CosmosSessionStore`, an Azure Cosmos DB-backed agent session store. Construct it and pass it to an agent's `.store(...)` to persist server-managed sessions in Cosmos DB:

```java
import com.google.genkit.plugins.azurefoundry.session.CosmosSessionStore;
import com.azure.cosmos.CosmosClientBuilder;

CosmosSessionStore<Map<String, Object>> store = new CosmosSessionStore<>(
    new CosmosClientBuilder()
        .endpoint(System.getenv("COSMOS_ENDPOINT"))
        .key(System.getenv("COSMOS_KEY"))
        .buildClient());
```

See [Session Stores](../../agents/session-stores#cosmossessionstore) for options and the agents-cosmos-session sample.

## Sample

See the [azure-foundry sample](https://github.com/genkit-ai/genkit-java/tree/main/samples/azure-foundry).
