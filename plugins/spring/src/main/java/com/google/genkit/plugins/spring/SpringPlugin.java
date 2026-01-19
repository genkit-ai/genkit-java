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

package com.google.genkit.plugins.spring;

import com.google.genkit.core.Action;
import com.google.genkit.core.GenkitException;
import com.google.genkit.core.Registry;
import com.google.genkit.core.ServerPlugin;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * SpringPlugin provides HTTP endpoints for Genkit flows using Spring Boot.
 *
 * <p>This plugin exposes registered flows as HTTP endpoints, making it easy to deploy Genkit
 * applications as web services using Spring Boot.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * Genkit genkit = Genkit.builder()
 *     .plugin(new SpringPlugin(SpringPluginOptions.builder().port(8080).build()))
 *     .build();
 *
 * // Define your flows...
 *
 * // Start the server and block (keeps application running)
 * genkit.start();
 * }</pre>
 */
public class SpringPlugin implements ServerPlugin {

  private static final Logger logger = LoggerFactory.getLogger(SpringPlugin.class);

  private final SpringPluginOptions options;
  private ConfigurableApplicationContext applicationContext;
  private Registry registry;

  // Static holder for sharing state with Spring components
  private static SpringPlugin instance;

  /** Creates a SpringPlugin with default options. */
  public SpringPlugin() {
    this(SpringPluginOptions.builder().build());
  }

  /**
   * Creates a SpringPlugin with the specified options.
   *
   * @param options the plugin options
   */
  public SpringPlugin(SpringPluginOptions options) {
    this.options = options;
    instance = this;
  }

  /**
   * Creates a SpringPlugin with the specified port.
   *
   * @param port the HTTP port
   * @return a new SpringPlugin
   */
  public static SpringPlugin create(int port) {
    return new SpringPlugin(SpringPluginOptions.builder().port(port).build());
  }

  /**
   * Gets the current plugin instance. Used internally by Spring components.
   *
   * @return the current SpringPlugin instance
   */
  static SpringPlugin getInstance() {
    return instance;
  }

  /**
   * Gets the registry. Used internally by Spring components.
   *
   * @return the registry
   */
  Registry getRegistry() {
    return registry;
  }

  /**
   * Gets the plugin options. Used internally by Spring components.
   *
   * @return the options
   */
  SpringPluginOptions getOptions() {
    return options;
  }

  @Override
  public String getName() {
    return "spring";
  }

  @Override
  public List<Action<?, ?, ?>> init() {
    // Spring plugin doesn't provide actions itself
    return Collections.emptyList();
  }

  @Override
  public List<Action<?, ?, ?>> init(Registry registry) {
    this.registry = registry;
    return Collections.emptyList();
  }

  /**
   * Starts the Spring Boot server and blocks until it is stopped.
   *
   * <p>This is the recommended way to start the server in a main() method. Similar to Express's
   * app.listen() in JavaScript, this method will keep your application running until the server is
   * explicitly stopped.
   *
   * <p>Example usage:
   *
   * <pre>{@code
   * SpringPlugin spring = new SpringPlugin(SpringPluginOptions.builder().port(8080).build());
   *
   * Genkit genkit = Genkit.builder().plugin(spring).build();
   *
   * // Define your flows...
   *
   * // Start and block
   * spring.start();
   * }</pre>
   *
   * @throws Exception if the server cannot be started or if interrupted while waiting
   */
  @Override
  public void start() throws Exception {
    if (registry == null) {
      throw new GenkitException(
          "Registry not set. Make sure SpringPlugin is added to Genkit before calling start().");
    }

    startServer();

    // Block until the application is stopped
    if (applicationContext != null) {
      try {
        // Keep the main thread alive while Spring Boot is running
        while (applicationContext.isRunning()) {
          Thread.sleep(1000);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        stop();
      }
    }
  }

  /**
   * Starts the Spring Boot server without blocking.
   *
   * @throws Exception if the server cannot be started
   */
  private void startServer() throws Exception {
    if (applicationContext != null) {
      return;
    }

    if (registry == null) {
      throw new GenkitException(
          "Registry not set. Make sure SpringPlugin is added to Genkit before calling start().");
    }

    // Build Spring Boot application programmatically
    applicationContext =
        new SpringApplicationBuilder(GenkitSpringApplication.class)
            .bannerMode(Banner.Mode.OFF)
            .web(WebApplicationType.SERVLET)
            .properties(
                "server.port=" + options.getPort(),
                "server.address=" + options.getHost(),
                "spring.main.allow-bean-definition-overriding=true",
                "logging.level.org.springframework=WARN")
            .run();

    logger.info("Spring Boot server started on {}:{}", options.getHost(), options.getPort());
  }

  /**
   * Stops the Spring Boot server.
   *
   * @throws Exception if the server cannot be stopped
   */
  @Override
  public void stop() throws Exception {
    if (applicationContext != null) {
      applicationContext.close();
      applicationContext = null;
      logger.info("Spring Boot server stopped");
    }
  }

  /**
   * Returns the port the server is listening on.
   *
   * @return the configured port
   */
  @Override
  public int getPort() {
    return options.getPort();
  }

  /**
   * Returns true if the server is currently running.
   *
   * @return true if the server is running, false otherwise
   */
  @Override
  public boolean isRunning() {
    return applicationContext != null && applicationContext.isRunning();
  }
}
