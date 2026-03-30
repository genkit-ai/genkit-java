---
title: MCP (Model Context Protocol)
description: Connect to MCP servers as a client or expose Genkit tools as an MCP server.
---

The MCP plugin supports both sides of the [Model Context Protocol](https://modelcontextprotocol.io/):

- **Client** — Connect your Genkit app to external MCP servers (filesystem, databases, APIs) and use their tools with AI models.
- **Server** — Expose your Genkit tools to external MCP clients like Claude Desktop.

## Installation

```xml
<dependency>
    <groupId>com.google.genkit</groupId>
    <artifactId>genkit-plugin-mcp</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

---

## MCP Client — Connecting to external servers

Use `MCPPlugin` to connect your Genkit app to one or more external MCP servers. The plugin automatically discovers tools from each server and makes them available as Genkit tools.

### STDIO transport (local processes)

Connect to MCP servers that run as local processes communicating via stdin/stdout:

```java
import com.google.genkit.plugins.mcp.MCPPlugin;
import com.google.genkit.plugins.mcp.MCPPluginOptions;
import com.google.genkit.plugins.mcp.MCPServerConfig;

MCPPlugin mcpPlugin = new MCPPlugin(MCPPluginOptions.builder()
    .addServer("filesystem", MCPServerConfig.stdio(
        "npx", "-y", "@modelcontextprotocol/server-filesystem", "/tmp"))
    .build());

Genkit genkit = Genkit.builder()
    .plugin(mcpPlugin)
    .build();
```

### HTTP/SSE transport (remote servers)

Connect to remote MCP servers over HTTP with Server-Sent Events:

```java
MCPPlugin mcpPlugin = new MCPPlugin(MCPPluginOptions.builder()
    .addServer("my-server", MCPServerConfig.http("https://my-mcp-server.com/sse"))
    .build());
```

### Streamable HTTP transport

For servers supporting the modern Streamable HTTP transport:

```java
MCPPlugin mcpPlugin = new MCPPlugin(MCPPluginOptions.builder()
    .addServer("my-server", MCPServerConfig.streamableHttp("https://my-mcp-server.com/mcp"))
    .build());
```

### Using MCP tools with AI models

Once connected, MCP tools are automatically registered as Genkit tools. AI models can use them directly:

```java
ModelResponse response = genkit.generate(GenerateOptions.builder()
    .model("openai/gpt-4o-mini")
    .prompt("List all files in the /tmp directory")
    .tools("filesystem/list_directory", "filesystem/read_file")
    .build());
```

### Convenience factory methods

For quick single-server setups:

```java
// STDIO
Genkit genkit = Genkit.builder()
    .plugin(MCPPlugin.stdio("filesystem",
        "npx", "-y", "@modelcontextprotocol/server-filesystem", "/tmp"))
    .build();

// HTTP
Genkit genkit = Genkit.builder()
    .plugin(MCPPlugin.http("my-server", "https://my-mcp-server.com/sse"))
    .build();
```

### Multi-server setup

Connect to multiple MCP servers simultaneously:

```java
MCPPlugin mcpPlugin = new MCPPlugin(MCPPluginOptions.builder()
    .addServer("filesystem", MCPServerConfig.stdio(
        "npx", "-y", "@modelcontextprotocol/server-filesystem", "/tmp"))
    .addServer("database", MCPServerConfig.http("https://db-mcp-server.com/sse"))
    .addServer("github", MCPServerConfig.stdio(
        "npx", "-y", "@modelcontextprotocol/server-github"))
    .build());
```

Tools from each server are namespaced: `filesystem/read_file`, `database/query`, `github/list_repos`.

### Direct tool invocation

Call MCP tools directly without going through an AI model:

```java
Object result = mcpPlugin.callTool("filesystem", "read_file",
    Map.of("path", "/tmp/example.txt"));
```

### Accessing MCP resources

Read resources exposed by MCP servers:

```java
List<MCPResource> resources = mcpPlugin.getResources("filesystem");

MCPResourceContent content = mcpPlugin.readResource("filesystem",
    "file:///tmp/example.txt");
```

### Client configuration options

| Option | Default | Description |
|--------|---------|-------------|
| `name` | `"genkit-mcp"` | Client name for identification |
| `requestTimeout` | 30 seconds | Timeout per MCP request |
| `rawToolResponses` | `false` | Return raw MCP responses |

### Cleanup

Disconnect from all MCP servers when done:

```java
mcpPlugin.disconnect();
```

---

## MCP Server — Exposing Genkit tools

Use `MCPServer` to expose your Genkit tools to external MCP clients (like Claude Desktop, Cursor, or other MCP-compatible apps).

### Creating an MCP server

```java
import com.google.genkit.Genkit;
import com.google.genkit.plugins.mcp.MCPServer;
import com.google.genkit.plugins.mcp.MCPServerOptions;

Genkit genkit = Genkit.builder().build();

// Define tools
genkit.defineTool("calculator", "Performs arithmetic",
    Map.of("expression", String.class), String.class,
    (ctx, input) -> {
        // evaluate expression...
        return result;
    });

genkit.defineTool("get_weather", "Gets current weather",
    Map.of("city", String.class), String.class,
    (ctx, input) -> {
        return "Sunny, 22°C in " + input.get("city");
    });

// Create and start the MCP server
MCPServer server = new MCPServer(genkit.getRegistry(),
    MCPServerOptions.builder()
        .name("my-genkit-tools")
        .version("1.0.0")
        .build());

// Start with STDIO transport (default)
server.start();
```

The server automatically discovers all tools registered in the Genkit registry and exposes them via the MCP protocol.

### Using with Claude Desktop

Add this to your Claude Desktop configuration (`~/Library/Application Support/Claude/claude_desktop_config.json` on macOS):

```json
{
  "mcpServers": {
    "genkit-tools": {
      "command": "java",
      "args": ["-jar", "/path/to/your-app.jar"]
    }
  }
}
```

### Custom transport

By default, the server uses STDIO transport. You can provide a custom transport provider:

```java
server.start(customTransportProvider);
```

### Server options

| Option | Default | Description |
|--------|---------|-------------|
| `name` | `"genkit-mcp-server"` | Server name exposed to clients |
| `version` | `"1.0.0"` | Server version |

---

## Popular MCP servers

| Server | Install | Description |
|--------|---------|-------------|
| Filesystem | `npx @modelcontextprotocol/server-filesystem` | Read/write local files |
| GitHub | `npx @modelcontextprotocol/server-github` | GitHub API access |
| Postgres | `npx @modelcontextprotocol/server-postgres` | PostgreSQL queries |
| Brave Search | `npx @modelcontextprotocol/server-brave-search` | Web search |
| Everything | `npx @modelcontextprotocol/server-everything` | Demo/test server |

Browse more at [mcp.so/servers](https://mcp.so/servers).

## Sample

See the [mcp sample](https://github.com/genkit-ai/genkit-java/tree/main/samples/mcp) for client and server examples.
