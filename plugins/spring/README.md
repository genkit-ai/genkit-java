# Genkit Spring Boot Plugin

This plugin provides Spring Boot HTTP server integration for Genkit, allowing you to expose your Genkit flows as REST endpoints.

## Features

- Automatic REST endpoint generation for all registered flows
- Health check endpoint at `/health`
- Flow listing endpoint at `/api/flows` (configurable)
- Individual flow execution endpoints at `/api/flows/{flowName}`
- Configurable port, host, base path, and context path
- Full Spring Boot ecosystem integration

## Installation

Add the following dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>com.google.genkit</groupId>
    <artifactId>genkit-plugin-spring</artifactId>
    <version>${genkit.version}</version>
</dependency>
```

## Usage

### Basic Usage

```java
import com.google.genkit.Genkit;
import com.google.genkit.plugins.spring.SpringPlugin;
import com.google.genkit.plugins.spring.SpringPluginOptions;

public class MyApplication {
    public static void main(String[] args) {
        // Create the Spring plugin
        SpringPlugin spring = new SpringPlugin(
            SpringPluginOptions.builder()
                .port(8080)
                .build()
        );

        // Create Genkit instance with the plugin
        Genkit genkit = Genkit.builder()
            .plugin(spring)
            .build();

        // Define your flows
        genkit.defineFlow("greet", String.class, String.class, (ctx, name) -> {
            return "Hello, " + name + "!";
        });

        // Start the server (blocks until stopped)
        spring.start();
    }
}
```

### Configuration Options

```java
SpringPluginOptions options = SpringPluginOptions.builder()
    .port(8080)              // HTTP port (default: 8080)
    .host("0.0.0.0")         // Bind address (default: 0.0.0.0)
    .basePath("/api/flows")  // Base path for flow endpoints (default: /api/flows)
    .contextPath("/myapp")   // Application context path (default: "")
    .build();
```

### Quick Setup

For simple use cases, you can use the static factory method:

```java
SpringPlugin spring = SpringPlugin.create(8080);
```

## Endpoints

Once the server is running, the following endpoints are available:

### Health Check

```
GET /health
```

Returns:
```json
{
    "status": "ok"
}
```

### List Flows

```
GET /api/flows
```

Returns:
```json
{
    "flows": ["greet", "summarize", "translate"]
}
```

### Execute Flow

```
POST /api/flows/{flowName}
Content-Type: application/json

{
    "your": "input data"
}
```

Returns the flow execution result as JSON.

## Error Handling

When a flow execution fails, the plugin returns a structured error response:

```json
{
    "code": 2,
    "message": "Error description",
    "details": {
        "stack": "..."
    }
}
```

## Comparison with Jetty Plugin

| Feature | Spring Plugin | Jetty Plugin |
|---------|--------------|--------------|
| Framework | Spring Boot | Eclipse Jetty |
| Dependencies | Larger (Spring ecosystem) | Lightweight |
| Configuration | Spring properties + builder | Builder pattern |
| Ecosystem | Full Spring Boot integration | Standalone |
| Best for | Enterprise applications | Simple deployments |

## Example: Full Application

```java
import com.google.genkit.Genkit;
import com.google.genkit.plugins.spring.SpringPlugin;
import com.google.genkit.plugins.spring.SpringPluginOptions;
import com.google.genkit.plugins.googlegenai.GoogleGenAIPlugin;

public class ChatApplication {
    public static void main(String[] args) throws Exception {
        SpringPlugin spring = new SpringPlugin(
            SpringPluginOptions.builder()
                .port(8080)
                .basePath("/api/chat")
                .build()
        );

        Genkit genkit = Genkit.builder()
            .plugin(spring)
            .plugin(new GoogleGenAIPlugin())
            .build();

        // Define a chat flow
        genkit.defineFlow("chat", String.class, String.class, (ctx, message) -> {
            return genkit.generate(
                GenerateRequest.builder()
                    .model("googleai/gemini-pro")
                    .prompt(message)
                    .build()
            ).text();
        });

        System.out.println("Starting server on http://localhost:8080");
        spring.start();
    }
}
```

## License

Apache License 2.0
