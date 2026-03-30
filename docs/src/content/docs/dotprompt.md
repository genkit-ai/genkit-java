---
title: DotPrompt
description: Manage prompts as template files with Handlebars support.
---

DotPrompt lets you load and use `.prompt` files with Handlebars templating for structured, reusable prompt management.

## Loading a prompt

Place `.prompt` files in `resources/prompts/`:

```
resources/
  prompts/
    recipe.prompt
    recipe.robot.prompt   # variant
```

```java
// Load a prompt from resources/prompts/recipe.prompt
ExecutablePrompt<RecipeInput> recipePrompt = genkit.prompt("recipe", RecipeInput.class);

// Execute with typed input
ModelResponse response = recipePrompt.generate(new RecipeInput("pasta carbonara"));
```

## Prompt variants

Variants let you create alternate versions of a prompt (e.g., different tones or personalities):

```java
// Load the "robot" variant: recipe.robot.prompt
ExecutablePrompt<RecipeInput> robotPrompt = genkit.prompt(
    "recipe", RecipeInput.class, "robot"
);
```

## Structured output from prompts

Prompts work with structured output generation:

```java
ExecutablePrompt<DishRequest> prompt = genkit.prompt("italian-dish", DishRequest.class);
MenuItem dish = prompt.generate(new DishRequest("Italian"), MenuItem.class);
```

## Prompt file format

Prompt files use Handlebars templates with YAML frontmatter:

```handlebars
---
model: openai/gpt-4o-mini
config:
  temperature: 0.9
  maxOutputTokens: 500
input:
  schema:
    ingredient: string
    style?: string
---
Create a {{style}} recipe using {{ingredient}} as the main ingredient.
Include step-by-step instructions.
```
