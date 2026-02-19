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

import io.gravitee.definition.model.v4.Api;
import io.gravitee.definition.model.v4.endpointgroup.EndpointGroup;
import io.gravitee.gateway.reactive.api.context.http.HttpPlainExecutionContext;
import io.vertx.core.json.JsonObject;
import lombok.extern.slf4j.Slf4j;

/**
 * Resolves an LLM Proxy endpoint group by type from the API definition,
 * extracting the target URL, authentication, and model configuration.
 */
@Slf4j
public final class EndpointGroupResolver {

  private EndpointGroupResolver() {}

  /**
   * Resolve the first endpoint group of type {@code "llm-proxy"} from the current API definition.
   *
   * @return a {@link ResolvedEndpoint} or {@code null} if not found
   */
  public static ResolvedEndpoint resolve(HttpPlainExecutionContext ctx) {
    var api = ctx.getComponent(Api.class);
    if (api == null) {
      log.warn("API definition not available in execution context");
      return null;
    }

    var groups = api.getEndpointGroups();
    if (groups == null) {
      return null;
    }

    for (var group : groups) {
      if ("llm-proxy".equals(group.getType())) {
        return resolveFromGroup(group);
      }
    }

    log.warn("No LLM endpoint group found in API definition");
    return null;
  }

  private static ResolvedEndpoint resolveFromGroup(EndpointGroup group) {
    var endpoints = group.getEndpoints();
    if (endpoints == null || endpoints.isEmpty()) {
      log.warn("Endpoint group '{}' has no endpoints", group.getName());
      return null;
    }

    var endpoint = endpoints.get(0);

    // Determine primary and fallback config sources.
    // APIM may store the full config in either the shared configuration
    // (when inheritConfiguration=true) or in the endpoint's own configuration.
    // We try the natural source first and fall back to the other if target is absent.
    var primaryJson = endpoint.isInheritConfiguration()
      ? group.getSharedConfiguration()
      : endpoint.getConfiguration();
    var fallbackJson = endpoint.isInheritConfiguration()
      ? endpoint.getConfiguration()
      : group.getSharedConfiguration();

    var configJson = isUsable(primaryJson) ? primaryJson : fallbackJson;
    if (!isUsable(configJson)) {
      log.warn(
        "Endpoint configuration is empty for group '{}'",
        group.getName()
      );
      return null;
    }

    try {
      var config = new JsonObject(configJson);
      var target = config.getString("target");

      // If target is missing from the primary source, try the fallback.
      if ((target == null || target.isBlank()) && isUsable(fallbackJson)) {
        log.debug(
          "Target not found in primary config for group '{}', trying fallback",
          group.getName()
        );
        var fallback = new JsonObject(fallbackJson);
        target = fallback.getString("target");
        if (target != null && !target.isBlank()) {
          // Use fallback as the config source for auth/models too
          config = fallback;
        }
      }

      if (target == null || target.isBlank()) {
        log.warn(
          "No target URL found in endpoint configuration for group '{}'. " +
          "Ensure the LLM Proxy endpoint has a target URL configured.",
          group.getName()
        );
        return null;
      }

      var model = extractModel(config);
      var auth = extractAuth(config);

      log.debug(
        "Resolved endpoint for group '{}': target={}, model={}",
        group.getName(),
        target,
        model
      );

      return new ResolvedEndpoint(
        target,
        auth != null ? auth.header() : null,
        auth != null ? auth.value() : null,
        model
      );
    } catch (Exception e) {
      log.warn(
        "Failed to parse endpoint configuration for group '{}'",
        group.getName(),
        e
      );
      return null;
    }
  }

  private static boolean isUsable(String s) {
    return s != null && !s.isBlank();
  }

  private static String extractModel(JsonObject config) {
    var models = config.getJsonArray("models");
    if (models == null || models.isEmpty()) {
      return null;
    }
    var first = models.getJsonObject(0);
    return first != null ? first.getString("name") : null;
  }

  private static AuthInfo extractAuth(JsonObject config) {
    var auth = config.getJsonObject("authentication");
    if (auth == null) {
      return null;
    }

    var type = auth.getString("type", "NONE");
    return switch (type) {
      case "API_KEY" -> {
        var headerName = auth.getString("headerName", "api-key");
        var apiKey = auth.getString("apiKey", "");
        yield new AuthInfo(headerName, apiKey);
      }
      case "BEARER" -> {
        var bearer = auth.getString("bearer", "");
        yield new AuthInfo("Authorization", "Bearer " + bearer);
      }
      default -> null;
    };
  }

  private record AuthInfo(String header, String value) {}
}
