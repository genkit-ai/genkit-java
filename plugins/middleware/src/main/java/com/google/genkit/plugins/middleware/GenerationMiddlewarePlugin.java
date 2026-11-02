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

package com.google.genkit.plugins.middleware;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.genkit.ai.middleware.GenerationMiddleware;
import com.google.genkit.ai.middleware.GenerationMiddlewareDesc;
import com.google.genkit.ai.middleware.GenerationMiddlewares;
import com.google.genkit.ai.middleware.MiddlewarePlugin;
import com.google.genkit.core.Action;
import com.google.genkit.core.GenkitException;
import com.google.genkit.core.Plugin;
import com.google.genkit.core.Registry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A plugin that ships a set of ready-to-use, parameterized generation middleware.
 *
 * <p>This is the Java analog of the JS {@code @genkit-ai/middleware} package and the Go {@code
 * plugins/middleware} package. Adding it to the {@code Genkit} builder registers each middleware
 * into the {@code "middleware"} registry bucket, so it appears in the Genkit Dev UI Middleware
 * panel (with a parameters form derived from its {@code configSchema}) and can be attached to any
 * {@code generate()} call by name.
 *
 * <p>Provided middleware:
 *
 * <ul>
 *   <li>{@code retry} — retry failed model calls with exponential backoff ({@link RetryMiddleware})
 *   <li>{@code fallback} — fall back to alternate models on failure ({@link FallbackMiddleware})
 *   <li>{@code simulateSystemPrompt} — rewrite the system message into a user/model exchange
 *       ({@link SimulateSystemPromptMiddleware})
 *   <li>{@code genkit-middleware/toolApproval} — hold tool calls for approval by the caller or a
 *       judge model ({@link ToolApproval})
 *   <li>{@code genkit-middleware/softToolErrors} — return tool errors to the model ({@link
 *       SoftToolErrors})
 *   <li>{@code genkit-middleware/skills} — expose a local library of skills ({@link Skills})
 *   <li>{@code genkit-middleware/filesystem} — file access scoped to a directory ({@link
 *       Filesystem})
 *   <li>{@code genkit-middleware-exp/budget} — cap the usage a run or agent session may spend
 *       ({@link BudgetMiddleware}, experimental)
 *   <li>{@code genkit-middleware-exp/contextCompression} — keep a conversation inside a context
 *       budget ({@link ContextCompressionMiddleware}, experimental)
 * </ul>
 *
 * <p>Middleware registered under the {@value #EXPERIMENTAL_PREFIX} prefix is experimental and may
 * change in any minor release.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * Genkit genkit = Genkit.builder()
 *     .plugin(OpenAIPlugin.create())
 *     .plugin(GenerationMiddlewarePlugin.create())
 *     .build();
 *
 * // Attach by name (config optional) from code or the Dev UI:
 * genkit.generate(GenerateOptions.builder()
 *     .model("openai/gpt-4o-mini")
 *     .prompt("Hello")
 *     .build());
 * }</pre>
 */
public class GenerationMiddlewarePlugin implements Plugin, MiddlewarePlugin {

  /** The plugin name. */
  public static final String PLUGIN_NAME = "genkit-middleware";

  /** The name prefix of the experimental middleware this plugin registers. */
  public static final String EXPERIMENTAL_PREFIX = "genkit-middleware-exp/";

  /**
   * Creates the plugin.
   *
   * @return a new plugin instance
   */
  public static GenerationMiddlewarePlugin create() {
    return new GenerationMiddlewarePlugin();
  }

  @Override
  public String getName() {
    return PLUGIN_NAME;
  }

  @Override
  public List<Action<?, ?, ?>> init() {
    // This plugin provides middleware (via MiddlewarePlugin), not actions.
    return List.of();
  }

  @Override
  public List<GenerationMiddlewareDesc> middlewares(Registry registry) {
    return List.of(
        GenerationMiddlewares.define(
            "retry",
            "Retries failed model calls with exponential backoff.",
            RetryMiddleware.Options.class,
            RetryMiddleware::new),
        GenerationMiddlewares.define(
            "fallback",
            "Falls back to alternate models when the primary model fails.",
            FallbackMiddleware.Options.class,
            FallbackMiddleware::new),
        GenerationMiddlewares.define(
            "simulateSystemPrompt",
            "Rewrites the system message into a user/model exchange for models without native"
                + " system-prompt support.",
            SimulateSystemPromptMiddleware.Options.class,
            SimulateSystemPromptMiddleware::new),
        GenerationMiddlewares.define(
            ToolApproval.NAME,
            "Require approval, from the caller or a judge model, before executing tools",
            ToolApproval.Options.class,
            ToolApproval::new),
        GenerationMiddlewares.define(
            SoftToolErrors.NAME,
            "Return tool errors to the model instead of failing the generation",
            SoftToolErrors.Options.class,
            SoftToolErrors::new),
        GenerationMiddlewares.define(
            Skills.NAME,
            "Expose a local library of skills as loadable system instructions",
            Skills.Options.class,
            Skills::new),
        GenerationMiddlewares.define(
            Filesystem.NAME,
            "Grant the model file access scoped to a directory",
            Filesystem.Options.class,
            Filesystem::new),
        budgetDesc(),
        GenerationMiddlewares.define(
            ContextCompressionMiddleware.NAME,
            "Compress the conversation when it grows too large, by deduplicating and truncating"
                + " tool responses, summarizing, and dropping older messages.",
            ContextCompressionMiddleware.Options.class,
            ContextCompressionMiddleware::new));
  }

  /**
   * Describes {@link BudgetMiddleware}. The fields of its limit come from the usage type, which
   * carries no descriptions, so they are described here for the Dev UI form.
   */
  private static GenerationMiddlewareDesc budgetDesc() {
    GenerationMiddlewareDesc base =
        GenerationMiddlewares.define(
            BudgetMiddleware.NAME,
            "Cap the usage a generate run or agent session may spend.",
            BudgetMiddleware.Options.class,
            BudgetMiddleware::new);
    Map<String, Object> schema = describeLimit(base.configSchema());
    return new GenerationMiddlewareDesc() {
      @Override
      public String name() {
        return base.name();
      }

      @Override
      public String description() {
        return base.description();
      }

      @Override
      public Map<String, Object> configSchema() {
        return schema;
      }

      @Override
      public GenerationMiddleware instantiate(JsonNode config) throws GenkitException {
        return base.instantiate(config);
      }
    };
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> describeLimit(Map<String, Object> schema) {
    if (schema == null) {
      return null;
    }
    Map<String, Object> copy = deepCopy(schema);
    Object props = copy.get("properties");
    if (!(props instanceof Map)) {
      return copy;
    }
    Object limit = ((Map<String, Object>) props).get("limit");
    if (!(limit instanceof Map)) {
      return copy;
    }
    Map<String, Object> limitSchema = (Map<String, Object>) limit;
    Object ref = limitSchema.get("$ref");
    if (ref instanceof String r && r.startsWith("#/")) {
      Object target = copy;
      for (String seg : r.substring(2).split("/")) {
        target = target instanceof Map ? ((Map<String, Object>) target).get(seg) : null;
      }
      if (target instanceof Map) {
        limitSchema = (Map<String, Object>) target;
      }
    }
    Object limitProps = limitSchema.get("properties");
    if (!(limitProps instanceof Map)) {
      return copy;
    }
    for (Map.Entry<String, Object> e : ((Map<String, Object>) limitProps).entrySet()) {
      if (!(e.getValue() instanceof Map)) {
        continue;
      }
      Map<String, Object> field = (Map<String, Object>) e.getValue();
      if (field.get("description") != null) {
        continue;
      }
      field.put(
          "description",
          e.getKey().equals("custom")
              ? "Caps on provider-specific usage, such as cost, key by key."
              : "Cap on " + e.getKey() + ". Zero leaves it uncapped.");
    }
    return copy;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> deepCopy(Map<String, Object> m) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (Map.Entry<String, Object> e : m.entrySet()) {
      Object v = e.getValue();
      if (v instanceof Map) {
        v = deepCopy((Map<String, Object>) v);
      } else if (v instanceof List) {
        List<Object> list = new ArrayList<>();
        for (Object o : (List<Object>) v) {
          list.add(o instanceof Map ? deepCopy((Map<String, Object>) o) : o);
        }
        v = list;
      }
      out.put(e.getKey(), v);
    }
    return out;
  }
}
