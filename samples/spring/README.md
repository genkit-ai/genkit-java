# Genkit Spring Boot Sample

This sample demonstrates how to use Genkit with Spring Boot to create a web server that exposes AI flows as REST endpoints.

## Prerequisites

- Java 21 or later
- Maven 3.x
- (Optional) Google GenAI API key for AI-powered flows

## Running the Sample

1. From the project root directory, build all modules:
   ```bash
   mvn clean install
   ```

2. Run the sample:
   ```bash
   cd samples/spring
   ./run.sh
   ```

   Or manually:
   ```bash
   mvn compile exec:java
   ```

3. The server will start on `http://localhost:8080`

## Available Endpoints

### Health Check
```bash
curl http://localhost:8080/health
```

Response:
```json
{"status":"ok"}
```

### List Flows
```bash
curl http://localhost:8080/api/flows
```

Response:
```json
{"flows":["greet","reverse","info"]}
```

### Execute Greet Flow
```bash
curl -X POST http://localhost:8080/api/flows/greet \
  -H "Content-Type: application/json" \
  -d '"World"'
```

Response:
```json
"Hello, World!"
```

### Execute Reverse Flow
```bash
curl -X POST http://localhost:8080/api/flows/reverse \
  -H "Content-Type: application/json" \
  -d '"Hello Spring"'
```

Response:
```json
"gnirpS olleH"
```

### Execute Info Flow
```bash
curl -X POST http://localhost:8080/api/flows/info \
  -H "Content-Type: application/json" \
  -d '"genkit"'
```

Response:
```json
{"topic":"genkit","timestamp":1703420000000,"version":"1.0.0"}
```

## Configuration

The sample uses the following configuration:

```java
SpringPluginOptions.builder()
    .port(8080)           // HTTP port
    .host("0.0.0.0")      // Bind to all interfaces
    .basePath("/api/flows") // Base path for flow endpoints
    .build()
```

## Comparison with Jetty Sample

This sample is functionally equivalent to the Jetty sample but uses Spring Boot instead. The main differences are:

| Aspect | Spring Plugin | Jetty Plugin |
|--------|--------------|--------------|
| Framework | Spring Boot 3.x | Eclipse Jetty 12 |
| Startup | Full Spring context | Lightweight server |
| Configuration | Spring properties | Builder pattern |
| Ecosystem | Spring Boot starters | Standalone |

Choose Spring Boot when you need:
- Integration with other Spring components
- Spring Security for authentication
- Spring Data for database access
- Production-ready features (actuator, metrics)

Choose Jetty when you need:
- Minimal dependencies
- Faster startup time
- Simple deployment

## License

Apache License 2.0
