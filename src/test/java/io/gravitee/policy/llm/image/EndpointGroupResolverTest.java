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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.gravitee.definition.model.v4.Api;
import io.gravitee.definition.model.v4.endpointgroup.Endpoint;
import io.gravitee.definition.model.v4.endpointgroup.EndpointGroup;
import io.gravitee.gateway.reactive.api.context.http.HttpPlainExecutionContext;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EndpointGroupResolverTest {

  @Mock
  private HttpPlainExecutionContext ctx;

  private String makeEndpointConfig(
    String target,
    String authType,
    String apiKey,
    String headerName,
    String bearer,
    String modelName
  ) {
    var auth = new JsonObject().put("type", authType);
    if (apiKey != null) auth.put("apiKey", apiKey);
    if (headerName != null) auth.put("headerName", headerName);
    if (bearer != null) auth.put("bearer", bearer);

    var config = new JsonObject()
      .put("target", target)
      .put("authentication", auth);

    if (modelName != null) {
      config.put(
        "models",
        new JsonArray().add(new JsonObject().put("name", modelName))
      );
    }

    return config.encode();
  }

  private Api buildApi(String endpointConfig) {
    var endpoint = Endpoint.builder().name("ep-1").build();
    endpoint.setConfiguration(endpointConfig);

    var group = EndpointGroup
      .builder()
      .name("llm-group")
      .type("llm-proxy")
      .endpoints(List.of(endpoint))
      .build();

    return Api.builder().endpointGroups(List.of(group)).build();
  }

  @Test
  @DisplayName("should resolve endpoint group with API_KEY auth")
  void shouldResolveWithApiKeyAuth() {
    var config = makeEndpointConfig(
      "https://api.openai.com/v1",
      "API_KEY",
      "sk-test-123",
      "X-Api-Key",
      null,
      "gpt-4"
    );
    var api = buildApi(config);
    when(ctx.getComponent(Api.class)).thenReturn(api);

    var resolved = EndpointGroupResolver.resolve(ctx);

    assertThat(resolved).isNotNull();
    assertThat(resolved.target()).isEqualTo("https://api.openai.com/v1");
    assertThat(resolved.authHeader()).isEqualTo("X-Api-Key");
    assertThat(resolved.authValue()).isEqualTo("sk-test-123");
    assertThat(resolved.model()).isEqualTo("gpt-4");
  }

  @Test
  @DisplayName("should resolve endpoint group with BEARER auth")
  void shouldResolveWithBearerAuth() {
    var config = makeEndpointConfig(
      "https://api.openai.com/v1",
      "BEARER",
      null,
      null,
      "my-bearer-token",
      "gpt-4o"
    );
    var api = buildApi(config);
    when(ctx.getComponent(Api.class)).thenReturn(api);

    var resolved = EndpointGroupResolver.resolve(ctx);

    assertThat(resolved).isNotNull();
    assertThat(resolved.target()).isEqualTo("https://api.openai.com/v1");
    assertThat(resolved.authHeader()).isEqualTo("Authorization");
    assertThat(resolved.authValue()).isEqualTo("Bearer my-bearer-token");
    assertThat(resolved.model()).isEqualTo("gpt-4o");
  }

  @Test
  @DisplayName("should resolve endpoint group with NONE auth")
  void shouldResolveWithNoAuth() {
    var config = makeEndpointConfig(
      "http://localhost:11434/v1",
      "NONE",
      null,
      null,
      null,
      "llama3"
    );
    var api = buildApi(config);
    when(ctx.getComponent(Api.class)).thenReturn(api);

    var resolved = EndpointGroupResolver.resolve(ctx);

    assertThat(resolved).isNotNull();
    assertThat(resolved.target()).isEqualTo("http://localhost:11434/v1");
    assertThat(resolved.authHeader()).isNull();
    assertThat(resolved.authValue()).isNull();
    assertThat(resolved.model()).isEqualTo("llama3");
  }

  @Test
  @DisplayName("should return null when no LLM endpoint group found")
  void shouldReturnNullWhenGroupNotFound() {
    var config = makeEndpointConfig(
      "https://api.openai.com/v1",
      "BEARER",
      null,
      null,
      "token",
      "gpt-4"
    );

    var endpoint = Endpoint.builder().name("ep-1").build();
    endpoint.setConfiguration(config);
    var group = EndpointGroup
      .builder()
      .name("http-group")
      .type("http")
      .endpoints(List.of(endpoint))
      .build();
    var api = Api.builder().endpointGroups(List.of(group)).build();
    when(ctx.getComponent(Api.class)).thenReturn(api);

    var resolved = EndpointGroupResolver.resolve(ctx);

    assertThat(resolved).isNull();
  }

  @Test
  @DisplayName("should return null when API is not available")
  void shouldReturnNullWhenApiUnavailable() {
    when(ctx.getComponent(Api.class)).thenReturn(null);

    var resolved = EndpointGroupResolver.resolve(ctx);

    assertThat(resolved).isNull();
  }

  @Test
  @DisplayName("should return null when endpoint group has no endpoints")
  void shouldReturnNullWhenGroupHasNoEndpoints() {
    var group = EndpointGroup
      .builder()
      .name("empty-group")
      .type("llm-proxy")
      .endpoints(List.of())
      .build();
    var api = Api.builder().endpointGroups(List.of(group)).build();
    when(ctx.getComponent(Api.class)).thenReturn(api);

    var resolved = EndpointGroupResolver.resolve(ctx);

    assertThat(resolved).isNull();
  }

  @Test
  @DisplayName("should use shared configuration when endpoint inherits")
  void shouldUseSharedConfigurationWhenInherited() {
    var sharedConfig = makeEndpointConfig(
      "https://api.openai.com/v1",
      "BEARER",
      null,
      null,
      "shared-token",
      "gpt-4"
    );

    var endpoint = Endpoint.builder().name("ep-1").build();
    endpoint.setInheritConfiguration(true);

    var group = EndpointGroup
      .builder()
      .name("inherited-group")
      .type("llm-proxy")
      .endpoints(List.of(endpoint))
      .build();
    group.setSharedConfiguration(sharedConfig);

    var api = Api.builder().endpointGroups(List.of(group)).build();
    when(ctx.getComponent(Api.class)).thenReturn(api);

    var resolved = EndpointGroupResolver.resolve(ctx);

    assertThat(resolved).isNotNull();
    assertThat(resolved.target()).isEqualTo("https://api.openai.com/v1");
    assertThat(resolved.authValue()).isEqualTo("Bearer shared-token");
    assertThat(resolved.model()).isEqualTo("gpt-4");
  }

  @Test
  @DisplayName("should extract model from first entry in models array")
  void shouldExtractModelFromFirstEntry() {
    var auth = new JsonObject().put("type", "NONE");
    var models = new JsonArray()
      .add(new JsonObject().put("name", "first-model"))
      .add(new JsonObject().put("name", "second-model"));
    var config = new JsonObject()
      .put("target", "https://api.example.com")
      .put("authentication", auth)
      .put("models", models)
      .encode();

    var api = buildApi(config);
    when(ctx.getComponent(Api.class)).thenReturn(api);

    var resolved = EndpointGroupResolver.resolve(ctx);

    assertThat(resolved).isNotNull();
    assertThat(resolved.model()).isEqualTo("first-model");
  }

  @Test
  @DisplayName("should handle missing models array gracefully")
  void shouldHandleMissingModelsArray() {
    var config = new JsonObject()
      .put("target", "https://api.example.com")
      .put("authentication", new JsonObject().put("type", "NONE"))
      .encode();

    var api = buildApi(config);
    when(ctx.getComponent(Api.class)).thenReturn(api);

    var resolved = EndpointGroupResolver.resolve(ctx);

    assertThat(resolved).isNotNull();
    assertThat(resolved.target()).isEqualTo("https://api.example.com");
    assertThat(resolved.model()).isNull();
  }

  @Test
  @DisplayName(
    "should fall back to endpoint config when shared config has no target"
  )
  void shouldFallBackToEndpointConfigWhenSharedConfigHasNoTarget() {
    // Shared config is present but has no target (common in real APIM deployments)
    var sharedConfig = new JsonObject()
      .put("authentication", new JsonObject().put("type", "NONE"))
      .encode();

    // Full config (with target) is in the endpoint's own configuration
    var endpointConfig = makeEndpointConfig(
      "https://api.openai.com/v1",
      "BEARER",
      null,
      null,
      "fallback-token",
      "gpt-4"
    );

    var endpoint = Endpoint.builder().name("ep-1").build();
    endpoint.setInheritConfiguration(true);
    endpoint.setConfiguration(endpointConfig);

    var group = EndpointGroup
      .builder()
      .name("llm-group")
      .type("llm-proxy")
      .endpoints(List.of(endpoint))
      .build();
    group.setSharedConfiguration(sharedConfig);

    var api = Api.builder().endpointGroups(List.of(group)).build();
    when(ctx.getComponent(Api.class)).thenReturn(api);

    var resolved = EndpointGroupResolver.resolve(ctx);

    assertThat(resolved).isNotNull();
    assertThat(resolved.target()).isEqualTo("https://api.openai.com/v1");
    assertThat(resolved.authValue()).isEqualTo("Bearer fallback-token");
    assertThat(resolved.model()).isEqualTo("gpt-4");
  }

  @Test
  @DisplayName(
    "should fall back to shared config when endpoint config has no target"
  )
  void shouldFallBackToSharedConfigWhenEndpointConfigHasNoTarget() {
    // Endpoint has no target; target lives in shared config
    var sharedConfig = makeEndpointConfig(
      "https://api.openai.com/v1",
      "API_KEY",
      "sk-shared",
      "x-api-key",
      null,
      "gpt-4o"
    );

    var endpointConfig = new JsonObject().put("provider", "OPEN_AI").encode(); // no target

    var endpoint = Endpoint.builder().name("ep-1").build();
    endpoint.setInheritConfiguration(false); // not inheriting, but own config lacks target
    endpoint.setConfiguration(endpointConfig);

    var group = EndpointGroup
      .builder()
      .name("llm-group")
      .type("llm-proxy")
      .endpoints(List.of(endpoint))
      .build();
    group.setSharedConfiguration(sharedConfig);

    var api = Api.builder().endpointGroups(List.of(group)).build();
    when(ctx.getComponent(Api.class)).thenReturn(api);

    var resolved = EndpointGroupResolver.resolve(ctx);

    assertThat(resolved).isNotNull();
    assertThat(resolved.target()).isEqualTo("https://api.openai.com/v1");
    assertThat(resolved.authHeader()).isEqualTo("x-api-key");
    assertThat(resolved.authValue()).isEqualTo("sk-shared");
    assertThat(resolved.model()).isEqualTo("gpt-4o");
  }

  @Test
  @DisplayName("should return null when target is absent from both configs")
  void shouldReturnNullWhenTargetAbsentFromBothConfigs() {
    var configNoTarget = new JsonObject()
      .put("provider", "OPEN_AI")
      .put("authentication", new JsonObject().put("type", "NONE"))
      .encode();

    var endpoint = Endpoint.builder().name("ep-1").build();
    endpoint.setConfiguration(configNoTarget);

    var group = EndpointGroup
      .builder()
      .name("llm-group")
      .type("llm-proxy")
      .endpoints(List.of(endpoint))
      .build();
    group.setSharedConfiguration("{}");

    var api = Api.builder().endpointGroups(List.of(group)).build();
    when(ctx.getComponent(Api.class)).thenReturn(api);

    var resolved = EndpointGroupResolver.resolve(ctx);

    assertThat(resolved).isNull();
  }

  @Test
  @DisplayName("should return null when endpoint groups list is null")
  void shouldReturnNullWhenEndpointGroupsNull() {
    var api = Api.builder().build();
    when(ctx.getComponent(Api.class)).thenReturn(api);

    var resolved = EndpointGroupResolver.resolve(ctx);

    assertThat(resolved).isNull();
  }

  @Test
  @DisplayName(
    "should default API_KEY header to api-key when headerName is missing"
  )
  void shouldDefaultApiKeyHeader() {
    var config = makeEndpointConfig(
      "https://api.openai.com/v1",
      "API_KEY",
      "sk-key",
      null,
      null,
      "gpt-4"
    );
    var api = buildApi(config);
    when(ctx.getComponent(Api.class)).thenReturn(api);

    var resolved = EndpointGroupResolver.resolve(ctx);

    assertThat(resolved).isNotNull();
    assertThat(resolved.authHeader()).isEqualTo("api-key");
    assertThat(resolved.authValue()).isEqualTo("sk-key");
  }
}
