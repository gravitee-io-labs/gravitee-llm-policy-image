/*
 * Copyright © 2015 The Gravitee team (http://gravitee.io)
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
 */
package io.gravitee.policy.llm.image;

/**
 * Resolved configuration from an LLM Proxy endpoint group.
 *
 * @param target    base URL of the LLM API (e.g. "https://api.openai.com/v1")
 * @param authHeader HTTP header name for authentication (e.g. "Authorization")
 * @param authValue  HTTP header value for authentication (e.g. "Bearer sk-...")
 * @param model      model name from the endpoint group configuration
 */
public record ResolvedEndpoint(
  String target,
  String authHeader,
  String authValue,
  String model
) {}
