import { defineConfig } from "astro/config";
import starlight from "@astrojs/starlight";

export default defineConfig({
  site: "https://genkit-ai.github.io/genkit-java",
  base: "/genkit-java",
  integrations: [
    starlight({
      title: "Java (Unofficial)",
      favicon: 'favicon.ico',
      head: [
        {
          tag: "meta",
          attrs: {
            name: "google-site-verification",
            content: "LopxNf0q-1RkccL6rKqWvpaLi8Qcr6HqkWDqyCl8fUA",
          },
        },
      ],
      description:
        "The Java implementation of the Genkit framework for building AI-powered applications.",
      social: [
        {
          icon: "github",
          label: "GitHub",
          href: "https://github.com/genkit-ai/genkit-java",
        },
        {
          icon: "discord",
          label: "Discord",
          href: "https://discord.gg/qXt5zzQKpc",
        },
        {
          icon: "x.com",
          label: "X",
          href: "https://x.com/GenkitFramework",
        },
        {
          icon: "linkedin",
          label: "LinkedIn",
          href: "https://www.linkedin.com/company/genkit",
        },
      ],
      logo: {
        alt: "Genkit Java",
        dark: "./src/assets/logo-dark.svg",
        light: "./src/assets/logo-light.svg",
      },
      customCss: ["./src/styles/custom.css"],
      editLink: {
        baseUrl:
          "https://github.com/genkit-ai/genkit-java/edit/main/docs/",
      },
      sidebar: [
        {
          label: "Getting Started",
          items: [
            { label: "Overview", slug: "overview" },
            { label: "Get Started", slug: "getting-started" },
          ],
        },
        {
          label: "Building AI Features",
          items: [
            { label: "Generating Content", slug: "models" },
            { label: "Creating Flows", slug: "flows" },
            { label: "Tool Calling", slug: "tools" },
            { label: "DotPrompt", slug: "dotprompt" },
            { label: "Structured Output", slug: "structured-output" },
            { label: "Streaming", slug: "streaming" },
            { label: "RAG", slug: "rag" },
            { label: "Chat Sessions", slug: "chat-sessions" },
            { label: "Evaluations", slug: "evaluations" },
            { label: "Middleware", slug: "middleware" },
            { label: "Interrupts", slug: "interrupts" },
            { label: "Multi-Agent", slug: "multi-agent" },
          ],
        },
        {
          label: "Observability",
          items: [
            { label: "Overview", slug: "observability" },
            { label: "OpenTelemetry Plugin", slug: "plugins/opentelemetry" },
          ],
        },
        {
          label: "Model Plugins",
          items: [
            { label: "Overview", slug: "plugins/overview" },
            { label: "Google GenAI (Gemini)", slug: "plugins/google-genai" },
            { label: "OpenAI", slug: "plugins/openai" },
            { label: "Anthropic (Claude)", slug: "plugins/anthropic" },
            { label: "AWS Bedrock", slug: "plugins/aws-bedrock" },
            { label: "Azure AI Foundry", slug: "plugins/azure-foundry" },
            { label: "xAI (Grok)", slug: "plugins/xai" },
            { label: "DeepSeek", slug: "plugins/deepseek" },
            { label: "Cohere", slug: "plugins/cohere" },
            { label: "Mistral", slug: "plugins/mistral" },
            { label: "Groq", slug: "plugins/groq" },
            { label: "Ollama", slug: "plugins/ollama" },
            { label: "OpenAI-Compatible", slug: "plugins/compat-oai" },
          ],
        },
        {
          label: "Server & Deployment",
          items: [
            { label: "Jetty Server", slug: "plugins/jetty" },
            { label: "Spring Boot", slug: "plugins/spring" },
            { label: "Firebase Functions", slug: "plugins/firebase-functions" },
          ],
        },
        {
          label: "Vector Databases",
          items: [
            { label: "Firebase (Firestore)", slug: "plugins/firebase-vector-store" },
            { label: "Local Vector Store", slug: "plugins/localvec" },
            { label: "Weaviate", slug: "plugins/weaviate" },
            { label: "PostgreSQL (pgvector)", slug: "plugins/postgresql" },
            { label: "Pinecone", slug: "plugins/pinecone" },
          ],
        },
        {
          label: "Other Plugins",
          items: [
            { label: "Firebase", slug: "plugins/firebase" },
            { label: "MCP", slug: "plugins/mcp" },
            { label: "Evaluators", slug: "plugins/evaluators" },
          ],
        },
        {
          label: "Development",
          items: [
            { label: "Dev UI & CLI", slug: "dev-ui" },
            { label: "Architecture", slug: "architecture" },
            { label: "Samples", slug: "samples" },
            { label: "Claude Code Skills", slug: "claude-code-skills" },
          ],
        },
        {
          label: "API Reference",
          items: [
            {
              label: "Javadoc",
              link: "/javadoc/",
              attrs: { target: "_blank" },
            },
          ],
        },
      ],
    }),
  ],
});
