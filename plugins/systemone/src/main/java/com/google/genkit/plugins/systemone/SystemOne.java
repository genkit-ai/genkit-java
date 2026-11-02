/*
 * Copyright 2026 Google LLC
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

package com.google.genkit.plugins.systemone;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.genkit.ai.ModelResponse;
import com.google.genkit.core.Action;
import com.google.genkit.core.ActionDesc;
import com.google.genkit.core.ActionType;
import com.google.genkit.core.DynamicPlugin;
import com.google.genkit.core.GenkitException;
import com.google.genkit.core.JsonUtils;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Function;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Serves decision models: models that speak System One, the protocol TypeSafe AI introduced with
 * jev and that Liquid AI's d1, Cloudflare's Clef, and the gateways serving them also speak. A
 * decision model does not generate text. It evaluates a state against typed questions and returns
 * one typed answer per question, with calibrated probabilities.
 *
 * <p>The questions are the fields of the output class, declared with {@link Choice}, {@link Score},
 * {@link Noul}, and {@link NoulOf}, so a decision is one typed generate call:
 *
 * <pre>{@code
 * Genkit genkit = Genkit.builder().plugin(SystemOne.typeSafe()).build(); // TYPESAFE_API_KEY
 *
 * Triage out = genkit.generateObject(
 *     "typesafe/jev-1.13.0", ticket, Triage.class);
 * if (out.department().confidence() < 0.6) {
 *   // route to a human
 * }
 * }</pre>
 *
 * <p>This plugin serves any server that speaks the protocol, under {@link #provider(String)}, by
 * the IDs the server uses: a model the server calls d1 is {@code provider/d1}, and one a gateway
 * calls liquid/d1 is {@code provider/liquid/d1}. Any ID resolves, so a model the server adds later
 * works without a change here. {@link #typeSafe()}, {@link #openRouter()}, {@link #ollama()}, and
 * {@link #workersAI(String)} set it up for the known servers; their settings can still be changed
 * before Genkit is built. The settings are read once, when Genkit initializes the plugin.
 *
 * <p>Preview: this plugin's API may change in any minor release.
 */
public final class SystemOne implements DynamicPlugin {

  private static final Logger logger = LoggerFactory.getLogger(SystemOne.class);

  /** Bounds a model listing, which serves the Dev UI and must not hold it up. */
  static final Duration LISTING_TIMEOUT = Duration.ofSeconds(5);

  /** How long a listing is kept. */
  static final Duration LISTING_TTL = Duration.ofMinutes(5);

  /** How long until a failed listing is tried again. */
  static final Duration LISTING_RETRY = Duration.ofMinutes(1);

  // ---------------------------------------------------------------------------------------------
  // Question types.

  /**
   * The option set of a {@link Choice}: an enum whose constants are the options, each with what it
   * means. The criteria are what the model chooses among, so they belong to the type rather than to
   * any one call. Options known only at run time are asked with a {@link ChoiceQuestion} instead.
   *
   * <pre>{@code
   * enum Dept implements SystemOne.Option {
   *   @JsonProperty("billing") BILLING("Payments, invoicing, refunds"),
   *   @JsonProperty("technical") TECHNICAL("Bugs, outages, integrations"),
   *   @JsonProperty("other") OTHER("None of the above");
   *
   *   private final String criteria;
   *   Dept(String criteria) { this.criteria = criteria; }
   *   public String criteria() { return criteria; }
   * }
   * }</pre>
   */
  public interface Option {

    /**
     * Returns what this option means.
     *
     * @return the criteria
     */
    String criteria();

    /**
     * Returns the structured form of the criteria the wire format also takes, such as an object
     * with {@code not_for} and {@code examples}, or null for none. The criteria string stays
     * required, and the schema and the answers show it; guidance replaces it on the wire only. A
     * value that encodes as a JSON object with no {@code what} gets the criteria as its {@code
     * what}, so guidance adds to the description; any other value is sent as it is.
     *
     * @return the guidance, or null
     */
    default Object guidance() {
      return null;
    }
  }

  /**
   * The rubric of a {@link Score}: an enum whose constants are the levels, lowest first. A level's
   * index is its number on the wire, so a three-level rubric scores 0 to 2.
   */
  public interface Level {

    /**
     * Returns what this level means.
     *
     * @return the description
     */
    String description();

    /**
     * Returns the structured form of the description, such as an object with {@code summary} and
     * {@code signals}, or null for none. {@link Score#legend()} and {@link Score#label()} keep the
     * description string.
     *
     * @return the guidance, or null
     */
    default Object guidance() {
      return null;
    }
  }

  /**
   * What yes and no mean for a {@link NoulOf} question, as the criteria of a {@link Choice} and the
   * levels of a {@link Score} come from their types. Both sides are given: the API takes the pair
   * or nothing. An implementation needs a no-argument constructor.
   *
   * <pre>{@code
   * public static final class Urgent implements SystemOne.YesNo {
   *   public String yes() { return "Names a deadline, or says now or today"; }
   *   public String no() { return "No time pressure is expressed"; }
   * }
   * }</pre>
   */
  public interface YesNo {

    /**
     * Returns what yes means.
     *
     * @return the yes criteria, or the empty string for none
     */
    String yes();

    /**
     * Returns what no means.
     *
     * @return the no criteria, or the empty string for none
     */
    String no();

    /**
     * Returns the structured form of what yes means, or null. Each side needs a string or guidance,
     * or neither does.
     *
     * @return the yes guidance, or null
     */
    default Object yesGuidance() {
      return null;
    }

    /**
     * Returns the structured form of what no means, or null.
     *
     * @return the no guidance, or null
     */
    default Object noGuidance() {
      return null;
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Server settings.

  /** Whether models read one kind of media. */
  public enum MediaSupport {
    /**
     * Keeps the setting it overrides: for a model, the plugin's; for the plugin, refusing the
     * media.
     */
    DEFAULT,
    /** Sends the media, and leaves a model that cannot read it to the server to refuse. */
    SUPPORTED,
    /** Refuses a request with the media before it is sent. */
    UNSUPPORTED
  }

  /**
   * Describes one decision model.
   *
   * @param label the name in the Dev UI, or null for one built from the ID
   * @param images whether the model reads images, overriding the plugin's setting
   * @param audio whether the model reads audio, overriding the plugin's setting
   * @param video whether the model reads video, overriding the plugin's setting
   */
  public record ModelSpec(
      String label, MediaSupport images, MediaSupport audio, MediaSupport video) {

    /** A model with no description: the plugin's settings apply. */
    public static final ModelSpec DEFAULT = new ModelSpec(null, null, null, null);

    /**
     * Creates a model description, with a null media setting meaning {@link MediaSupport#DEFAULT}.
     *
     * @param label the name in the Dev UI
     * @param images whether the model reads images
     * @param audio whether the model reads audio
     * @param video whether the model reads video
     */
    public ModelSpec {
      images = images == null ? MediaSupport.DEFAULT : images;
      audio = audio == null ? MediaSupport.DEFAULT : audio;
      video = video == null ? MediaSupport.DEFAULT : video;
    }

    /**
     * Returns a description with only a label, which changes nothing else.
     *
     * @param label the name in the Dev UI
     * @return the description
     */
    public static ModelSpec labeled(String label) {
      return new ModelSpec(label, null, null, null);
    }

    /**
     * Returns a copy with the image setting.
     *
     * @param images whether the model reads images
     * @return the description
     */
    public ModelSpec withImages(MediaSupport images) {
      return new ModelSpec(label, images, audio, video);
    }

    /**
     * Returns a copy with the audio setting.
     *
     * @param audio whether the model reads audio
     * @return the description
     */
    public ModelSpec withAudio(MediaSupport audio) {
      return new ModelSpec(label, images, audio, video);
    }

    /**
     * Returns a copy with the video setting.
     *
     * @param video whether the model reads video
     * @return the description
     */
    public ModelSpec withVideo(MediaSupport video) {
      return new ModelSpec(label, images, audio, video);
    }
  }

  /**
   * Builds the request a server takes when its wire is not the native one. It gets the model ID and
   * the native body, which it may change, and returns the path to append to {@link #path(String)},
   * joined with a slash when it does not start with one, and the body to send.
   */
  @FunctionalInterface
  public interface Route {

    /**
     * Routes one request.
     *
     * @param model the model ID
     * @param body the native body, which may be changed
     * @return the path suffix and the body to send
     * @throws Exception when the request cannot be routed
     */
    Routed route(String model, Map<String, Object> body) throws Exception;
  }

  /**
   * A routed request: the path to append, and the body to send.
   *
   * @param path the path to append, or the empty string
   * @param body the body to send
   */
  public record Routed(String path, Object body) {}

  /**
   * Returns the native response from the body a server that wraps its responses sent, such as by
   * taking it out of an envelope. An exception it throws is reported under the plugin's name, as
   * UNKNOWN unless it is a {@link GenkitException} with a status.
   */
  @FunctionalInterface
  public interface Unwrap {

    /**
     * Unwraps one response.
     *
     * @param body the body the server sent
     * @return the native response body
     * @throws Exception when the body is not a response
     */
    String unwrap(String body) throws Exception;
  }

  /** What a constructor sets for its server beyond the public settings. */
  private record Preset(
      String label,
      String apiKeyEnv,
      String baseUrl,
      String baseUrlEnv,
      String account,
      String accountEnv) {
    static final Preset NONE = new Preset(null, null, null, null, null, null);
  }

  private String provider;
  private String baseUrl;
  private String path;
  private String modelsPath;
  private String apiKey;
  private final Map<String, ModelSpec> models = new LinkedHashMap<>();
  private MediaSupport images = MediaSupport.DEFAULT;
  private MediaSupport audio = MediaSupport.DEFAULT;
  private MediaSupport video = MediaSupport.DEFAULT;
  private OkHttpClient httpClient;
  private final Map<String, List<String>> headers = new LinkedHashMap<>();
  private Route route;
  private Unwrap unwrap;
  private Preset preset = Preset.NONE;

  /** Reads an environment variable; replaced in tests. */
  Function<String, String> env = System::getenv;

  // State read by init.
  private Client client;
  private Map<String, ModelSpec> initModels;
  private MediaSupport[] media;

  // The last listing and when it goes stale.
  private final Object listLock = new Object();
  private List<ActionDesc> listed;
  private Instant listedUntil = Instant.MIN;

  /**
   * Creates a plugin for a server with no constructor of its own. Set at least {@link
   * #provider(String)} and {@link #baseUrl(String)}.
   *
   * <pre>{@code
   * SystemOne liquid = new SystemOne()
   *     .provider("liquid")
   *     .baseUrl("https://api.liquid.ai/decisions")
   *     .modelsPath("/v1/models")
   *     .apiKey(System.getenv("LIQUID_API_KEY"))
   *     .images(SystemOne.MediaSupport.SUPPORTED);
   * }</pre>
   */
  public SystemOne() {}

  // ---------------------------------------------------------------------------------------------
  // Constructors for the known servers.

  /**
   * TypeSafe AI's own API, which serves jev, the first System One model. Its models are {@code
   * typesafe/<id>}, such as {@code typesafe/jev-1.13.0}, and read text and JSON only. The API key
   * comes from {@code TYPESAFE_API_KEY} and the base URL from {@code TYPESAFE_BASE_URL}. Pin a
   * version in production: confidence thresholds tuned against one release do not carry over to the
   * next.
   *
   * @return the plugin
   */
  public static SystemOne typeSafe() {
    SystemOne s = new SystemOne().provider("typesafe").modelsPath("/v1/models");
    s.models.put("jev-latest", ModelSpec.DEFAULT);
    s.models.put("jev-1.13.0", ModelSpec.DEFAULT);
    s.preset =
        new Preset(
            "TypeSafe",
            "TYPESAFE_API_KEY",
            "https://api.typesafe.ai",
            "TYPESAFE_BASE_URL",
            null,
            null);
    return s;
  }

  /**
   * OpenRouter's Decisions API, which serves several vendors' decision models under one key. Its
   * models are {@code openrouter-decisions/<ID>} by OpenRouter's ID, such as {@code
   * openrouter-decisions/liquid/d1}. The name is not {@code openrouter}, which a plugin for
   * OpenRouter's chat models may have, so both serve one app. The API key comes from {@code
   * OPENROUTER_API_KEY}. The Dev UI lists the models OpenRouter lists as decision models, and the
   * best known of them when the listing fails, and a request's cost is reported in the response's
   * usage under {@code custom}.
   *
   * <p>Its models are text only here: the API can route a request to a provider that drops media
   * and answers without it, so a request with media is refused before it is sent.
   *
   * @return the plugin
   */
  public static SystemOne openRouter() {
    SystemOne s =
        new SystemOne()
            .provider("openrouter-decisions")
            .path("/api/alpha/decisions")
            .modelsPath("/api/v1/models?output_modalities=decisions");
    for (String id :
        List.of(
            "cloudflare/clef",
            "cloudflare/clef-flash",
            "liquid/d1",
            "typesafe/jev-1.13",
            "~typesafe/jev-latest")) {
      s.models.put(id, ModelSpec.DEFAULT);
    }
    s.preset =
        new Preset("OpenRouter", "OPENROUTER_API_KEY", "https://openrouter.ai", null, null, null);
    return s;
  }

  /**
   * A local Ollama server, which serves the decision models it has pulled, such as Clef, and takes
   * no key. Its models are {@code ollama-decisions/<name>}, such as {@code ollama-decisions/clef}.
   * The server is {@code http://localhost:11434} unless {@link #baseUrl(String)} says otherwise.
   * The Dev UI lists Clef, and {@link #model(String, ModelSpec)} adds the other models pulled.
   *
   * <p>Ollama takes images as raw base64 rather than data URLs, which this plugin converts, and
   * requires text in the state, so a request with no text, such as one of images alone, is refused
   * before it is sent.
   *
   * @return the plugin
   */
  public static SystemOne ollama() {
    SystemOne s =
        new SystemOne()
            .provider("ollama-decisions")
            .route(SystemOne::ollamaRoute)
            .images(MediaSupport.SUPPORTED);
    s.models.put("clef", ModelSpec.DEFAULT);
    s.preset = new Preset("Ollama", null, "http://localhost:11434", null, null, null);
    return s;
  }

  /**
   * Cloudflare Workers AI for one account. It serves Cloudflare's own decision models, Clef and
   * Clef-flash, which read images, and Clef-omni, which reads images, audio, and video, and
   * partners' models such as jev. Its models are {@code cloudflare-decisions/<ID>} by the Workers
   * AI ID, such as {@code cloudflare-decisions/@cf/cloudflare/clef}. The API token comes from
   * {@code CLOUDFLARE_API_TOKEN}, and the account ID from {@code CLOUDFLARE_ACCOUNT_ID} when {@code
   * accountId} is null or empty. A base URL with no {@code {account}} segment, such as a proxy's,
   * needs no account ID.
   *
   * <p>Workers AI names one model per release, with no versions, so a threshold tuned against a
   * model can move when Cloudflare updates it.
   *
   * @param accountId the Cloudflare account ID, or null to read it from the environment
   * @return the plugin
   */
  public static SystemOne workersAI(String accountId) {
    SystemOne s =
        new SystemOne()
            .provider("cloudflare-decisions")
            .path("/ai/run")
            .route(SystemOne::workersAIRoute)
            .unwrap(SystemOne::unwrapWorkersAI)
            .images(MediaSupport.SUPPORTED);
    s.models.put("@cf/cloudflare/clef", ModelSpec.DEFAULT);
    s.models.put("@cf/cloudflare/clef-flash", ModelSpec.DEFAULT);
    s.models.put(
        "@cf/cloudflare/clef-omni",
        ModelSpec.DEFAULT.withAudio(MediaSupport.SUPPORTED).withVideo(MediaSupport.SUPPORTED));
    s.models.put("typesafe/jev", ModelSpec.DEFAULT.withImages(MediaSupport.UNSUPPORTED));
    s.preset =
        new Preset(
            "Workers AI",
            "CLOUDFLARE_API_TOKEN",
            "https://api.cloudflare.com/client/v4/accounts/{account}",
            null,
            accountId,
            "CLOUDFLARE_ACCOUNT_ID");
    return s;
  }

  // ---------------------------------------------------------------------------------------------
  // Settings.

  /**
   * Names the plugin and prefixes its model names. It is required, and must differ from every other
   * plugin's name.
   *
   * @param provider the provider name
   * @return this plugin
   */
  public SystemOne provider(String provider) {
    this.provider = provider;
    return this;
  }

  /**
   * Sets the server's root, which the path and the models path are joined to. It is required,
   * unless a constructor gives a default.
   *
   * @param baseUrl the base URL
   * @return this plugin
   */
  public SystemOne baseUrl(String baseUrl) {
    this.baseUrl = baseUrl;
    return this;
  }

  /**
   * Sets the request path. Unset means {@code /v1/systemone}, and {@code "/"} means the base URL
   * itself, for a base URL that is the whole endpoint.
   *
   * @param path the request path
   * @return this plugin
   */
  public SystemOne path(String path) {
    this.path = path;
    return this;
  }

  /**
   * Sets the path of the server's model listing. When unset, no listing is asked for, and the Dev
   * UI shows the models described with {@link #model(String, ModelSpec)}.
   *
   * @param modelsPath the models path
   * @return this plugin
   */
  public SystemOne modelsPath(String modelsPath) {
    this.modelsPath = modelsPath;
    return this;
  }

  /**
   * Sets the API key, sent as a bearer token. A constructor reads it from the environment variable
   * it names when this is unset; a server that takes no key is reached with none.
   *
   * @param apiKey the API key
   * @return this plugin
   */
  public SystemOne apiKey(String apiKey) {
    this.apiKey = apiKey;
    return this;
  }

  /**
   * Describes a model known ahead, keyed by the ID the server uses or by the full model name. It is
   * listed in the Dev UI whether or not the server has a listing. Any other ID resolves too, as a
   * model with no description.
   *
   * @param id the server's ID or the full model name
   * @param spec the description
   * @return this plugin
   */
  public SystemOne model(String id, ModelSpec spec) {
    models.put(id, spec == null ? ModelSpec.DEFAULT : spec);
    return this;
  }

  /**
   * Replaces the models known ahead.
   *
   * @param models the descriptions, keyed by the server's ID or the full model name
   * @return this plugin
   */
  public SystemOne models(Map<String, ModelSpec> models) {
    this.models.clear();
    if (models != null) {
      models.forEach(this::model);
    }
    return this;
  }

  /**
   * Sets whether the models read images. The default refuses a request with images before it is
   * sent, since media is an extension to the protocol, and a server that does not know a field can
   * drop it and answer without the media. A model's {@link ModelSpec} overrides it.
   *
   * @param images whether the models read images
   * @return this plugin
   */
  public SystemOne images(MediaSupport images) {
    this.images = images == null ? MediaSupport.DEFAULT : images;
    return this;
  }

  /**
   * Sets whether the models read audio, as {@link #images(MediaSupport)} does for images.
   *
   * @param audio whether the models read audio
   * @return this plugin
   */
  public SystemOne audio(MediaSupport audio) {
    this.audio = audio == null ? MediaSupport.DEFAULT : audio;
    return this;
  }

  /**
   * Sets whether the models read video, as {@link #images(MediaSupport)} does for images.
   *
   * @param video whether the models read video
   * @return this plugin
   */
  public SystemOne video(MediaSupport video) {
    this.video = video == null ? MediaSupport.DEFAULT : video;
    return this;
  }

  /**
   * Sets the HTTP client that sends the requests: the escape hatch to transport settings such as
   * timeouts, proxies, and interceptors. When unset, a client with a 30-second timeout is used.
   *
   * @param httpClient the HTTP client
   * @return this plugin
   */
  public SystemOne httpClient(OkHttpClient httpClient) {
    this.httpClient = httpClient;
    return this;
  }

  /**
   * Adds a header sent on every request, after the authorization header.
   *
   * @param name the header name
   * @param value the header value
   * @return this plugin
   */
  public SystemOne header(String name, String value) {
    headers.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
    return this;
  }

  /**
   * Sets the headers sent on every request, after the authorization header.
   *
   * @param headers the headers
   * @return this plugin
   */
  public SystemOne headers(Map<String, String> headers) {
    this.headers.clear();
    if (headers != null) {
      headers.forEach(this::header);
    }
    return this;
  }

  /**
   * Sets how a request is built for a server whose wire is not the native one. When unset, the
   * native body goes to the path.
   *
   * @param route the route
   * @return this plugin
   */
  public SystemOne route(Route route) {
    this.route = route;
    return this;
  }

  /**
   * Sets how the native response is read from a server that wraps its responses. When unset, the
   * body is read as the native response.
   *
   * @param unwrap the unwrap hook
   * @return this plugin
   */
  public SystemOne unwrap(Unwrap unwrap) {
    this.unwrap = unwrap;
    return this;
  }

  /** Replaces the environment lookup, for tests. */
  SystemOne env(Function<String, String> env) {
    this.env = env;
    return this;
  }

  // ---------------------------------------------------------------------------------------------
  // Plugin.

  @Override
  public String getName() {
    synchronized (this) {
      return client != null ? client.endpoint.name() : provider;
    }
  }

  @Override
  public List<Action<?, ?, ?>> init() {
    initialize();
    return List.of();
  }

  /**
   * Builds the client, and fails without a provider name or a base URL, or without a key or an
   * account ID for a server that requires one, since every request would fail, and when the models
   * name one model twice. No actions are registered up front: models resolve by name.
   */
  private synchronized void initialize() {
    if (provider == null || provider.isEmpty()) {
      throw new IllegalStateException(
          "systemone: set provider, the plugin's name and the prefix of its models");
    }
    if (client != null) {
      throw new IllegalStateException(provider + ": plugin already initialized");
    }
    String key = apiKey;
    if (preset.apiKeyEnv() != null) {
      if (key == null || key.isEmpty()) {
        key = env.apply(preset.apiKeyEnv());
      }
      if (key == null || key.isEmpty()) {
        throw new IllegalStateException(
            provider + ": set apiKey or the " + preset.apiKeyEnv() + " environment variable");
      }
    }
    String base = orEmpty(baseUrl).isEmpty() ? orEmpty(preset.baseUrl()) : baseUrl;
    if (preset.baseUrlEnv() != null && orEmpty(baseUrl).isEmpty()) {
      String fromEnv = env.apply(preset.baseUrlEnv());
      if (fromEnv != null && !fromEnv.isEmpty()) {
        base = fromEnv;
      }
    }
    if (base.isEmpty()) {
      throw new IllegalStateException(
          provider + ": set baseUrl, the root of the server's System One API");
    }
    if (preset.accountEnv() != null && base.contains("{account}")) {
      String account = orEmpty(preset.account());
      if (account.isEmpty()) {
        account = orEmpty(env.apply(preset.accountEnv()));
      }
      if (account.isEmpty()) {
        throw new IllegalStateException(
            provider
                + ": pass an account ID or set the "
                + preset.accountEnv()
                + " environment variable");
      }
      base = base.replace("{account}", pathEscape(account));
    }
    String requestPath = rooted(path == null || path.isEmpty() ? "/v1/systemone" : path);
    if (requestPath.equals("/")) {
      requestPath = "";
    }
    media = new MediaSupport[] {images, audio, video};
    Map<String, ModelSpec> byId = new LinkedHashMap<>();
    for (Map.Entry<String, ModelSpec> e : models.entrySet()) {
      String id = trimProvider(provider, e.getKey());
      if (byId.containsKey(id)) {
        throw new IllegalStateException(
            provider + ": models names " + id + " twice, by its ID and by its full name");
      }
      byId.put(id, e.getValue());
    }
    initModels = byId;
    OkHttpClient http =
        httpClient != null
            ? httpClient
            : new OkHttpClient.Builder()
                .callTimeout(Client.REQUEST_TIMEOUT)
                .retryOnConnectionFailure(false)
                .build();
    Map<String, List<String>> headerCopy = new LinkedHashMap<>();
    headers.forEach((k, v) -> headerCopy.put(k, List.copyOf(v)));
    client =
        new Client(
            http,
            base.endsWith("/") ? base.substring(0, base.length() - 1) : base,
            key,
            headerCopy,
            new Client.Endpoint(provider, requestPath, rooted(modelsPath), route, unwrap));
  }

  /** Gives a non-empty path the leading slash that joins it to the base URL. */
  private static String rooted(String path) {
    if (path == null || path.isEmpty() || path.startsWith("/")) {
      return path == null ? "" : path;
    }
    return "/" + path;
  }

  private static String orEmpty(String s) {
    return s == null ? "" : s;
  }

  private static String trimProvider(String provider, String id) {
    String prefix = provider + "/";
    return id.startsWith(prefix) ? id.substring(prefix.length()) : id;
  }

  private synchronized Client initialized() {
    return client;
  }

  /**
   * Lists the models described ahead together with the ones the server's listing names. A listing
   * that fails or takes too long leaves the models described ahead, so the Dev UI still shows them
   * while offline. A listing is kept for five minutes, and a failed one is not tried again for a
   * minute.
   *
   * @return the model descriptors
   */
  @Override
  public List<ActionDesc> listActions() {
    Client c = initialized();
    if (c == null) {
      return List.of();
    }
    synchronized (listLock) {
      if (listed != null && Instant.now().isBefore(listedUntil)) {
        return new ArrayList<>(listed);
      }
      List<String> fromServer = new ArrayList<>();
      Duration fresh = listing(c, fromServer);
      TreeSet<String> ids = new TreeSet<>(initModels.keySet());
      ids.addAll(fromServer);
      List<ActionDesc> actions = new ArrayList<>(ids.size());
      for (String id : ids) {
        actions.add(newModel(c, id).getDesc());
      }
      listed = actions;
      listedUntil = Instant.now().plus(fresh);
      return new ArrayList<>(actions);
    }
  }

  /** Fills the IDs the server's listing names, and returns how long they stay fresh. */
  private Duration listing(Client c, List<String> ids) {
    if (c.endpoint.modelsPath() == null || c.endpoint.modelsPath().isEmpty()) {
      return LISTING_TTL;
    }
    try {
      OkHttpClient listingHttp = c.http.newBuilder().callTimeout(LISTING_TIMEOUT).build();
      for (Client.ListedModel m : c.listModels(listingHttp)) {
        if (!m.model().isEmpty()) {
          ids.add(m.model());
        }
      }
      return LISTING_TTL;
    } catch (RuntimeException e) {
      logger.debug(
          "{}: model listing failed, advertising the configured models: {}",
          c.endpoint.name(),
          e.getMessage());
      ids.clear();
      return LISTING_RETRY;
    }
  }

  /**
   * Resolves a model by its full name. Models are the only action type served. The ID is forwarded
   * as given; whether it exists is for the server to say when the first request is made.
   *
   * @param type the action type
   * @param name the full model name, {@code provider/id}
   * @return the model, or null for any other name
   */
  @Override
  public Action<?, ?, ?> resolveAction(ActionType type, String name) {
    if (type != ActionType.MODEL || name == null) {
      return null;
    }
    Client c = initialized();
    if (c == null) {
      return null;
    }
    String prefix = c.endpoint.name() + "/";
    if (!name.startsWith(prefix) || name.length() == prefix.length()) {
      return null;
    }
    return newModel(c, name.substring(prefix.length()));
  }

  /**
   * Returns the full name of one of the plugin's models, for the places that take a name, such as a
   * fallback list. The ID is the server's or the full model name: {@code modelName("jev-1.13.0")}
   * and {@code modelName("typesafe/jev-1.13.0")} on {@link #typeSafe()} are both {@code
   * typesafe/jev-1.13.0}.
   *
   * @param id the server's ID or the full model name
   * @return the full model name
   */
  public String modelName(String id) {
    String p = getName();
    return p + "/" + trimProvider(p, id);
  }

  /** Builds the model action for one ID. */
  private DecisionModel newModel(Client c, String id) {
    ModelSpec spec = initModels.getOrDefault(id, ModelSpec.DEFAULT);
    String label = spec.label();
    if (label == null || label.isEmpty()) {
      String p = preset.label() != null ? preset.label() : c.endpoint.name();
      label = p.isEmpty() ? id : p + " - " + id;
    }
    StateBuilder.Reads reads =
        new StateBuilder.Reads(
            reads(media[0], spec.images()),
            reads(media[1], spec.audio()),
            reads(media[2], spec.video()));
    return new DecisionModel(c, c.endpoint.name() + "/" + id, id, label, reads);
  }

  private static boolean reads(MediaSupport plugin, MediaSupport model) {
    MediaSupport effective = model != MediaSupport.DEFAULT ? model : plugin;
    return effective == MediaSupport.SUPPORTED;
  }

  // ---------------------------------------------------------------------------------------------
  // Runtime questions and response helpers.

  /**
   * Returns the output schema that asks the given questions, each answered under its key. Pass it
   * with {@code OutputConfig.json(...)} and read the answers with {@link #answers(ModelResponse)}:
   *
   * <pre>{@code
   * List<ChoiceOption> options = tools.stream()
   *     .map(t -> ChoiceOption.of(t.name(), t.description()))
   *     .toList();
   * ModelResponse resp = genkit.generate(GenerateOptions.builder()
   *     .model("typesafe/jev-1.13.0")
   *     .output(OutputConfig.json(SystemOne.schema(Map.of(
   *         "tool", new ChoiceQuestion("Which tool serves the request?", options)))))
   *     .prompt(request)
   *     .build());
   * String tool = SystemOne.answers(resp).get("tool").choice();
   * }</pre>
   *
   * @param questions the questions, keyed by the ID their answers come back under
   * @return the output schema
   */
  public static Map<String, Object> schema(Map<String, ? extends Question> questions) {
    List<String> ids = new ArrayList<>(new TreeSet<>(questions.keySet()));
    Map<String, Object> props = new LinkedHashMap<>();
    for (String id : ids) {
      Question q = questions.get(id);
      // A null question is left unmarked, so the request is refused as asking nothing.
      props.put(id, q != null ? QuestionSchemas.schema(q) : new LinkedHashMap<>());
    }
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "object");
    schema.put("properties", props);
    schema.put("required", ids);
    schema.put("additionalProperties", false);
    return schema;
  }

  /**
   * Reads the answers to a {@link #schema(Map)} from a response, keyed by question ID.
   *
   * @param response the decision model's response
   * @return the answers
   * @throws GenkitException if the response's text is not a set of answers
   */
  public static Map<String, Answer> answers(ModelResponse response) throws GenkitException {
    String text = response == null ? null : response.getText();
    if (text == null || text.isEmpty()) {
      throw Wire.error("INVALID_ARGUMENT", "systemone: the response carries no answers");
    }
    try {
      return Collections.unmodifiableMap(
          JsonUtils.getObjectMapper()
              .readValue(text, new TypeReference<LinkedHashMap<String, Answer>>() {}));
    } catch (JsonProcessingException e) {
      throw Wire.error(
          "INVALID_ARGUMENT",
          "systemone: the response is not a set of answers: " + e.getOriginalMessage(),
          e);
    }
  }

  /**
   * Reads the {@link Info} of a decision model's response, from the model or from JSON that carried
   * one, such as a flow's output or a stored trace. It is {@link Info#EMPTY} for a response from
   * any other model.
   *
   * @param response the response
   * @return the info
   */
  public static Info responseInfo(ModelResponse response) {
    if (response == null || response.getCustom() == null) {
      return Info.EMPTY;
    }
    try {
      Info info = JsonUtils.getObjectMapper().convertValue(response.getCustom(), Info.class);
      return info == null ? Info.EMPTY : info;
    } catch (IllegalArgumentException e) {
      return Info.EMPTY;
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Server wires.

  /** Sends the native body with the images as raw base64, and refuses a state with no text. */
  @SuppressWarnings("unchecked")
  static Routed ollamaRoute(String model, Map<String, Object> body) {
    List<String> urls =
        body.get("images") instanceof List<?> l ? (List<String>) l : Collections.emptyList();
    if (!hasText(body.get("state"))) {
      String msg = "systemone: Ollama requires text in the state";
      if (!urls.isEmpty()) {
        msg += ", so a request with images needs text too";
      }
      throw Wire.error("INVALID_ARGUMENT", msg);
    }
    if (!urls.isEmpty()) {
      List<String> raw = new ArrayList<>(urls.size());
      for (String image : urls) {
        int at = image.indexOf(";base64,");
        raw.add(at < 0 ? "" : image.substring(at + ";base64,".length()));
      }
      body.put("images", raw);
    }
    return new Routed("", body);
  }

  /**
   * Whether a state carries any text: a nonempty string, a turn or document with text, or any other
   * value, such as the object of a data part.
   */
  static boolean hasText(Object state) {
    if (state == null) {
      return false;
    }
    if (state instanceof String s) {
      return !s.isEmpty();
    }
    if (state instanceof StateBuilder.Turns turns) {
      return turns.stream().anyMatch(turn -> hasText(turn.get("content")));
    }
    if (state instanceof StateBuilder.WithContext ctx) {
      List<?> docs = ctx.get("context") instanceof List<?> l ? l : List.of();
      return hasText(ctx.get("messages")) || docs.stream().anyMatch(SystemOne::hasText);
    }
    if (state instanceof Map<?, ?> map) {
      return !map.isEmpty();
    }
    return true;
  }

  /**
   * Builds a request the way Workers AI runs the model. Its own models, under {@code @cf/}, run at
   * {@code /ai/run/<ID>} with the native body, which names the model by its last segment. A
   * partner's model runs at {@code /ai/run} with the native body under input and the model beside
   * it. A model ID goes into the path one escaped segment at a time, and one with an empty or dot
   * segment is refused, so no ID can change the query or reach another path of the account's API.
   */
  static Routed workersAIRoute(String model, Map<String, Object> body) {
    if (!model.startsWith("@cf/")) {
      body.remove("model");
      Map<String, Object> wrapped = new LinkedHashMap<>();
      wrapped.put("model", model);
      wrapped.put("input", body);
      return new Routed("", wrapped);
    }
    String[] segments = model.split("/", -1);
    StringBuilder path = new StringBuilder();
    for (String segment : segments) {
      if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
        throw Wire.error(
            "INVALID_ARGUMENT", "systemone: \"" + model + "\" is not a Workers AI model ID");
      }
      path.append('/').append(pathEscape(segment));
    }
    body.put("model", model.substring(model.lastIndexOf('/') + 1));
    return new Routed(path.toString(), body);
  }

  /**
   * Reads both response shapes the Workers AI REST API is documented with: the bare model output,
   * and the {result, success, errors} envelope. A failed envelope's messages are read the way an
   * error response's are.
   */
  static String unwrapWorkersAI(String body) {
    JsonNode node;
    try {
      node = Wire.tree(body);
    } catch (JsonProcessingException e) {
      return body;
    }
    if (node == null || !node.isObject()) {
      return body;
    }
    JsonNode success = node.get("success");
    if (success == null || success.isNull()) {
      return body;
    }
    if (!success.asBoolean()) {
      throw new IllegalStateException(Client.errorMessage(body.getBytes(StandardCharsets.UTF_8)));
    }
    JsonNode result = node.get("result");
    return result == null ? "null" : result.toString();
  }

  /**
   * Escapes a string as one path segment: every byte but the unreserved characters and the ones a
   * segment may hold ({@code $&+:=@}) is percent-encoded.
   */
  static String pathEscape(String s) {
    StringBuilder out = new StringBuilder();
    for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
      int c = b & 0xff;
      if ((c >= 'a' && c <= 'z')
          || (c >= 'A' && c <= 'Z')
          || (c >= '0' && c <= '9')
          || "-_.~$&+:=@".indexOf(c) >= 0) {
        out.append((char) c);
      } else {
        out.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)));
        out.append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
      }
    }
    return out.toString();
  }
}
