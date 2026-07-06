/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.google.genkit;

import com.google.genkit.ai.*;
import com.google.genkit.ai.evaluation.*;
import com.google.genkit.ai.middleware.*;
import com.google.genkit.ai.telemetry.ModelTelemetryHelper;
import com.google.genkit.core.*;
import com.google.genkit.core.middleware.Middleware;
import com.google.genkit.core.tracing.SpanMetadata;
import com.google.genkit.core.tracing.Tracer;
import com.google.genkit.prompt.DotPrompt;
import com.google.genkit.prompt.ExecutablePrompt;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Genkit is the main entry point for the Genkit framework.
 *
 * <p>It provides methods to define and run flows, configure AI models, and interact with the Genkit
 * ecosystem.
 */
public class Genkit {

  private static final Logger logger = LoggerFactory.getLogger(Genkit.class);

  private final Registry registry;
  private final List<Plugin> plugins;
  private final GenkitOptions options;
  private final Map<String, DotPrompt<?>> promptCache;
  private ReflectionServer reflectionServer;
  private ReflectionServerV2 reflectionServerV2;
  private EvaluationManager evaluationManager;

  /** Creates a new Genkit instance with default options. */
  public Genkit() {
    this(GenkitOptions.builder().build());
  }

  /**
   * Creates a new Genkit instance with the given options.
   *
   * @param options the Genkit options
   */
  public Genkit(GenkitOptions options) {
    this.options = options;
    this.registry = new DefaultRegistry();
    this.plugins = new ArrayList<>();
    this.promptCache = new ConcurrentHashMap<>();
  }

  /**
   * Creates a new Genkit builder.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Creates a Genkit instance with the given plugins.
   *
   * @param plugins the plugins to use
   * @return a configured Genkit instance
   */
  public static Genkit create(Plugin... plugins) {
    Builder builder = builder();
    for (Plugin plugin : plugins) {
      builder.plugin(plugin);
    }
    return builder.build();
  }

  /** Initializes plugins. */
  public void init() {
    // Register utility actions
    registerUtilityActions();

    for (Plugin plugin : plugins) {
      try {
        List<Action<?, ?, ?>> actions = plugin.init(registry);
        for (Action<?, ?, ?> action : actions) {
          String key = action.getType().keyFromName(action.getName());
          registry.registerAction(key, action);
        }
        logger.info("Initialized plugin: {}", plugin.getName());
      } catch (Exception e) {
        logger.error("Failed to initialize plugin: {}", plugin.getName(), e);
        throw new GenkitException("Failed to initialize plugin: " + plugin.getName(), e);
      }
    }

    // Start reflection server in dev mode
    if (options.isDevMode()) {
      startReflectionServer();
    }
  }

  /** Registers utility actions like /util/generate. */
  private void registerUtilityActions() {
    GenerateAction.define(registry);
  }

  /**
   * Defines a flow.
   *
   * @param <I> the input type
   * @param <O> the output type
   * @param name the flow name
   * @param inputClass the input class
   * @param outputClass the output class
   * @param handler the flow handler
   * @return the flow
   */
  public <I, O> Flow<I, O, Void> defineFlow(
      String name,
      Class<I> inputClass,
      Class<O> outputClass,
      BiFunction<ActionContext, I, O> handler) {
    return Flow.define(registry, name, inputClass, outputClass, handler);
  }

  /**
   * Defines a flow with middleware.
   *
   * @param <I> the input type
   * @param <O> the output type
   * @param name the flow name
   * @param inputClass the input class
   * @param outputClass the output class
   * @param handler the flow handler
   * @param middleware the middleware to apply
   * @return the flow
   */
  public <I, O> Flow<I, O, Void> defineFlow(
      String name,
      Class<I> inputClass,
      Class<O> outputClass,
      BiFunction<ActionContext, I, O> handler,
      List<Middleware<I, O>> middleware) {
    return Flow.define(registry, name, inputClass, outputClass, handler, middleware);
  }

  /**
   * Defines a flow with a simple handler.
   *
   * @param <I> the input type
   * @param <O> the output type
   * @param name the flow name
   * @param inputClass the input class
   * @param outputClass the output class
   * @param handler the flow handler
   * @return the flow
   */
  public <I, O> Flow<I, O, Void> defineFlow(
      String name, Class<I> inputClass, Class<O> outputClass, Function<I, O> handler) {
    return Flow.define(
        registry, name, inputClass, outputClass, (ctx, input) -> handler.apply(input));
  }

  /**
   * Defines a flow with a simple handler and middleware.
   *
   * @param <I> the input type
   * @param <O> the output type
   * @param name the flow name
   * @param inputClass the input class
   * @param outputClass the output class
   * @param handler the flow handler
   * @param middleware the middleware to apply
   * @return the flow
   */
  public <I, O> Flow<I, O, Void> defineFlow(
      String name,
      Class<I> inputClass,
      Class<O> outputClass,
      Function<I, O> handler,
      List<Middleware<I, O>> middleware) {
    return Flow.define(
        registry, name, inputClass, outputClass, (ctx, input) -> handler.apply(input), middleware);
  }

  /**
   * Defines a tool.
   *
   * @param <I> the input type
   * @param <O> the output type
   * @param name the tool name
   * @param description the tool description
   * @param inputSchema the input JSON schema
   * @param inputClass the input class
   * @param handler the tool handler
   * @return the tool
   */
  public <I, O> Tool<I, O> defineTool(
      String name,
      String description,
      Map<String, Object> inputSchema,
      Class<I> inputClass,
      BiFunction<ActionContext, I, O> handler) {
    Tool<I, O> tool =
        Tool.<I, O>builder()
            .name(name)
            .description(description)
            .inputSchema(inputSchema)
            .inputClass(inputClass)
            .handler(handler)
            .build();
    tool.register(registry);
    return tool;
  }

  /**
   * Defines a tool with typed input and output classes.
   *
   * <p>This is the preferred way to create tools with structured input/output. The schemas are
   * automatically generated from the classes using their {@code @JsonPropertyDescription} and
   * {@code @JsonProperty} annotations.
   *
   * <p>Example usage:
   *
   * <pre>{@code
   * Tool<RecipeRequest, MenuItem> tool = genkit.defineTool(
   *     "generateRecipe",
   *     "Generates a recipe based on cuisine and dietary preferences",
   *     (ctx, request) -> {
   *       // Tool implementation
   *       return new MenuItem(...);
   *     },
   *     RecipeRequest.class,
   *     MenuItem.class
   * );
   * }</pre>
   *
   * @param <I> the input type
   * @param <O> the output type
   * @param name the tool name
   * @param description the tool description
   * @param handler the tool handler
   * @param inputClass the input class (schema auto-generated)
   * @param outputClass the output class (schema auto-generated)
   * @return the tool
   */
  public <I, O> Tool<I, O> defineTool(
      String name,
      String description,
      BiFunction<ActionContext, I, O> handler,
      Class<I> inputClass,
      Class<O> outputClass) {
    Tool<I, O> tool =
        Tool.<I, O>builder()
            .name(name)
            .description(description)
            .inputClass(inputClass)
            .outputClass(outputClass)
            .handler(handler)
            .build();
    tool.register(registry);
    return tool;
  }

  /**
   * Loads a prompt by name from the prompts directory.
   *
   * <p>This is similar to the JavaScript API: `ai.prompt('hello')`. The prompt is loaded from the
   * configured promptDir (default: /prompts). The prompt is automatically registered as an action
   * and cached for reuse.
   *
   * <p>Example usage:
   *
   * <pre>{@code
   * ExecutablePrompt<HelloInput> helloPrompt = genkit.prompt("hello", HelloInput.class);
   * ModelResponse response = helloPrompt.generate(new HelloInput("John"));
   * }</pre>
   *
   * @param <I> the input type
   * @param name the prompt name (without .prompt extension)
   * @param inputClass the input class
   * @return the executable prompt
   * @throws GenkitException if the prompt cannot be loaded
   */
  @SuppressWarnings("unchecked")
  public <I> ExecutablePrompt<I> prompt(String name, Class<I> inputClass) throws GenkitException {
    return prompt(name, inputClass, null);
  }

  /**
   * Loads a prompt by name with an optional variant.
   *
   * <p>Variants allow different versions of the same prompt to be tested. For example: "recipe"
   * with variant "gemini25pro" loads "recipe.gemini25pro.prompt".
   *
   * @param <I> the input type
   * @param name the prompt name (without .prompt extension)
   * @param inputClass the input class
   * @param variant optional variant name (e.g., "gemini25pro")
   * @return the executable prompt
   * @throws GenkitException if the prompt cannot be loaded
   */
  @SuppressWarnings("unchecked")
  public <I> ExecutablePrompt<I> prompt(String name, Class<I> inputClass, String variant)
      throws GenkitException {
    // Build the cache key
    String cacheKey = variant != null ? name + "." + variant : name;

    // Check cache first
    DotPrompt<I> dotPrompt = (DotPrompt<I>) promptCache.get(cacheKey);

    if (dotPrompt == null) {
      // Build the resource path
      String promptDir = options.getPromptDir();
      String fileName = variant != null ? name + "." + variant + ".prompt" : name + ".prompt";
      String resourcePath = promptDir + "/" + fileName;

      // Load the prompt
      dotPrompt = DotPrompt.loadFromResource(resourcePath);
      promptCache.put(cacheKey, dotPrompt);

      // Auto-register as action
      dotPrompt.register(registry, inputClass);
      String registeredKey = ActionType.EXECUTABLE_PROMPT.keyFromName(dotPrompt.getName());
      logger.info(
          "Loaded and registered prompt: {} as {} (variant: {})", name, registeredKey, variant);
    }

    return new ExecutablePrompt<>(dotPrompt, registry, inputClass)
        .withGenerateFunction(opts -> this.generate(opts))
        .withGenerateObjectFunction((opts, clazz) -> this.generateObject(opts));
  }

  /**
   * Loads a prompt by name using a Map as input type.
   *
   * <p>This is a convenience method when you don't want to define a specific input class.
   *
   * @param name the prompt name (without .prompt extension)
   * @return the executable prompt with Map input
   * @throws GenkitException if the prompt cannot be loaded
   */
  @SuppressWarnings("unchecked")
  public ExecutablePrompt<Map<String, Object>> prompt(String name) throws GenkitException {
    return prompt(name, (Class<Map<String, Object>>) (Class<?>) Map.class, null);
  }

  /**
   * Defines a prompt.
   *
   * @param <I> the input type
   * @param name the prompt name
   * @param template the prompt template
   * @param inputClass the input class
   * @param renderer the prompt renderer
   * @return the prompt
   */
  public <I> Prompt<I> definePrompt(
      String name,
      String template,
      Class<I> inputClass,
      BiFunction<ActionContext, I, ModelRequest> renderer) {
    Prompt<I> prompt =
        Prompt.<I>builder()
            .name(name)
            .template(template)
            .inputClass(inputClass)
            .renderer(renderer)
            .build();
    prompt.register(registry);
    return prompt;
  }

  /**
   * Registers a model.
   *
   * @param model the model to register
   */
  public void registerModel(Model model) {
    model.register(registry);
  }

  /**
   * Registers an embedder.
   *
   * @param embedder the embedder to register
   */
  public void registerEmbedder(Embedder embedder) {
    embedder.register(registry);
  }

  /**
   * Registers a retriever.
   *
   * @param retriever the retriever to register
   */
  public void registerRetriever(Retriever retriever) {
    retriever.register(registry);
  }

  /**
   * Registers an indexer.
   *
   * @param indexer the indexer to register
   */
  public void registerIndexer(Indexer indexer) {
    indexer.register(registry);
  }

  /**
   * Defines and registers a retriever.
   *
   * <p>This is the preferred way to create retrievers as it automatically registers them with the
   * Genkit registry.
   *
   * <p>Example usage:
   *
   * <pre>{@code
   * Retriever myRetriever = genkit.defineRetriever("myStore/docs", (ctx, request) -> {
   *   // Find similar documents
   *   List<Document> docs = findSimilarDocs(request.getQuery());
   *   return new RetrieverResponse(docs);
   * });
   * }</pre>
   *
   * @param name the retriever name
   * @param handler the retrieval function
   * @return the registered retriever
   */
  public Retriever defineRetriever(
      String name, BiFunction<ActionContext, RetrieverRequest, RetrieverResponse> handler) {
    Retriever retriever = Retriever.builder().name(name).handler(handler).build();
    retriever.register(registry);
    return retriever;
  }

  /**
   * Defines and registers an indexer.
   *
   * <p>This is the preferred way to create indexers as it automatically registers them with the
   * Genkit registry.
   *
   * <p>Example usage:
   *
   * <pre>{@code
   * Indexer myIndexer = genkit.defineIndexer("myStore/docs", (ctx, request) -> {
   *   // Index the documents
   *   indexDocuments(request.getDocuments());
   *   return new IndexerResponse();
   * });
   * }</pre>
   *
   * @param name the indexer name
   * @param handler the indexing function
   * @return the registered indexer
   */
  public Indexer defineIndexer(
      String name, BiFunction<ActionContext, IndexerRequest, IndexerResponse> handler) {
    Indexer indexer = Indexer.builder().name(name).handler(handler).build();
    indexer.register(registry);
    return indexer;
  }

  /**
   * Gets a model by name.
   *
   * @param name the model name
   * @return the model
   */
  public Model getModel(String name) {
    Action<?, ?, ?> action = registry.lookupAction(ActionType.MODEL, name);
    if (action == null) {
      throw new GenkitException("Model not found: " + name);
    }
    return (Model) action;
  }

  /**
   * Gets an embedder by name.
   *
   * @param name the embedder name
   * @return the embedder
   */
  public Embedder getEmbedder(String name) {
    Action<?, ?, ?> action = registry.lookupAction(ActionType.EMBEDDER, name);
    if (action == null) {
      throw new GenkitException("Embedder not found: " + name);
    }
    return (Embedder) action;
  }

  /**
   * Gets a retriever by name.
   *
   * @param name the retriever name
   * @return the retriever
   */
  public Retriever getRetriever(String name) {
    Action<?, ?, ?> action = registry.lookupAction(ActionType.RETRIEVER, name);
    if (action == null) {
      throw new GenkitException("Retriever not found: " + name);
    }
    return (Retriever) action;
  }

  /**
   * Generates a model response using the specified options.
   *
   * <p>This method handles tool execution automatically. If the model requests tool calls, this
   * method will execute the tools, add the results to the conversation, and continue generation
   * until the model produces a final response.
   *
   * <p>When a tool throws a {@link ToolInterruptException}, the generation is halted and the
   * response is returned with {@link FinishReason#INTERRUPTED}. The caller can then use {@link
   * ResumeOptions} to continue generation after handling the interrupt.
   *
   * <p>Example with interrupts:
   *
   * <pre>{@code
   * // First generation - may be interrupted
   * ModelResponse response = genkit.generate(
   *     GenerateOptions.builder()
   *         .model("googleai/gemini-pro")
   *         .prompt("Transfer $100 to account 12345")
   *         .tools(List.of(confirmTransfer))
   *         .build());
   *
   * // Check if interrupted
   * if (response.isInterrupted()) {
   *   Part interrupt = response.getInterrupts().get(0);
   *
   *   // Get user confirmation
   *   boolean confirmed = askUserForConfirmation();
   *
   *   // Resume with user response
   *   Part responseData = confirmTransfer.respond(interrupt, new ConfirmOutput(confirmed));
   *   ModelResponse resumed = genkit.generate(
   *       GenerateOptions.builder()
   *           .model("googleai/gemini-pro")
   *           .messages(response.getMessages())
   *           .tools(List.of(confirmTransfer))
   *           .resume(ResumeOptions.builder().respond(responseData).build())
   *           .build());
   * }
   * }</pre>
   *
   * @param options the generate options
   * @return the model response
   * @throws GenkitException if generation fails
   */
  @SuppressWarnings("unchecked")
  public <T> T generate(GenerateOptions options) throws GenkitException {
    // If outputClass is set, return typed object
    if (options.getOutputClass() != null) {
      ModelResponse response = generateInternal(options);
      Class<T> outputClass = (Class<T>) options.getOutputClass();
      try {
        String rawOutput = response.getText();
        String jsonOutput = extractJson(rawOutput);
        T result = JsonUtils.fromJson(jsonOutput, outputClass);
        return result;
      } catch (Exception e) {
        throw new GenkitException(
            "Failed to deserialize model output to "
                + outputClass.getSimpleName()
                + ": "
                + e.getMessage()
                + "\nRaw output: "
                + response.getText(),
            e);
      }
    }
    // Otherwise return ModelResponse
    return (T) generateInternal(options);
  }

  /**
   * Extracts JSON from model response text that may contain markdown or extra text.
   *
   * @param text the response text
   * @return the extracted JSON string
   */
  private String extractJson(String text) {
    if (text == null) {
      return "{}";
    }

    // Try to find JSON object
    int start = text.indexOf('{');
    int end = text.lastIndexOf('}');
    if (start >= 0 && end > start) {
      String json = text.substring(start, end + 1);

      // Unwrap single-key root objects (e.g., {"menu_item": {...}} -> {...})
      // This handles cases where the model wraps the response in a descriptive key
      try {
        com.fasterxml.jackson.databind.ObjectMapper mapper =
            new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode rootNode = mapper.readTree(json);
        if (rootNode.isObject() && rootNode.size() == 1) {
          com.fasterxml.jackson.databind.JsonNode unwrapped = rootNode.elements().next();
          if (unwrapped.isObject() || unwrapped.isArray()) {
            return mapper.writeValueAsString(unwrapped);
          }
        }
      } catch (Exception e) {
        // If unwrapping fails, return the original JSON
      }

      return json;
    }

    // Try to find JSON array
    start = text.indexOf('[');
    end = text.lastIndexOf(']');
    if (start >= 0 && end > start) {
      return text.substring(start, end + 1);
    }

    // Return as-is if no JSON structure found
    return text;
  }

  /**
   * Internal method that performs the actual generation (non-streaming).
   *
   * @param options the generate options
   * @return the model response
   * @throws GenkitException if generation fails
   */
  private ModelResponse generateInternal(GenerateOptions<?> options) throws GenkitException {
    return generateInternal(options, null);
  }

  /**
   * Internal method that performs the actual generation, optionally with streaming.
   *
   * <p>When {@code streamCallback} is non-null, the model is called with streaming enabled and each
   * chunk is forwarded through the callback. The streaming callback is propagated through the
   * entire middleware chain ({@code wrapGenerate} receives it via {@link
   * com.google.genkit.ai.middleware.GenerateParams#getOnChunk()}, and {@code wrapModel} receives it
   * via {@link com.google.genkit.ai.middleware.ModelParams#getStreamCallback()}).
   *
   * @param options the generate options
   * @param streamCallback callback invoked for each streaming chunk, or null for non-streaming
   * @return the model response
   * @throws GenkitException if generation fails
   */
  private ModelResponse generateInternal(
      GenerateOptions<?> options, java.util.function.Consumer<ModelResponseChunk> streamCallback)
      throws GenkitException {
    // Thread the caller-supplied user context (e.g. {"auth": {...}}) into the ActionContext used
    // to execute tools during this generate. Tools therefore observe ctx.getContext() ==
    // options.getContext(). This is additional to (and independent of) the model-grounding use of
    // options.getContext() at generateObject.
    ActionContext ctx =
        ActionContext.builder().registry(registry).context(options.getContext()).build();

    int maxTurns = options.getMaxTurns() != null ? options.getMaxTurns() : 5;

    // Auto-register any middleware passed via .use(...) so it shows up in the Dev UI
    // Middleware panel. Registration is idempotent (last write wins for a given name).
    registerMiddlewareForDevUi(options.getUse());

    // Create fresh middleware instances for this invocation
    List<GenerationMiddleware> middlewares = createMiddlewareInstances(options.getUse());

    // Collect tools from middleware instances and merge with options tools
    List<Tool<?, ?>> allTools = new ArrayList<>();
    if (options.getTools() != null) {
      allTools.addAll(options.getTools());
    }
    for (GenerationMiddleware mw : middlewares) {
      List<Tool<?, ?>> mwTools = mw.tools();
      if (mwTools != null && !mwTools.isEmpty()) {
        allTools.addAll(mwTools);
      }
    }

    // Build high-level GenerateActionOptions (unresolved: model name as string,
    // tool names as strings). This is what wrapGenerate middleware receives,
    // allowing it to modify model, tools, etc. before resolution.
    GenerateActionOptions actionOpts = toGenerateActionOptions(options, allTools);

    // Handle resume option if provided (manipulates messages at the high level)
    if (options.getResume() != null) {
      actionOpts = handleResumeOption(actionOpts, options);
    }

    // Extract pending restart requests (handled inside the generate loop for proper middleware
    // lifecycle: wrapTool hooks fire for restarted tools, then wrapGenerate fires for next turn)
    final List<ToolRequest> pendingRestarts = new java.util.ArrayList<>();
    if (options.getResume() != null && options.getResume().getRestart() != null) {
      pendingRestarts.addAll(options.getResume().getRestart());
    }

    // Use an array to hold the reference for recursive WrapGenerate wrapping
    final GenerateNext[] generateRef = new GenerateNext[1];

    // Streaming role/message-index tracking (mirrors Go's wrappedCb in generate.go:337-357 and JS
    // makeChunk in action.ts:303-334). Model chunks default to role=model; the message index bumps
    // whenever the streamed role transitions (e.g. model → tool). These are shared across the whole
    // tool loop so indices stay monotonic across turns.
    final Role[] streamCurrentRole = {Role.MODEL};
    final int[] streamCurrentIndex = {0};

    // Core generate iteration: resolve options → model call → tool handling → recurse
    GenerateNext rawGenerate =
        (actx, params) -> {
          GenerateActionOptions opts = params.getRequest();
          int turn = params.getIteration();

          if (turn >= maxTurns) {
            throw new GenkitException("Max tool execution turns (" + maxTurns + ") exceeded");
          }

          // Resolve model from the (possibly middleware-modified) options
          Model model = getModel(opts.getModel());

          // Resolve GenerateActionOptions → ModelRequest (tool names → definitions, config → map)
          ModelRequest req = resolveToModelRequest(opts, allTools);

          // Handle pending restart tools through middleware before calling model.
          // This ensures wrapTool hooks fire for restarted tools, and subsequent
          // recursion through generateRef fires wrapGenerate for the next turn.
          if (!pendingRestarts.isEmpty()) {
            List<ToolRequest> restarts = new java.util.ArrayList<>(pendingRestarts);
            pendingRestarts.clear();

            // Convert restart requests to tool request parts for middleware execution, PRESERVING
            // the request's Part-level metadata (resumed / replacedInput) so the restarted tool
            // can observe its resumed status via ActionContext.isResumed()/getResumed() (mirrors
            // Go handleResumedToolRequest → ToolContext.Resumed). The restart directive's metadata
            // travels on the ToolRequest itself (set by GenkitBeta.toResumeOptions).
            List<Part> restartParts = new java.util.ArrayList<>();
            for (ToolRequest restart : restarts) {
              Part restartPart = Part.toolRequest(restart);
              if (restart.getMetadata() != null && !restart.getMetadata().isEmpty()) {
                restartPart.setMetadata(new java.util.HashMap<>(restart.getMetadata()));
              }
              restartParts.add(restartPart);
            }

            // Execute through WrapTool chain (fires wrapTool hooks)
            ToolExecutionResult toolResult =
                executeToolsWithMiddleware(actx, restartParts, allTools, middlewares);

            // If a restart tool interrupts again, fail
            if (!toolResult.getInterrupts().isEmpty()) {
              throw new GenkitException(
                  "Tool triggered an interrupt during restart. "
                      + "Re-interrupting during restart is not supported.");
            }

            // Add restart tool responses to messages (use high-level opts for recursion)
            List<Message> updatedMessages = new java.util.ArrayList<>(opts.getMessages());

            // If last message is a TOOL message (from respond directives), merge restart responses
            if (!updatedMessages.isEmpty()
                && updatedMessages.get(updatedMessages.size() - 1).getRole() == Role.TOOL) {
              Message existingToolMsg = updatedMessages.get(updatedMessages.size() - 1);
              List<Part> mergedContent = new java.util.ArrayList<>(existingToolMsg.getContent());
              for (Part restartResp : toolResult.getResponses()) {
                Map<String, Object> metadata =
                    restartResp.getMetadata() != null
                        ? new java.util.HashMap<>(restartResp.getMetadata())
                        : new java.util.HashMap<>();
                metadata.put("source", "restart");
                restartResp.setMetadata(metadata);
                mergedContent.add(restartResp);
              }
              existingToolMsg.setContent(mergedContent);
            } else {
              // Create new TOOL message with restart responses
              Message toolResponseMessage = new Message();
              toolResponseMessage.setRole(Role.TOOL);
              List<Part> restartResponses = new java.util.ArrayList<>();
              for (Part restartResp : toolResult.getResponses()) {
                Map<String, Object> metadata =
                    restartResp.getMetadata() != null
                        ? new java.util.HashMap<>(restartResp.getMetadata())
                        : new java.util.HashMap<>();
                metadata.put("source", "restart");
                restartResp.setMetadata(metadata);
                restartResponses.add(restartResp);
              }
              toolResponseMessage.setContent(restartResponses);
              Map<String, Object> toolMsgMetadata = new java.util.HashMap<>();
              toolMsgMetadata.put("resumed", true);
              toolResponseMessage.setMetadata(toolMsgMetadata);
              updatedMessages.add(toolResponseMessage);
            }

            // Recurse through WrapGenerate hooks for the next turn (propagate onChunk)
            GenerateActionOptions nextOpts = opts.withMessages(updatedMessages);
            int nextMsgIdx = params.getMessageIndex() + 1;

            // Stream the resumed tool-response message as a chunk before recursing (mirrors Go
            // generate.go:232-241 and JS action.ts:324-327).
            emitToolResponseChunk(
                params.getOnChunk(),
                toolResult.getResponses(),
                nextMsgIdx,
                streamCurrentRole,
                streamCurrentIndex);

            return generateRef[0].apply(
                actx, new GenerateParams(nextOpts, turn + 1, nextMsgIdx, params.getOnChunk()));
          }

          // Build model call wrapped with WrapModel hooks (resolved per-turn so
          // middleware-modified model names take effect)
          ModelNext wrappedModelCall =
              buildWrappedModelCall(model, opts.getModel(), actx, middlewares);

          // Call model through WrapModel chain (propagate streaming callback from GenerateParams).
          // Wrap the callback so model chunks carry role=model and a monotonic message index,
          // bumping the index on any role transition (mirrors Go generate.go:337-357).
          final java.util.function.Consumer<ModelResponseChunk> rawOnChunk = params.getOnChunk();
          java.util.function.Consumer<ModelResponseChunk> wrappedOnChunk = null;
          if (rawOnChunk != null) {
            streamCurrentIndex[0] = params.getMessageIndex();
            streamCurrentRole[0] = Role.MODEL;
            wrappedOnChunk =
                chunk -> {
                  Role chunkRole = chunk.getRole();
                  if (chunkRole != null && chunkRole != streamCurrentRole[0]) {
                    streamCurrentIndex[0]++;
                    streamCurrentRole[0] = chunkRole;
                  }
                  chunk.setIndex(streamCurrentIndex[0]);
                  if (chunk.getRole() == null) {
                    chunk.setRole(Role.MODEL);
                  }
                  rawOnChunk.accept(chunk);
                };
          }
          ModelParams mparams = new ModelParams(req, wrappedOnChunk);
          ModelResponse response = wrappedModelCall.apply(actx, mparams);

          // Check if the model requested tool calls
          List<Part> toolRequestParts = extractToolRequestParts(response);
          if (toolRequestParts.isEmpty()) {
            return response;
          }

          // Execute tools through WrapTool chain (includes middleware-provided tools)
          ToolExecutionResult toolResult =
              executeToolsWithMiddleware(actx, toolRequestParts, allTools, middlewares);

          // If there are interrupts, return immediately
          if (!toolResult.getInterrupts().isEmpty()) {
            ModelResponse interruptedResponse = buildInterruptedResponse(response, toolResult);
            // Set original request so getMessages() includes conversation history
            interruptedResponse.setRequest(req);
            return interruptedResponse;
          }

          // Build next options with updated messages for recursion through wrapGenerate
          Message assistantMessage = response.getMessage();
          List<Message> updatedMessages = new java.util.ArrayList<>(opts.getMessages());
          updatedMessages.add(assistantMessage);

          Message toolResponseMessage = new Message();
          toolResponseMessage.setRole(Role.TOOL);
          toolResponseMessage.setContent(toolResult.getResponses());
          updatedMessages.add(toolResponseMessage);

          GenerateActionOptions nextOpts = opts.withMessages(updatedMessages);

          // Recurse through the wrapped generate function (goes through WrapGenerate hooks)
          int nextMsgIdx = params.getMessageIndex() + 1;

          // Stream the tool-response message as a chunk before recursing (mirrors Go
          // generate.go:865-874 and JS action.ts:420-425). The chunk carries role=tool, the tool
          // response parts, and the incremented message index.
          emitToolResponseChunk(
              params.getOnChunk(),
              toolResult.getResponses(),
              nextMsgIdx,
              streamCurrentRole,
              streamCurrentIndex);

          return generateRef[0].apply(
              actx, new GenerateParams(nextOpts, turn + 1, nextMsgIdx, params.getOnChunk()));
        };

    // Chain WrapGenerate hooks around the core iteration
    generateRef[0] = chainGenerateHooks(middlewares, rawGenerate);

    // Wrap the whole generate operation (tool-calling loop, middleware, output conformance) in its
    // own "generate" span so traces read flow -> generate -> model, matching the /util/generate
    // action and the JS/Go SDKs. Because Tracer.runInNewSpan makes the span current (via the
    // OpenTelemetry context), the per-turn model span(s) created downstream nest under this
    // generate
    // span, and this generate span nests under the surrounding flow/agent span when one is present.
    // Its input/output (GenerateActionOptions / ModelResponse) differ from the raw model
    // request/response, which is precisely why generate warrants its own span.
    final GenerateActionOptions spanInput = actionOpts;
    final ActionContext genCtx = ctx;
    SpanMetadata generateSpan = SpanMetadata.builder().name("generate").type("util").build();

    // Start generation with high-level options (messageIndex starts at 0, propagate streamCallback)
    return Tracer.runInNewSpan(
        genCtx,
        generateSpan,
        spanInput,
        (spanCtx, opts) ->
            generateRef[0].apply(
                genCtx.withSpanContext(spanCtx), new GenerateParams(opts, 0, 0, streamCallback)));
  }

  /**
   * Emits the tool-response message as a streaming chunk before the generate loop recurses into the
   * next model turn (mirrors Go {@code generate.go} and JS {@code action.ts}: the tool message is
   * streamed as a {@code role: tool} chunk at the incremented message index). No-op when {@code
   * onChunk} is null or there are no responses. Advances the shared streaming role/index trackers
   * so a subsequent model chunk bumps the index correctly.
   */
  private static void emitToolResponseChunk(
      java.util.function.Consumer<ModelResponseChunk> onChunk,
      List<Part> toolResponseParts,
      int messageIndex,
      Role[] streamCurrentRole,
      int[] streamCurrentIndex) {
    if (onChunk == null || toolResponseParts == null || toolResponseParts.isEmpty()) {
      return;
    }
    ModelResponseChunk chunk = new ModelResponseChunk(toolResponseParts);
    chunk.setRole(Role.TOOL);
    chunk.setIndex(messageIndex);
    streamCurrentRole[0] = Role.TOOL;
    streamCurrentIndex[0] = messageIndex;
    onChunk.accept(chunk);
  }

  /** Creates fresh middleware instances for a single generate invocation. */
  private List<GenerationMiddleware> createMiddlewareInstances(List<GenerationMiddleware> use) {
    if (use == null || use.isEmpty()) {
      return List.of();
    }
    return use.stream().map(GenerationMiddleware::newInstance).toList();
  }

  /**
   * Registers each live middleware in the registry's {@code "middleware"} value bucket (wrapped in
   * a parameterless {@link GenerationMiddlewareDesc}) so the Dev UI Middleware panel can list and
   * dispatch it. Registration is idempotent and safe under concurrent {@code generate()} calls.
   */
  private void registerMiddlewareForDevUi(List<GenerationMiddleware> use) {
    if (use == null || use.isEmpty()) {
      return;
    }
    for (GenerationMiddleware mw : use) {
      if (mw == null) continue;
      String name = mw.name();
      if (name == null || name.isEmpty()) continue;
      registerMiddlewareDesc(GenerationMiddlewares.of(mw));
    }
  }

  /**
   * Registers a middleware descriptor into the {@code "middleware"} value bucket so the Dev UI can
   * list it (with a parameters form derived from its {@code configSchema}) and resolve it by name
   * at generate time.
   *
   * <p>Idempotent — a descriptor already registered under the same name is kept (first registration
   * wins). Concurrency-safe — the {@code lookupValue}/{@code registerValue} pair is not atomic, so
   * a concurrent registration of the same name is caught and ignored rather than crashing the
   * generation request with the registry's duplicate-key {@link IllegalStateException}.
   */
  private void registerMiddlewareDesc(GenerationMiddlewareDesc desc) {
    if (desc == null) return;
    String name = desc.name();
    if (name == null || name.isEmpty()) return;
    if (registry.lookupValue("middleware", name) != null) {
      return;
    }
    try {
      registry.registerValue("middleware", name, desc);
    } catch (IllegalStateException e) {
      // Another thread registered the same middleware concurrently — safe to ignore.
    }
  }

  /**
   * Registers middleware shared by plugins implementing {@link MiddlewarePlugin} into the {@code
   * "middleware"} value bucket. Called once during builder {@code build()} after plugin
   * initialization, mirroring JS {@code GenkitPluginV2.middleware()} and Go {@code
   * MiddlewarePlugin.Middlewares()}.
   */
  private void registerPluginMiddlewares() {
    for (Plugin plugin : plugins) {
      if (plugin instanceof MiddlewarePlugin) {
        List<GenerationMiddlewareDesc> descs = ((MiddlewarePlugin) plugin).middlewares(registry);
        if (descs == null) continue;
        for (GenerationMiddlewareDesc desc : descs) {
          registerMiddlewareDesc(desc);
        }
      }
    }
  }

  /**
   * Converts {@link GenerateOptions} to a high-level {@link GenerateActionOptions}.
   *
   * <p>This builds the unresolved options that the {@code wrapGenerate} middleware chain receives.
   * Messages are assembled from the prompt/system/messages fields, and tools are represented as
   * name strings (not resolved definitions).
   */
  private GenerateActionOptions toGenerateActionOptions(
      GenerateOptions<?> options, List<Tool<?, ?>> allTools) {
    GenerateActionOptions opts = new GenerateActionOptions();
    opts.setModel(options.getModel());

    // Build messages from system/prompt/messages (same assembly order as toModelRequest)
    List<Message> messages = new java.util.ArrayList<>();
    if (options.getSystem() != null) {
      messages.add(Message.system(options.getSystem()));
    }
    if (options.getMessages() != null && !options.getMessages().isEmpty()) {
      messages.addAll(options.getMessages());
    } else if (options.getPrompt() != null) {
      messages.add(Message.user(options.getPrompt()));
    }
    opts.setMessages(messages);

    // Tool names as strings (high-level, unresolved)
    if (allTools != null && !allTools.isEmpty()) {
      opts.setTools(
          allTools.stream().map(Tool::getName).collect(java.util.stream.Collectors.toList()));
    }

    if (options.getToolChoice() != null) {
      opts.setToolChoice(options.getToolChoice().toString());
    }
    opts.setConfig(options.getConfig());
    opts.setOutput(options.getOutput());
    opts.setDocs(options.getDocs());
    opts.setMaxTurns(options.getMaxTurns());
    return opts;
  }

  /**
   * Resolves a high-level {@link GenerateActionOptions} into a low-level {@link ModelRequest}.
   *
   * <p>This performs tool name → definition resolution and config conversion, producing the request
   * that is actually sent to the model through the {@code wrapModel} chain.
   */
  private ModelRequest resolveToModelRequest(
      GenerateActionOptions actionOpts, List<Tool<?, ?>> allTools) {
    ModelRequest.Builder builder = ModelRequest.builder();

    if (actionOpts.getMessages() != null) {
      builder.messages(actionOpts.getMessages());
    }

    // Resolve tool names to ToolDefinitions
    if (actionOpts.getTools() != null && !actionOpts.getTools().isEmpty()) {
      List<ToolDefinition> toolDefs = new java.util.ArrayList<>();
      for (String toolName : actionOpts.getTools()) {
        for (Tool<?, ?> tool : allTools) {
          if (tool.getName().equals(toolName)) {
            toolDefs.add(tool.getDefinition());
            break;
          }
        }
      }
      builder.tools(toolDefs);
    }

    // Convert GenerationConfig → Map<String, Object> for ModelRequest
    if (actionOpts.getConfig() != null) {
      GenerationConfig config = actionOpts.getConfig();
      Map<String, Object> configMap = new java.util.HashMap<>();
      if (config.getTemperature() != null) {
        configMap.put("temperature", config.getTemperature());
      }
      if (config.getMaxOutputTokens() != null) {
        configMap.put("maxOutputTokens", config.getMaxOutputTokens());
      }
      if (config.getTopP() != null) {
        configMap.put("topP", config.getTopP());
      }
      if (config.getTopK() != null) {
        configMap.put("topK", config.getTopK());
      }
      if (config.getStopSequences() != null) {
        configMap.put("stopSequences", config.getStopSequences());
      }
      if (config.getPresencePenalty() != null) {
        configMap.put("presencePenalty", config.getPresencePenalty());
      }
      if (config.getFrequencyPenalty() != null) {
        configMap.put("frequencyPenalty", config.getFrequencyPenalty());
      }
      if (config.getSeed() != null) {
        configMap.put("seed", config.getSeed());
      }
      if (config.getCustom() != null) {
        configMap.putAll(config.getCustom());
      }
      builder.config(configMap);
    }

    if (actionOpts.getOutput() != null) {
      builder.output(actionOpts.getOutput());
    }

    if (actionOpts.getDocs() != null && !actionOpts.getDocs().isEmpty()) {
      builder.context(actionOpts.getDocs());
    }

    return builder.build();
  }

  /** Builds the model call function wrapped with WrapModel hooks from middleware. */
  private ModelNext buildWrappedModelCall(
      Model model, String modelName, ActionContext ctx, List<GenerationMiddleware> middlewares) {

    // Core model call with telemetry
    ModelNext core =
        (actx, mparams) -> {
          ModelRequest req = mparams.getRequest();

          SpanMetadata modelSpanMetadata =
              SpanMetadata.builder()
                  .name(modelName)
                  .type(ActionType.MODEL.getValue())
                  .subtype("model")
                  .build();

          String flowName = actx.getFlowName();
          if (flowName != null) {
            modelSpanMetadata.getAttributes().put("genkit:metadata:flow:name", flowName);
          }

          final String spanPath = "/generate/" + modelName;
          final java.util.function.Consumer<ModelResponseChunk> sc = mparams.getStreamCallback();
          return Tracer.runInNewSpan(
              actx,
              modelSpanMetadata,
              req,
              (spanCtx, r) -> {
                if (sc != null) {
                  // Streaming: use streaming telemetry and pass callback to model
                  return ModelTelemetryHelper.runWithTelemetryStreaming(
                      modelName,
                      flowName,
                      spanPath,
                      req,
                      mr -> model.run(actx.withSpanContext(spanCtx), mr, sc));
                } else {
                  // Non-streaming
                  return ModelTelemetryHelper.runWithTelemetry(
                      modelName,
                      flowName,
                      spanPath,
                      req,
                      mr -> model.run(actx.withSpanContext(spanCtx), mr));
                }
              });
        };

    return chainModelHooks(middlewares, core);
  }

  /** Chains WrapGenerate hooks. First middleware is outermost. */
  private GenerateNext chainGenerateHooks(
      List<GenerationMiddleware> middlewares, GenerateNext core) {
    if (middlewares.isEmpty()) {
      return core;
    }
    GenerateNext current = core;
    for (int i = middlewares.size() - 1; i >= 0; i--) {
      final GenerationMiddleware mw = middlewares.get(i);
      final GenerateNext next = current;
      current = (ctx, params) -> mw.wrapGenerate(ctx, params, next);
    }
    return current;
  }

  /** Chains WrapModel hooks. First middleware is outermost. */
  private ModelNext chainModelHooks(List<GenerationMiddleware> middlewares, ModelNext core) {
    if (middlewares.isEmpty()) {
      return core;
    }
    ModelNext current = core;
    for (int i = middlewares.size() - 1; i >= 0; i--) {
      final GenerationMiddleware mw = middlewares.get(i);
      final ModelNext next = current;
      current = (ctx, params) -> mw.wrapModel(ctx, params, next);
    }
    return current;
  }

  /** Chains WrapTool hooks. First middleware is outermost. */
  private ToolNext chainToolHooks(List<GenerationMiddleware> middlewares, ToolNext core) {
    if (middlewares.isEmpty()) {
      return core;
    }
    ToolNext current = core;
    for (int i = middlewares.size() - 1; i >= 0; i--) {
      final GenerationMiddleware mw = middlewares.get(i);
      final ToolNext next = current;
      current = (ctx, params) -> mw.wrapTool(ctx, params, next);
    }
    return current;
  }

  /** Executes tools with WrapTool middleware hooks applied. */
  private ToolExecutionResult executeToolsWithMiddleware(
      ActionContext ctx,
      List<Part> toolRequestParts,
      List<Tool<?, ?>> tools,
      List<GenerationMiddleware> middlewares) {

    // Build WrapTool chain
    ToolNext wrappedToolCall =
        chainToolHooks(
            middlewares,
            (actx, tparams) -> {
              Tool<?, ?> tool = tparams.getTool();
              ToolRequest toolReq = tparams.getRequest();

              Object toolInput = toolReq.getInput();
              Class<?> inputClass = tool.getInputClass();
              if (inputClass != null && toolInput != null && !inputClass.isInstance(toolInput)) {
                toolInput = JsonUtils.convert(toolInput, inputClass);
              }

              @SuppressWarnings("unchecked")
              Tool<Object, Object> typedTool = (Tool<Object, Object>) tool;
              Object result = typedTool.run(actx, toolInput);

              return Part.toolResponse(
                  new ToolResponse(toolReq.getRef(), toolReq.getName(), result));
            });

    List<Part> responseParts = new java.util.ArrayList<>();
    List<Part> interrupts = new java.util.ArrayList<>();
    Map<String, Part> interruptMap = new java.util.HashMap<>();
    Map<String, Object> pendingOutputMap = new java.util.HashMap<>();

    for (Part toolRequestPart : toolRequestParts) {
      ToolRequest toolRequest = toolRequestPart.getToolRequest();
      String toolName = toolRequest.getName();
      String key = toolName + "#" + (toolRequest.getRef() != null ? toolRequest.getRef() : "");

      Tool<?, ?> tool = findTool(toolName, tools);
      if (tool == null) {
        Part errorPart = new Part();
        ToolResponse errorResponse =
            new ToolResponse(
                toolRequest.getRef(), toolName, Map.of("error", "Tool not found: " + toolName));
        errorPart.setToolResponse(errorResponse);
        responseParts.add(errorPart);
        continue;
      }

      try {
        // Execute through WrapTool chain. When this tool request is a RESTART (its Part carries
        // `resumed` metadata attached by Tool.restart(...) / GenkitBeta.toResumeOptions), thread
        // the
        // resumed value and original input into the ActionContext so a restart-aware tool handler
        // can observe ctx.isResumed()/getResumed()/getOriginalInput() (mirrors Go's
        // handleResumedToolRequest → ToolContext.Resumed and JS ToolRunOptions.resumed).
        ActionContext toolCtx = ctx;
        Map<String, Object> partMeta = toolRequestPart.getMetadata();
        if (partMeta != null && partMeta.containsKey("resumed")) {
          Object resumedValue = partMeta.get("resumed");
          Object originalInput =
              partMeta.containsKey("replacedInput")
                  ? partMeta.get("replacedInput")
                  : toolRequest.getInput();
          toolCtx = ctx.withResumed(resumedValue, originalInput);
        }
        ToolParams tparams = new ToolParams(toolRequestPart, tool);
        Part responsePart = wrappedToolCall.apply(toolCtx, tparams);

        responseParts.add(responsePart);

        pendingOutputMap.put(key, responsePart.getToolResponse().getOutput());

        logger.debug("Executed tool '{}' successfully", toolName);

      } catch (ToolInterruptException e) {
        Map<String, Object> interruptMetadata = e.getMetadata();

        Part interruptPart = new Part();
        interruptPart.setToolRequest(toolRequest);
        Map<String, Object> metadata =
            toolRequestPart.getMetadata() != null
                ? new java.util.HashMap<>(toolRequestPart.getMetadata())
                : new java.util.HashMap<>();
        metadata.put(
            "interrupt",
            interruptMetadata != null && !interruptMetadata.isEmpty() ? interruptMetadata : true);
        interruptPart.setMetadata(metadata);

        interrupts.add(interruptPart);
        interruptMap.put(key, interruptPart);

        logger.debug("Tool '{}' triggered interrupt", toolName);

      } catch (Exception e) {
        logger.error("Tool execution failed for '{}': {}", toolName, e.getMessage());
        Part errorPart = new Part();
        ToolResponse errorResponse =
            new ToolResponse(
                toolRequest.getRef(),
                toolName,
                Map.of("error", "Tool execution failed: " + e.getMessage()));
        errorPart.setToolResponse(errorResponse);
        responseParts.add(errorPart);
      }
    }

    return new ToolExecutionResult(responseParts, interrupts, interruptMap, pendingOutputMap);
  }

  /** Handles resume options by processing respond and restart directives. */
  private GenerateActionOptions handleResumeOption(
      GenerateActionOptions actionOpts, GenerateOptions<?> options) {
    ResumeOptions resume = options.getResume();
    List<Message> messages = new java.util.ArrayList<>(actionOpts.getMessages());

    if (messages.isEmpty()) {
      throw new GenkitException("Cannot resume generation with no messages");
    }

    Message lastMessage = messages.get(messages.size() - 1);
    if (lastMessage.getRole() != Role.MODEL) {
      throw new GenkitException("Cannot resume unless the last message is from the model");
    }

    // Build tool response parts from resume options
    List<Part> toolResponseParts = new java.util.ArrayList<>();

    // Collect tool names/refs from respond directives
    java.util.Set<String> respondedTools = new java.util.HashSet<>();

    // Handle respond directives
    if (resume.getRespond() != null) {
      for (ToolResponse toolResponse : resume.getRespond()) {
        respondedTools.add(
            toolResponse.getName()
                + "#"
                + (toolResponse.getRef() != null ? toolResponse.getRef() : ""));
        Part responsePart = new Part();
        responsePart.setToolResponse(toolResponse);
        Map<String, Object> metadata = new java.util.HashMap<>();
        metadata.put("interruptResponse", true);
        responsePart.setMetadata(metadata);
        toolResponseParts.add(responsePart);
      }
    }

    // Note: restart directives are handled inside the generate loop
    // for proper middleware lifecycle (wrapTool and wrapGenerate hooks fire correctly)
    boolean hasRespond = resume.getRespond() != null && !resume.getRespond().isEmpty();
    boolean hasRestart = resume.getRestart() != null && !resume.getRestart().isEmpty();

    if (!hasRespond && !hasRestart) {
      throw new GenkitException("Resume options must contain either respond or restart directives");
    }

    // Collect tool names/refs from restart directives to avoid duplicating their responses
    java.util.Set<String> restartedTools = new java.util.HashSet<>();
    if (resume.getRestart() != null) {
      for (ToolRequest toolRequest : resume.getRestart()) {
        restartedTools.add(
            toolRequest.getName()
                + "#"
                + (toolRequest.getRef() != null ? toolRequest.getRef() : ""));
      }
    }

    // Add tool responses for completed tools (pendingOutput metadata) that aren't
    // being explicitly responded to or restarted. This ensures all tool_calls in the
    // model message have matching tool responses (required by providers like OpenAI).
    for (Part part : lastMessage.getContent()) {
      if (part.getToolRequest() != null && part.getMetadata() != null) {
        Object pendingOutput = part.getMetadata().get("pendingOutput");
        if (pendingOutput != null) {
          String key =
              part.getToolRequest().getName()
                  + "#"
                  + (part.getToolRequest().getRef() != null ? part.getToolRequest().getRef() : "");
          if (!respondedTools.contains(key) && !restartedTools.contains(key)) {
            Part responsePart = new Part();
            ToolResponse toolResponse =
                new ToolResponse(
                    part.getToolRequest().getRef(), part.getToolRequest().getName(), pendingOutput);
            responsePart.setToolResponse(toolResponse);
            Map<String, Object> metadata = new java.util.HashMap<>();
            metadata.put("pendingOutput", true);
            responsePart.setMetadata(metadata);
            toolResponseParts.add(responsePart);
          }
        }
      }
    }

    if (!toolResponseParts.isEmpty()) {
      // Add tool response message for completed and responded tools
      Message toolResponseMessage = new Message();
      toolResponseMessage.setRole(Role.TOOL);
      toolResponseMessage.setContent(toolResponseParts);
      Map<String, Object> toolMsgMetadata = new java.util.HashMap<>();
      toolMsgMetadata.put("resumed", true);
      toolResponseMessage.setMetadata(toolMsgMetadata);
      messages.add(toolResponseMessage);
    }

    return actionOpts.withMessages(messages);
  }

  /** Builds an interrupted response from the model response and tool execution result. */
  private ModelResponse buildInterruptedResponse(
      ModelResponse response, ToolExecutionResult toolResult) {
    // Update the model message content with interrupt metadata
    Message originalMessage = response.getMessage();
    List<Part> updatedContent = new java.util.ArrayList<>();

    for (Part part : originalMessage.getContent()) {
      if (part.getToolRequest() != null) {
        ToolRequest toolRequest = part.getToolRequest();
        String key =
            toolRequest.getName()
                + "#"
                + (toolRequest.getRef() != null ? toolRequest.getRef() : "");

        // Check if this tool request was interrupted
        Part interruptPart = toolResult.getInterruptMap().get(key);
        if (interruptPart != null) {
          updatedContent.add(interruptPart);
        } else {
          // Check for pending output
          Object pendingOutput = toolResult.getPendingOutputMap().get(key);
          if (pendingOutput != null) {
            Part pendingPart = new Part();
            pendingPart.setToolRequest(toolRequest);
            Map<String, Object> metadata =
                part.getMetadata() != null
                    ? new java.util.HashMap<>(part.getMetadata())
                    : new java.util.HashMap<>();
            metadata.put("pendingOutput", pendingOutput);
            pendingPart.setMetadata(metadata);
            updatedContent.add(pendingPart);
          } else {
            updatedContent.add(part);
          }
        }
      } else {
        updatedContent.add(part);
      }
    }

    Message updatedMessage = new Message();
    updatedMessage.setRole(originalMessage.getRole());
    updatedMessage.setContent(updatedContent);
    updatedMessage.setMetadata(originalMessage.getMetadata());

    // Create candidate with updated message
    Candidate updatedCandidate = new Candidate();
    updatedCandidate.setMessage(updatedMessage);
    updatedCandidate.setFinishReason(FinishReason.INTERRUPTED);

    return ModelResponse.builder()
        .candidates(List.of(updatedCandidate))
        .usage(response.getUsage())
        .request(response.getRequest())
        .custom(response.getCustom())
        .latencyMs(response.getLatencyMs())
        .finishReason(FinishReason.INTERRUPTED)
        .finishMessage("One or more tool calls resulted in interrupts.")
        .interrupts(toolResult.getInterrupts())
        .build();
  }

  /** Extracts tool request parts from a model response. */
  private List<Part> extractToolRequestParts(ModelResponse response) {
    List<Part> parts = new java.util.ArrayList<>();
    if (response.getCandidates() != null) {
      for (Candidate candidate : response.getCandidates()) {
        if (candidate.getMessage() != null && candidate.getMessage().getContent() != null) {
          for (Part part : candidate.getMessage().getContent()) {
            if (part.getToolRequest() != null) {
              parts.add(part);
            }
          }
        }
      }
    }
    return parts;
  }

  /** Extracts tool requests from a model response. */
  private List<ToolRequest> extractToolRequests(ModelResponse response) {
    List<ToolRequest> requests = new java.util.ArrayList<>();
    if (response.getCandidates() != null) {
      for (Candidate candidate : response.getCandidates()) {
        if (candidate.getMessage() != null && candidate.getMessage().getContent() != null) {
          for (Part part : candidate.getMessage().getContent()) {
            if (part.getToolRequest() != null) {
              requests.add(part.getToolRequest());
            }
          }
        }
      }
    }
    return requests;
  }

  /** Result of tool execution with interrupt handling. */
  private static class ToolExecutionResult {
    private final List<Part> responses;
    private final List<Part> interrupts;
    private final Map<String, Part> interruptMap;
    private final Map<String, Object> pendingOutputMap;

    ToolExecutionResult(
        List<Part> responses,
        List<Part> interrupts,
        Map<String, Part> interruptMap,
        Map<String, Object> pendingOutputMap) {
      this.responses = responses;
      this.interrupts = interrupts;
      this.interruptMap = interruptMap;
      this.pendingOutputMap = pendingOutputMap;
    }

    List<Part> getResponses() {
      return responses;
    }

    List<Part> getInterrupts() {
      return interrupts;
    }

    Map<String, Part> getInterruptMap() {
      return interruptMap;
    }

    Map<String, Object> getPendingOutputMap() {
      return pendingOutputMap;
    }
  }

  /** Executes tools with interrupt handling. */
  private ToolExecutionResult executeToolsWithInterruptHandling(
      ActionContext ctx, List<Part> toolRequestParts, List<Tool<?, ?>> tools) {

    List<Part> responseParts = new java.util.ArrayList<>();
    List<Part> interrupts = new java.util.ArrayList<>();
    Map<String, Part> interruptMap = new java.util.HashMap<>();
    Map<String, Object> pendingOutputMap = new java.util.HashMap<>();

    for (Part toolRequestPart : toolRequestParts) {
      ToolRequest toolRequest = toolRequestPart.getToolRequest();
      String toolName = toolRequest.getName();
      String key = toolName + "#" + (toolRequest.getRef() != null ? toolRequest.getRef() : "");

      // Find the tool
      Tool<?, ?> tool = findTool(toolName, tools);
      if (tool == null) {
        Part errorPart = new Part();
        ToolResponse errorResponse =
            new ToolResponse(
                toolRequest.getRef(), toolName, Map.of("error", "Tool not found: " + toolName));
        errorPart.setToolResponse(errorResponse);
        responseParts.add(errorPart);
        continue;
      }

      // Check if this is an interrupt tool (has "interrupt" metadata marker)
      boolean isInterruptTool =
          tool.getMetadata() != null && Boolean.TRUE.equals(tool.getMetadata().get("interrupt"));

      try {
        // Convert input to the expected type
        Object toolInput = toolRequest.getInput();
        Class<?> inputClass = tool.getInputClass();
        if (inputClass != null && toolInput != null && !inputClass.isInstance(toolInput)) {
          toolInput = JsonUtils.convert(toolInput, inputClass);
        }

        // Execute the tool
        @SuppressWarnings("unchecked")
        Tool<Object, Object> typedTool = (Tool<Object, Object>) tool;
        Object result = typedTool.run(ctx, toolInput);

        // Create tool response part
        Part responsePart = new Part();
        ToolResponse toolResponse = new ToolResponse(toolRequest.getRef(), toolName, result);
        responsePart.setToolResponse(toolResponse);
        responseParts.add(responsePart);

        // Store pending output in case other tools interrupt
        pendingOutputMap.put(key, result);

        logger.debug("Executed tool '{}' successfully", toolName);

      } catch (ToolInterruptException e) {
        // Tool interrupted - store the interrupt
        Map<String, Object> interruptMetadata = e.getMetadata();

        Part interruptPart = new Part();
        interruptPart.setToolRequest(toolRequest);
        Map<String, Object> metadata =
            toolRequestPart.getMetadata() != null
                ? new java.util.HashMap<>(toolRequestPart.getMetadata())
                : new java.util.HashMap<>();
        metadata.put(
            "interrupt",
            interruptMetadata != null && !interruptMetadata.isEmpty() ? interruptMetadata : true);
        interruptPart.setMetadata(metadata);

        interrupts.add(interruptPart);
        interruptMap.put(key, interruptPart);

        logger.debug("Tool '{}' triggered interrupt", toolName);

      } catch (Exception e) {
        logger.error("Tool execution failed for '{}': {}", toolName, e.getMessage());
        Part errorPart = new Part();
        ToolResponse errorResponse =
            new ToolResponse(
                toolRequest.getRef(),
                toolName,
                Map.of("error", "Tool execution failed: " + e.getMessage()));
        errorPart.setToolResponse(errorResponse);
        responseParts.add(errorPart);
      }
    }

    return new ToolExecutionResult(responseParts, interrupts, interruptMap, pendingOutputMap);
  }

  /** Executes tools and returns the response parts. */
  private List<Part> executeTools(
      ActionContext ctx, List<ToolRequest> toolRequests, List<Tool<?, ?>> tools) {
    List<Part> responseParts = new java.util.ArrayList<>();

    for (ToolRequest toolRequest : toolRequests) {
      String toolName = toolRequest.getName();
      Object toolInput = toolRequest.getInput();

      // Find the tool
      Tool<?, ?> tool = findTool(toolName, tools);
      if (tool == null) {
        // Tool not found, create an error response
        Part errorPart = new Part();
        ToolResponse errorResponse =
            new ToolResponse(
                toolRequest.getRef(), toolName, Map.of("error", "Tool not found: " + toolName));
        errorPart.setToolResponse(errorResponse);
        responseParts.add(errorPart);
        continue;
      }

      try {
        // Execute the tool
        @SuppressWarnings("unchecked")
        Tool<Object, Object> typedTool = (Tool<Object, Object>) tool;
        Object result = typedTool.run(ctx, toolInput);

        // Create tool response part
        Part responsePart = new Part();
        ToolResponse toolResponse = new ToolResponse(toolRequest.getRef(), toolName, result);
        responsePart.setToolResponse(toolResponse);
        responseParts.add(responsePart);

        logger.debug("Executed tool '{}' successfully", toolName);
      } catch (Exception e) {
        logger.error("Tool execution failed for '{}': {}", toolName, e.getMessage());
        Part errorPart = new Part();
        ToolResponse errorResponse =
            new ToolResponse(
                toolRequest.getRef(),
                toolName,
                Map.of("error", "Tool execution failed: " + e.getMessage()));
        errorPart.setToolResponse(errorResponse);
        responseParts.add(errorPart);
      }
    }

    return responseParts;
  }

  /** Finds a tool by name. */
  private Tool<?, ?> findTool(String toolName, List<Tool<?, ?>> tools) {
    if (tools != null) {
      for (Tool<?, ?> tool : tools) {
        if (tool.getName().equals(toolName)) {
          return tool;
        }
      }
    }

    // Also try to find in registry
    Action<?, ?, ?> action = registry.lookupAction(ActionType.TOOL, toolName);
    if (action instanceof Tool) {
      return (Tool<?, ?>) action;
    }

    return null;
  }

  /**
   * Generates a streaming model response using the specified options.
   *
   * <p>This method invokes the model with streaming enabled, calling the provided callback for each
   * chunk of the response as it arrives. The streaming callback is propagated through the entire
   * middleware chain — {@code wrapGenerate} middleware can observe it via {@link
   * com.google.genkit.ai.middleware.GenerateParams#getOnChunk()}, and {@code wrapModel} middleware
   * can observe/transform it via {@link
   * com.google.genkit.ai.middleware.ModelParams#getStreamCallback()}.
   *
   * <p>This is useful for displaying responses incrementally to users, and for middleware that
   * needs to intercept streaming chunks (e.g., logging, guardrails, metrics).
   *
   * <p>Example usage:
   *
   * <pre>{@code
   * StringBuilder result = new StringBuilder();
   * ModelResponse response = genkit.generateStream(
   *     GenerateOptions.builder().model("openai/gpt-4o").prompt("Tell me a story").build(),
   *     chunk -> {
   *       System.out.print(chunk.getText());
   *       result.append(chunk.getText());
   *     });
   * }</pre>
   *
   * @param options the generate options
   * @param streamCallback callback invoked for each response chunk
   * @return the final aggregated model response
   * @throws GenkitException if generation fails or model doesn't support streaming
   */
  public ModelResponse generateStream(
      GenerateOptions<?> options, java.util.function.Consumer<ModelResponseChunk> streamCallback)
      throws GenkitException {
    Model model = getModel(options.getModel());
    if (!model.supportsStreaming()) {
      throw new GenkitException("Model " + options.getModel() + " does not support streaming");
    }
    // Delegate to generateInternal with the streaming callback.
    // The callback flows through the full middleware chain:
    //   generateInternal → GenerateParams.onChunk → wrapGenerate hooks
    //   → rawGenerate → ModelParams.streamCallback → wrapModel hooks → model.run(ctx, req, cb)
    return generateInternal(options, streamCallback);
  }

  /**
   * Generates a model response with a simple prompt.
   *
   * @param modelName the model name
   * @param prompt the prompt text
   * @return the model response
   * @throws GenkitException if generation fails
   */
  public ModelResponse generate(String modelName, String prompt) throws GenkitException {
    return generate(GenerateOptions.builder().model(modelName).prompt(prompt).build());
  }

  /**
   * Generates a structured output from the model, returning a typed object.
   *
   * <p>This method automatically generates a JSON schema from the provided class and deserializes
   * the model's response into an instance of that class. You can add descriptions to fields using
   * {@code @JsonPropertyDescription} and mark fields as required using
   * {@code @JsonProperty(required = true)}:
   *
   * <pre>
   * {
   *   &#64;code
   *   public class MenuItem {
   *     &#64;JsonProperty(required = true)
   *     &#64;JsonPropertyDescription("The name of the menu item")
   *     private String name;
   *
   *     &#64;JsonPropertyDescription("A description of the menu item")
   *     private String description;
   *
   *     @JsonProperty(required = true)
   *     &#64;JsonPropertyDescription("The estimated number of calories")
   *     private int calories;
   *
   *     // getters/setters...
   *   }
   *
   *   // Usage:
   *   MenuItem item = genkit.generate(
   *       GenerateOptions.<MenuItem>builder()
   *           .model("openai/gpt-4o-mini")
   *           .prompt("Suggest a menu item for a pirate-themed restaurant.")
   *           .outputClass(MenuItem.class)
   *           .build());
   * }
   * </pre>
   *
   * @param <T> the output type
   * @param options the generate options with outputClass set
   * @return the generated object
   * @throws GenkitException if generation or deserialization fails
   */
  @SuppressWarnings("unchecked")
  public <T> T generateObject(GenerateOptions options) throws GenkitException {
    if (options.getOutputClass() == null) {
      throw new GenkitException("outputClass must be set in GenerateOptions for typed generate");
    }

    // Call generateInternal to get ModelResponse, then deserialize
    ModelResponse response = generateInternal(options);
    Class<T> outputClass = (Class<T>) options.getOutputClass();

    try {
      String rawOutput = response.getText();
      String jsonOutput = extractJson(rawOutput);
      return JsonUtils.fromJson(jsonOutput, outputClass);
    } catch (Exception e) {
      throw new GenkitException(
          "Failed to deserialize model output to "
              + outputClass.getSimpleName()
              + ": "
              + e.getMessage(),
          e);
    }
  }

  /**
   * Generates a structured output from the model with a simple prompt.
   *
   * <p>Convenience method that combines model name, prompt, and output class.
   *
   * <pre>{@code
   * MenuItem item = genkit.generateObject(
   *     "openai/gpt-4o-mini",
   *     "Suggest a menu item for a pirate-themed restaurant.",
   *     MenuItem.class);
   * }</pre>
   *
   * @param <T> the output type
   * @param modelName the model name
   * @param prompt the prompt text
   * @param outputClass the class to deserialize the response into
   * @return the generated object
   * @throws GenkitException if generation or deserialization fails
   */
  public <T> T generateObject(String modelName, String prompt, Class<T> outputClass)
      throws GenkitException {
    return generateObject(
        GenerateOptions.<T>builder()
            .model(modelName)
            .prompt(prompt)
            .outputClass(outputClass)
            .build());
  }

  /**
   * Generates a structured output from the model, returning a typed object.
   *
   * <p>Alternative method that takes outputClass as a separate parameter.
   *
   * @param <T> the output type
   * @param options the generate options
   * @param outputClass the class to deserialize the response into
   * @return the generated object
   * @throws GenkitException if generation or deserialization fails
   */
  public <T> T generateObject(GenerateOptions<?> options, Class<T> outputClass)
      throws GenkitException {
    // Build options with output config from class
    GenerateOptions<T> optionsWithOutput =
        GenerateOptions.<T>builder()
            .model(options.getModel())
            .prompt(options.getPrompt())
            .messages(options.getMessages())
            .docs(options.getDocs())
            .system(options.getSystem())
            .tools(options.getTools())
            .toolChoice(options.getToolChoice())
            .outputClass(outputClass)
            .config(options.getConfig())
            .context(options.getContext())
            .maxTurns(options.getMaxTurns())
            .resume(options.getResume())
            .build();

    return generateObject(optionsWithOutput);
  }

  /**
   * Generates a structured output from the model with a simple prompt.
   *
   * <p>Convenience method that combines model name, prompt, and output class.
   *
   * <p>Embeds documents using the specified embedder.
   *
   * @param embedderName the embedder name
   * @param documents the documents to embed
   * @return the embed response
   * @throws GenkitException if embedding fails
   */
  public EmbedResponse embed(String embedderName, List<Document> documents) throws GenkitException {
    Embedder embedder = getEmbedder(embedderName);
    EmbedRequest request = new EmbedRequest(documents);
    ActionContext ctx = new ActionContext(registry);
    return embedder.run(ctx, request);
  }

  /**
   * Retrieves documents using the specified retriever.
   *
   * <p>This is the primary method for retrieval in RAG workflows. The returned documents can be
   * passed directly to {@code generate()} via the {@code .docs()} option.
   *
   * <p>Example usage:
   *
   * <pre>{@code
   * // Retrieve relevant documents
   * List<Document> docs = genkit.retrieve("myStore/docs", "What is the capital of France?");
   *
   * // Use documents in generation
   * ModelResponse response = genkit.generate(
   *     GenerateOptions.builder()
   *         .model("openai/gpt-4o-mini")
   *         .prompt("Answer the question based on context")
   *         .docs(docs)
   *         .build());
   * }</pre>
   *
   * @param retrieverName the retriever name
   * @param query the query text
   * @return the list of retrieved documents
   * @throws GenkitException if retrieval fails
   */
  public List<Document> retrieve(String retrieverName, String query) throws GenkitException {
    Retriever retriever = getRetriever(retrieverName);
    RetrieverRequest request = RetrieverRequest.fromText(query);
    ActionContext ctx = new ActionContext(registry);
    RetrieverResponse response = retriever.run(ctx, request);
    return response.getDocuments();
  }

  /**
   * Retrieves documents using the specified retriever with options.
   *
   * <p>Example usage:
   *
   * <pre>{@code
   * List<Document> docs = genkit
   *     .retrieve("myStore/docs", "query", RetrieverParams.builder().k(5).build());
   * }</pre>
   *
   * @param retrieverName the retriever name
   * @param query the query text
   * @param options retrieval options (e.g., k for number of results)
   * @return the list of retrieved documents
   * @throws GenkitException if retrieval fails
   */
  public List<Document> retrieve(
      String retrieverName, String query, RetrieverRequest.RetrieverOptions options)
      throws GenkitException {
    Retriever retriever = getRetriever(retrieverName);
    RetrieverRequest request = RetrieverRequest.fromText(query);
    request.setOptions(options);
    ActionContext ctx = new ActionContext(registry);
    RetrieverResponse response = retriever.run(ctx, request);
    return response.getDocuments();
  }

  /**
   * Retrieves documents using a Document as the query.
   *
   * @param retrieverName the retriever name
   * @param query the query document
   * @return the list of retrieved documents
   * @throws GenkitException if retrieval fails
   */
  public List<Document> retrieve(String retrieverName, Document query) throws GenkitException {
    Retriever retriever = getRetriever(retrieverName);
    RetrieverRequest request = new RetrieverRequest(query);
    ActionContext ctx = new ActionContext(registry);
    RetrieverResponse response = retriever.run(ctx, request);
    return response.getDocuments();
  }

  /**
   * Indexes documents using the specified indexer.
   *
   * <p>Example usage:
   *
   * <pre>{@code
   * List<Document> docs = List.of(
   *     Document.fromText("Paris is the capital of France."),
   *     Document.fromText("Berlin is the capital of Germany."));
   * genkit.index("myStore/docs", docs);
   * }</pre>
   *
   * @param indexerName the indexer name
   * @param documents the documents to index
   * @throws GenkitException if indexing fails
   */
  public void index(String indexerName, List<Document> documents) throws GenkitException {
    Indexer indexer = getIndexer(indexerName);
    IndexerRequest request = new IndexerRequest(documents);
    ActionContext ctx = new ActionContext(registry);
    indexer.run(ctx, request);
  }

  /**
   * Gets an indexer by name.
   *
   * @param name the indexer name
   * @return the indexer
   */
  public Indexer getIndexer(String name) {
    Action<?, ?, ?> action = registry.lookupAction(ActionType.INDEXER, name);
    if (action == null) {
      throw new GenkitException("Indexer not found: " + name);
    }
    return (Indexer) action;
  }

  /**
   * Runs a flow by name.
   *
   * @param <I> the input type
   * @param <O> the output type
   * @param flowName the flow name
   * @param input the flow input
   * @return the flow output
   * @throws GenkitException if execution fails
   */
  @SuppressWarnings("unchecked")
  public <I, O> O runFlow(String flowName, I input) throws GenkitException {
    Action<?, ?, ?> action = registry.lookupAction(ActionType.FLOW, flowName);
    if (action == null) {
      throw new GenkitException("Flow not found: " + flowName);
    }
    Flow<I, O, ?> flow = (Flow<I, O, ?>) action;
    ActionContext ctx = new ActionContext(registry);
    return flow.run(ctx, input);
  }

  /**
   * Gets the registry.
   *
   * @return the registry
   */
  public Registry getRegistry() {
    return registry;
  }

  /**
   * Gets the options.
   *
   * @return the options
   */
  public GenkitOptions getOptions() {
    return options;
  }

  /**
   * Returns the beta (experimental) API surface for this Genkit instance.
   *
   * <p>The beta API exposes experimental features such as the {@code defineAgent}, {@code
   * definePromptAgent}, and {@code defineCustomAgent} bidi agent actions. These methods are gated
   * behind the {@code experimental} flag (see {@link GenkitOptions.Builder#experimental} or the
   * {@code GENKIT_EXPERIMENTAL} environment variable) and throw a {@link GenkitException} if it is
   * not enabled.
   *
   * <pre>{@code
   * Genkit ai = new Genkit(GenkitOptions.builder().experimental(true).build());
   * Agent<MyState> agent = ai.beta().defineAgent(
   *     AgentConfig.<MyState>builder()
   *         .name("helper")
   *         .system("You are helpful.")
   *         .model("googleai/gemini-2.5-flash")
   *         .build());
   * }</pre>
   *
   * @return the beta API surface
   */
  public GenkitBeta beta() {
    return new GenkitBeta(this);
  }

  /**
   * Returns whether experimental features are enabled for this instance.
   *
   * @return true if experimental features are enabled
   */
  public boolean isExperimental() {
    return options.isExperimental();
  }

  /**
   * Gets the registered plugins.
   *
   * @return the plugins
   */
  public List<Plugin> getPlugins() {
    return plugins;
  }

  /** Starts the reflection server for dev tools integration. */
  private void startReflectionServer() {
    String v2ServerUrl = System.getenv("GENKIT_REFLECTION_V2_SERVER");
    if (v2ServerUrl != null && !v2ServerUrl.isEmpty()) {
      startReflectionServerV2(v2ServerUrl);
    } else {
      startReflectionServerV1();
    }
  }

  private void startReflectionServerV1() {
    try {
      int basePort = options.getReflectionPort();
      reflectionServer = new ReflectionServer(registry, basePort, options.getName());
      reflectionServer.start();
      int actualPort = reflectionServer.getPort();
      logger.info("Reflection server started on port {}", actualPort);

      // Write runtime file with matching runtime ID
      RuntimeFileWriter.write(actualPort, reflectionServer.getRuntimeId());
    } catch (Exception e) {
      logger.error("Failed to start reflection server", e);
      throw new GenkitException("Failed to start reflection server", e);
    }
  }

  private void startReflectionServerV2(String serverUrl) {
    try {
      reflectionServerV2 = new ReflectionServerV2(registry, serverUrl, options.getName());
      reflectionServerV2.start();
      logger.info("Reflection V2 client connecting to {}", serverUrl);
    } catch (Exception e) {
      logger.error("Failed to start reflection V2 client", e);
      throw new GenkitException("Failed to start reflection V2 client", e);
    }
  }

  /** Stops the Genkit instance and cleans up resources. */
  public void stop() {
    if (reflectionServerV2 != null) {
      try {
        reflectionServerV2.stop();
      } catch (Exception e) {
        logger.warn("Error stopping reflection V2 client", e);
      }
    }
    if (reflectionServer != null) {
      try {
        reflectionServer.stop();
        RuntimeFileWriter.cleanup();
      } catch (Exception e) {
        logger.warn("Error stopping reflection server", e);
      }
    }
  }

  // =========================================================================
  // Interrupt Methods
  // =========================================================================

  /**
   * Defines an interrupt tool for human-in-the-loop interactions.
   *
   * <p>Interrupts allow tools to pause generation and request user input. When a tool throws a
   * {@link ToolInterruptException}, the chat returns early with the interrupt information, allowing
   * the application to collect user input and resume.
   *
   * <p>Example usage:
   *
   * <pre>{@code
   * // Define an interrupt for confirming actions
   * Tool<ConfirmInput, ConfirmOutput> confirmInterrupt = genkit.defineInterrupt(
   *     InterruptConfig.<ConfirmInput, ConfirmOutput>builder()
   *         .name("confirm")
   *         .description("Asks user to confirm an action")
   *         .inputType(ConfirmInput.class)
   *         .outputType(ConfirmOutput.class)
   *         .build());
   *
   * // Use in a chat with tools
   * Chat chat = genkit.chat(
   *     ChatOptions.builder()
   *         .model("openai/gpt-4o")
   *         .tools(List.of(someActionTool, confirmInterrupt))
   *         .build());
   *
   * ModelResponse response = chat.send("Book a table for 4");
   *
   * // Check for interrupts
   * if (chat.hasPendingInterrupts()) {
   *   List<InterruptRequest> interrupts = chat.getPendingInterrupts();
   *   // Show UI to user, collect response
   *   ConfirmOutput userResponse = getUserConfirmation(interrupts.get(0));
   *
   *   // Resume with user response
   *   response = chat.send(
   *       "",
   *       SendOptions.builder()
   *           .resumeOptions(
   *               ResumeOptions.builder()
   *                   .respond(List.of(interrupts.get(0).respond(userResponse)))
   *                   .build())
   *           .build());
   * }
   * }</pre>
   *
   * @param <I> the interrupt input type
   * @param <O> the interrupt output type (user response)
   * @param config the interrupt configuration
   * @return the interrupt as a tool
   */
  public <I, O> Tool<I, O> defineInterrupt(InterruptConfig<I, O> config) {
    Map<String, Object> inputSchema = config.getInputSchema();
    if (inputSchema == null) {
      inputSchema = new java.util.HashMap<>();
      inputSchema.put("type", "object");
    }

    Map<String, Object> outputSchema = config.getOutputSchema();
    if (outputSchema == null) {
      outputSchema = new java.util.HashMap<>();
      outputSchema.put("type", "object");
    }

    Tool<I, O> interruptTool =
        new Tool<>(
            config.getName(),
            config.getDescription() != null
                ? config.getDescription()
                : "Interrupt: " + config.getName(),
            inputSchema,
            outputSchema,
            config.getInputType(),
            (ctx, input) -> {
              // Build metadata from input - create a mutable copy since user may return
              // immutable map
              Map<String, Object> metadata = new java.util.HashMap<>();
              if (config.getRequestMetadata() != null) {
                metadata.putAll(config.getRequestMetadata().apply(input));
              }
              metadata.put("interrupt", true);
              metadata.put("interruptName", config.getName());
              metadata.put("input", input);

              // Throw interrupt exception - this never returns
              throw new ToolInterruptException(metadata);
            });

    // Register the interrupt tool
    registry.registerAction(ActionType.TOOL, interruptTool);
    return interruptTool;
  }

  // =========================================================================
  // Evaluation Methods
  // =========================================================================

  /**
   * Defines a new evaluator and registers it with the registry.
   *
   * <p>Evaluators assess the quality of AI outputs. They can be used to:
   *
   * <ul>
   *   <li>Score outputs based on various criteria (accuracy, relevance, etc.)
   *   <li>Compare outputs against reference data
   *   <li>Run automated quality checks in CI/CD pipelines
   * </ul>
   *
   * <p>Example usage:
   *
   * <pre>{@code
   * genkit.defineEvaluator(
   *     "myEvaluator",
   *     "My Evaluator",
   *     "Checks output quality",
   *     (dataPoint, options) -> {
   *       // Evaluate the output
   *       double score = calculateScore(dataPoint.getOutput());
   *       return EvalResponse.builder()
   *           .testCaseId(dataPoint.getTestCaseId())
   *           .evaluation(Score.builder().score(score).build())
   *           .build();
   *     });
   * }</pre>
   *
   * @param <O> the options type
   * @param name the evaluator name
   * @param displayName the display name shown in the UI
   * @param definition description of what the evaluator measures
   * @param evaluatorFn the evaluation function
   * @return the created evaluator
   */
  public <O> Evaluator<O> defineEvaluator(
      String name, String displayName, String definition, EvaluatorFn<O> evaluatorFn) {
    return Evaluator.define(registry, name, displayName, definition, evaluatorFn);
  }

  /**
   * Defines a new evaluator with full options.
   *
   * @param <O> the options type
   * @param name the evaluator name
   * @param displayName the display name shown in the UI
   * @param definition description of what the evaluator measures
   * @param isBilled whether using this evaluator incurs costs
   * @param optionsClass the class for evaluator-specific options
   * @param evaluatorFn the evaluation function
   * @return the created evaluator
   */
  public <O> Evaluator<O> defineEvaluator(
      String name,
      String displayName,
      String definition,
      boolean isBilled,
      Class<O> optionsClass,
      EvaluatorFn<O> evaluatorFn) {
    return Evaluator.define(
        registry, name, displayName, definition, isBilled, optionsClass, evaluatorFn);
  }

  /**
   * Gets an evaluator by name.
   *
   * @param name the evaluator name
   * @return the evaluator
   * @throws GenkitException if evaluator not found
   */
  @SuppressWarnings("unchecked")
  public Evaluator<?> getEvaluator(String name) {
    Action<?, ?, ?> action = registry.lookupAction(ActionType.EVALUATOR, name);
    if (action == null) {
      throw new GenkitException("Evaluator not found: " + name);
    }
    return (Evaluator<?>) action;
  }

  /**
   * Runs an evaluation using the specified request.
   *
   * <p>This method:
   *
   * <ol>
   *   <li>Loads the dataset
   *   <li>Runs inference on the target action
   *   <li>Executes all specified evaluators
   *   <li>Stores and returns the results
   * </ol>
   *
   * @param request the evaluation request
   * @return the evaluation run key
   * @throws Exception if evaluation fails
   */
  public EvalRunKey evaluate(RunEvaluationRequest request) throws Exception {
    return getEvaluationManager().runEvaluation(request);
  }

  /**
   * Gets the evaluation manager.
   *
   * @return the evaluation manager
   */
  public synchronized EvaluationManager getEvaluationManager() {
    if (evaluationManager == null) {
      evaluationManager = new EvaluationManager(registry);
    }
    return evaluationManager;
  }

  /**
   * Gets the dataset store.
   *
   * @return the dataset store
   */
  public DatasetStore getDatasetStore() {
    return getEvaluationManager().getDatasetStore();
  }

  /**
   * Gets the eval store.
   *
   * @return the eval store
   */
  public EvalStore getEvalStore() {
    return getEvaluationManager().getEvalStore();
  }

  /** Builder for Genkit. */
  public static class Builder {
    private final List<Plugin> plugins = new ArrayList<>();
    private final List<GenerationMiddleware> middlewares = new ArrayList<>();
    private final List<GenerationMiddlewareDesc> middlewareDescs = new ArrayList<>();
    private GenkitOptions options = GenkitOptions.builder().build();

    /**
     * Sets the Genkit options.
     *
     * @param options the options
     * @return this builder
     */
    public Builder options(GenkitOptions options) {
      this.options = options;
      return this;
    }

    /**
     * Adds a plugin.
     *
     * @param plugin the plugin to add
     * @return this builder
     */
    public Builder plugin(Plugin plugin) {
      this.plugins.add(plugin);
      return this;
    }

    /**
     * Optional: pre-registers one or more generation middlewares so they show up in the Genkit Dev
     * UI Middleware panel <em>before</em> any flow has executed. This is a UX convenience only —
     * middlewares are also auto-registered the first time they appear in a {@code
     * GenerateOptions.use(...)} call, so production code does not need to declare them here.
     *
     * @param middlewares the middlewares to pre-register
     * @return this builder
     */
    public Builder middleware(GenerationMiddleware... middlewares) {
      for (GenerationMiddleware mw : middlewares) {
        this.middlewares.add(mw);
      }
      return this;
    }

    /**
     * Optional: pre-registers one or more middleware <em>descriptors</em> so they show up in the
     * Genkit Dev UI Middleware panel — including a parameters form derived from each descriptor's
     * {@code configSchema}. Use this for parameterized middleware defined via {@link
     * com.google.genkit.ai.middleware.GenerationMiddlewares#define}. For middleware shared by a
     * plugin, prefer implementing {@link com.google.genkit.ai.middleware.MiddlewarePlugin} instead.
     *
     * @param descriptors the middleware descriptors to pre-register
     * @return this builder
     */
    public Builder middleware(GenerationMiddlewareDesc... descriptors) {
      for (GenerationMiddlewareDesc desc : descriptors) {
        this.middlewareDescs.add(desc);
      }
      return this;
    }

    /**
     * Enables dev mode.
     *
     * @return this builder
     */
    public Builder devMode() {
      this.options = GenkitOptions.builder().devMode(true).build();
      return this;
    }

    /**
     * Sets the reflection port.
     *
     * @param port the port number
     * @return this builder
     */
    public Builder reflectionPort(int port) {
      this.options =
          GenkitOptions.builder().devMode(options.isDevMode()).reflectionPort(port).build();
      return this;
    }

    /**
     * Builds the Genkit instance.
     *
     * @return the configured Genkit instance
     */
    public Genkit build() {
      Genkit genkit = new Genkit(options);
      genkit.plugins.addAll(plugins);
      genkit.init();
      // Register middleware shared by plugins (those implementing MiddlewarePlugin) so it shows up
      // in the Dev UI Middleware panel and is resolvable by name at generate time. Mirrors the JS
      // GenkitPluginV2.middleware() / Go MiddlewarePlugin.Middlewares() registration during init.
      genkit.registerPluginMiddlewares();
      // Pre-register any middleware declared directly via .middleware(...) so the Dev UI Middleware
      // panel can list them before any generate() call runs.
      genkit.registerMiddlewareForDevUi(middlewares);
      for (GenerationMiddlewareDesc desc : middlewareDescs) {
        genkit.registerMiddlewareDesc(desc);
      }
      return genkit;
    }
  }
}
