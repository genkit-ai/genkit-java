---
title: Dev UI & CLI
description: Local developer tools for testing and debugging AI workflows.
---

Genkit provides a developer UI and CLI for testing, debugging, and inspecting your AI logic.

## Installing the CLI

```bash
npm install -g genkit
```

## Starting the Dev UI

Run your application with the Genkit CLI to launch the developer UI:

```bash
genkit start -- ./run.sh
# Or:
genkit start -- mvn exec:java
```

The Dev UI launches at `http://localhost:4000` by default.

## What the Dev UI provides

- **List all registered actions** — Flows, models, tools, prompts, retrievers, evaluators
- **Run actions with test inputs** — Execute flows interactively
- **View traces** — Inspect execution logs with full span details
- **Manage datasets** — Create and manage evaluation datasets
- **Run evaluations** — Execute evaluators and review results

## Reflection server

When running in dev mode (`devMode(true)`), Genkit starts a reflection server on port 3100 (configurable via `reflectionPort()`). The Dev UI connects to this server.

```java
Genkit genkit = Genkit.builder()
    .options(GenkitOptions.builder()
        .devMode(true)
        .reflectionPort(3100)  // Default port
        .build())
    .build();
```

## Running flows from the CLI

```bash
# Run a flow
genkit flow:run tellJoke '"pirates"'

# Run with streaming output
genkit flow:run tellJoke '"pirates"' -s
```
