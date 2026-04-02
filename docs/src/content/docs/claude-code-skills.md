---
title: Claude Code Skills
description: Use Genkit Java skills with Claude Code for AI-assisted development.
---

Genkit Java ships with [Claude Code skills](https://docs.anthropic.com/en/docs/claude-code/skills) — structured knowledge files that teach Claude Code how to work effectively with the framework.

## Available skills

| Skill | Description | Audience |
|-------|-------------|----------|
| **building-ai-apps-with-genkit-java** | Build AI-powered apps with Genkit Java: setup, flows, generation, tools, RAG, prompts, agents, deployment | App developers |
| **developing-genkit-java** | Contribute to the framework: architecture, plugin development, core abstractions, conventions | Framework contributors |

## Installation

### Option 1: Install from GitHub

Install a specific skill:

```bash
# For app developers
npx skills add https://github.com/genkit-ai/genkit-java --skill building-ai-apps-with-genkit-java

# For framework contributors
npx skills add https://github.com/genkit-ai/genkit-java --skill developing-genkit-java
```

Skills are sourced from the [`skills/`](https://github.com/genkit-ai/genkit-java/tree/main/skills) directory and installed into your local Claude Code configuration.

### Option 2: Manual setup

Copy the skill folders into your Claude Code skills directory:

```bash
# Clone the repo (if not already)
git clone https://github.com/genkit-ai/genkit-java.git

# Copy skills to your Claude Code config
cp -r genkit-java/skills/* ~/.claude/skills/
```

## Usage

Once installed, Claude Code automatically activates the relevant skill based on your prompts. For example:

- *"Create a new Genkit Java app with OpenAI that summarizes text"* → triggers the **building-ai-apps-with-genkit-java** skill
- *"Add a new Anthropic plugin to the Genkit Java framework"* → triggers the **developing-genkit-java** skill

You can also invoke a skill explicitly in Claude Code:

```
/skill building-ai-apps-with-genkit-java "Build a RAG pipeline with Pinecone and Gemini"
```

## What's inside

### building-ai-apps-with-genkit-java

Covers everything an app developer needs:

- **Quick start** — minimal `pom.xml`, application code, run commands
- **Provider setup** — all 12+ providers with Maven coordinates, env vars, model names
- **Generation API** — text, streaming, structured output, multi-turn
- **Flows** — defining, exposing as HTTP endpoints
- **Tools** — function calling with typed I/O
- **DotPrompt** — `.prompt` files with Handlebars templates and variants
- **RAG** — embeddings, indexing, retrieval with local and production vector stores
- **Sessions & Chat** — multi-turn conversations with state
- **Agents** — multi-agent orchestration
- **Interrupts** — human-in-the-loop patterns
- **Evaluations** — built-in RAGAS metrics and custom evaluators
- **Deployment** — Jetty, Spring Boot, Firebase Cloud Functions
- **Observability** — tracing, metrics, Firebase telemetry

### developing-genkit-java

Covers framework internals and contribution guidelines:

- **Module architecture** — core, ai, genkit, plugins dependency flow
- **Core abstractions** — `Action<I,O,S>`, `ActionContext`, `Registry`, `ActionType`
- **Plugin system** — `Plugin` interface, registration, `compat-oai` base
- **Model implementation** — `Model` interface, streaming, SSE
- **Naming conventions** — packages, classes, methods, action keys
- **Testing patterns** — JUnit 5, Mockito, test structure
- **Code quality** — Google Java Format, Checkstyle
- **Conventional commits** — types, scopes, versioning
- **New module checklist** — step-by-step guide for adding plugins
