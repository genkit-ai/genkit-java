---
title: Structured Output
description: Generate type-safe outputs with automatic JSON schema generation.
---

Genkit supports generating structured, type-safe outputs using Jackson annotations. This ensures the model's response conforms to your Java types.

## Defining an output class

Use Jackson annotations to define your output schema:

```java
public class MenuItem {
    @JsonProperty(required = true)
    @JsonPropertyDescription("The name of the menu item")
    private String name;

    @JsonProperty(required = true)
    @JsonPropertyDescription("A detailed description")
    private String description;

    @JsonProperty(required = true)
    @JsonPropertyDescription("Price in dollars")
    private double price;

    @JsonPropertyDescription("Preparation time in minutes")
    private int prepTimeMinutes;

    @JsonPropertyDescription("Dietary information (e.g., vegan, gluten-free)")
    private List<String> dietaryInfo;

    // getters/setters...
}
```

## Generating structured output

```java
MenuItem item = genkit.generate(
    GenerateOptions.<MenuItem>builder()
        .model("openai/gpt-4o-mini")
        .prompt("Suggest a fancy French menu item")
        .outputClass(MenuItem.class)
        .build()
);

System.out.println(item.getName());        // "Coq au Vin"
System.out.println(item.getPrice());       // 28.50
System.out.println(item.getDietaryInfo()); // ["gluten-free"]
```

## In flows

```java
genkit.defineFlow(
    "generateMenuItem",
    MenuItemRequest.class,
    MenuItem.class,
    (ctx, request) -> genkit.generate(
        GenerateOptions.<MenuItem>builder()
            .model("openai/gpt-4o-mini")
            .prompt(request.getDescription())
            .outputClass(MenuItem.class)
            .build()
    )
);
```

## With DotPrompt

```java
ExecutablePrompt<DishRequest> prompt = genkit.prompt("italian-dish", DishRequest.class);
MenuItem dish = prompt.generate(new DishRequest("Italian"), MenuItem.class);
```

## With tools

```java
Tool<RecipeRequest, MenuItem> recipeGen = genkit.defineTool(
    "generateRecipe",
    "Generates a recipe",
    (ctx, request) -> new MenuItem(...),
    RecipeRequest.class,
    MenuItem.class
);
```

See the [structured-output sample](https://github.com/genkit-ai/genkit-java/tree/main/samples/structured-output) for complete examples.
