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
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

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
}
