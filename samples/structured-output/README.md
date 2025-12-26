# Structured Output Sample

This sample demonstrates how to use Genkit's type-safe structured output feature.

## Features

- **Type-safe generation**: Generate typed objects directly from AI models
- **Annotation support**: Use `@JsonPropertyDescription` and `@JsonProperty(required = true)`
- **Tools with structured I/O**: Define tools with typed inputs and outputs
- **Schema auto-generation**: Schemas are automatically generated from your classes

## Running the Sample

1. Set your OpenAI API key:
   ```bash
   export OPENAI_API_KEY=your-api-key
   ```

2. Run the sample:
   ```bash
   ./run.sh
   ```

   Or using Maven:
   ```bash
   mvn exec:java
   ```

## Examples in the Sample

### 1. Simple Structured Output
Generate a typed object with a simple prompt:

```java
MenuItem item = genkit.generate(
    "openai/gpt-4o-mini",
    "Suggest a fancy French menu item",
    MenuItem.class
);
```

### 2. With Options
Use `GenerateOptions` for more control:

```java
MenuItem item = genkit.generate(
    GenerateOptions.<MenuItem>builder()
        .model("openai/gpt-4o-mini")
        .prompt("Suggest an Italian pasta dish")
        .outputClass(MenuItem.class)
        .config(GenerationConfig.builder().temperature(0.7).build())
        .build()
);
```

### 3. Tools with Structured I/O
Define tools with typed inputs and outputs:

```java
Tool<RecipeRequest, MenuItem> tool = Tool.<RecipeRequest, MenuItem>builder()
    .name("generateRecipe")
    .description("Generates recipes")
    .inputClass(RecipeRequest.class)
    .outputClass(MenuItem.class)
    .handler((ctx, request) -> {
        // Your tool logic
    })
    .build();
```

### 4. Using Annotations
Add metadata to your classes:

```java
public class MenuItem {
    @JsonProperty(required = true)
    @JsonPropertyDescription("The name of the menu item")
    private String name;
    
    @JsonPropertyDescription("A detailed description")
    private String description;
    
    @JsonProperty(required = true)
    @JsonPropertyDescription("The price in USD")
    private double price;
}
```

## Benefits

- **Type Safety**: Compile-time type checking for your AI outputs
- **Better IDE Support**: Auto-completion and refactoring
- **Self-Documenting**: Annotations describe your schema
- **Less Boilerplate**: No manual schema definition needed
