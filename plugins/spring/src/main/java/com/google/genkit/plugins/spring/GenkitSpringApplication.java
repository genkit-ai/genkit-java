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

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring Boot application class for Genkit.
 *
 * <p>This class is the entry point for Spring Boot auto-configuration and component scanning for
 * the Genkit Spring plugin.
 */
@SpringBootApplication
public class GenkitSpringApplication {

  /**
   * Creates an ObjectMapper bean for JSON serialization.
   *
   * @return the ObjectMapper
   */
  @Bean
  public ObjectMapper objectMapper() {
    return new ObjectMapper();
  }

  /**
   * Creates the Genkit flow controller bean.
   *
   * @param objectMapper the ObjectMapper for JSON serialization
   * @return the flow controller
   */
  @Bean
  public GenkitFlowController genkitFlowController(ObjectMapper objectMapper) {
    return new GenkitFlowController(objectMapper);
  }

  /**
   * Creates the Genkit agent controller bean.
   *
   * @param objectMapper the ObjectMapper for JSON serialization
   * @return the agent controller
   */
  @Bean
  public GenkitAgentController genkitAgentController(ObjectMapper objectMapper) {
    return new GenkitAgentController(objectMapper);
  }

  /**
   * Configures Spring MVC to deserialize {@code @RequestBody} JSON using the Jackson 2 ({@code
   * com.fasterxml.jackson.databind}) {@link ObjectMapper} that {@link GenkitFlowController} and
   * {@link GenkitAgentController} use for {@code JsonNode} handling.
   *
   * <p>{@code spring-boot-starter-web} on Spring Boot 4 auto-configures a Jackson 3 ({@code
   * tools.jackson.databind}) message converter as the default JSON converter. Jackson 3's binder
   * cannot construct instances of the Jackson 2 {@code com.fasterxml.jackson.databind.JsonNode}
   * type used throughout Genkit's core action APIs, so {@code @RequestBody JsonNode} parameters
   * would otherwise fail to bind. Prepending a {@link MappingJackson2HttpMessageConverter} backed
   * by the Jackson 2 {@code ObjectMapper} makes it the preferred converter for {@code
   * application/json} bodies.
   *
   * @param objectMapper the Jackson 2 ObjectMapper for JSON serialization
   * @return the MVC configurer
   */
  @Bean
  public WebMvcConfigurer genkitJackson2MessageConverterConfigurer(ObjectMapper objectMapper) {
    return new WebMvcConfigurer() {
      @Override
      public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        converters.add(0, new MappingJackson2HttpMessageConverter(objectMapper));
      }
    };
  }
}
