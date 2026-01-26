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

import io.gravitee.gateway.api.buffer.Buffer;
import io.gravitee.gateway.reactive.api.context.http.HttpPlainExecutionContext;
import io.gravitee.gateway.reactive.api.policy.http.HttpPolicy;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.rxjava3.core.Vertx;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class LlmImagePolicy implements HttpPolicy {

  private static final String POLICY_ID = "policy-llm-image";
  private static final String REDACTED_MESSAGE =
    "[Image redacted: validation failed]";
  private static final String ENV_VISION_ENDPOINT =
    "GRAVITEE_LLM_IMAGE_VISION_ENDPOINT";

  private static final String DEFAULT_MODEL_NAME = "qwen3-vl";
  private static final String DEFAULT_VALIDATION_PROMPT =
    "Describe this image in a few words.";
  private static final int DEFAULT_TIMEOUT_MS = 30000;

  private final LlmImagePolicyConfiguration config;

  public LlmImagePolicy(LlmImagePolicyConfiguration config) {
    this.config = config == null ? new LlmImagePolicyConfiguration() : config;
  }

  @Override
  public String id() {
    return POLICY_ID;
  }

  @Override
  public Completable onRequest(HttpPlainExecutionContext ctx) {
    LlmImagePolicyConfiguration effectiveConfig = resolveConfig();
    if (
      effectiveConfig.getVisionEndpoint() == null ||
      effectiveConfig.getVisionEndpoint().isBlank()
    ) {
      log.warn(
        "Vision endpoint is not configured; set policy config or {} to enable image validation",
        ENV_VISION_ENDPOINT
      );
      return Completable.complete();
    }

    return ctx
      .request()
      .body()
      .flatMapCompletable(body -> handleBody(ctx, body, effectiveConfig))
      .doOnError(error ->
        log.warn("Failed to process request body for image validation", error)
      )
      .onErrorComplete();
  }

  protected VisionModelClient createClient(
    Vertx vertx,
    LlmImagePolicyConfiguration effectiveConfig
  ) {
    return new VisionModelClient(vertx, effectiveConfig);
  }

  private Completable handleBody(
    HttpPlainExecutionContext ctx,
    Buffer body,
    LlmImagePolicyConfiguration effectiveConfig
  ) {
    if (body == null || body.length() == 0) {
      return Completable.complete();
    }

    JsonObject requestBody;
    try {
      requestBody = new JsonObject(body.toString());
    } catch (Exception ex) {
      log.debug(
        "Request body is not valid JSON; skipping image validation",
        ex
      );
      return Completable.complete();
    }

    List<ImageContent> images = ImageExtractor.extractImages(requestBody);
    if (images.isEmpty()) {
      return Completable.complete();
    }

    Vertx vertx = ctx.getComponent(Vertx.class);
    if (vertx == null) {
      log.warn("Vertx component unavailable; skipping image validation");
      return Completable.complete();
    }

    VisionModelClient client = createClient(vertx, effectiveConfig);
    return Flowable
      .fromIterable(images)
      .flatMapSingle(image ->
        client
          .validateImage(image)
          .map(valid -> new ValidationResult(image, valid))
      )
      .toList()
      .flatMapCompletable(results -> {
        boolean modified = false;
        for (ValidationResult result : results) {
          if (!result.valid()) {
            redactImage(requestBody, result.image().jsonPath());
            modified = true;
          }
        }
        if (modified) {
          ctx.request().body(Buffer.buffer(requestBody.encode()));
        }
        return Completable.complete();
      });
  }

  private LlmImagePolicyConfiguration resolveConfig() {
    String visionEndpoint = firstNonBlank(
      config.getVisionEndpoint(),
      System.getenv(ENV_VISION_ENDPOINT)
    );
    String modelName = firstNonBlank(config.getModelName(), DEFAULT_MODEL_NAME);
    String validationPrompt = firstNonBlank(
      config.getValidationPrompt(),
      DEFAULT_VALIDATION_PROMPT
    );
    int timeoutMs = config.getTimeoutMs() > 0
      ? config.getTimeoutMs()
      : DEFAULT_TIMEOUT_MS;

    return LlmImagePolicyConfiguration
      .builder()
      .visionEndpoint(visionEndpoint)
      .modelName(modelName)
      .validationPrompt(validationPrompt)
      .timeoutMs(timeoutMs)
      .build();
  }

  private static String firstNonBlank(String... values) {
    if (values == null) {
      return null;
    }
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }

  private void redactImage(JsonObject original, String[] jsonPath) {
    if (original == null || jsonPath == null || jsonPath.length == 0) {
      return;
    }

    Object parent = resolveParent(original, jsonPath);
    String lastSegment = jsonPath[jsonPath.length - 1];
    JsonObject redacted = new JsonObject()
      .put("type", "input_text")
      .put("text", REDACTED_MESSAGE);

    if (parent instanceof JsonArray array) {
      Integer index = parseIndex(lastSegment);
      if (index != null && index >= 0 && index < array.size()) {
        array.set(index, redacted);
      }
      return;
    }

    if (parent instanceof JsonObject object) {
      object.put(lastSegment, redacted);
    }
  }

  private Object resolveParent(JsonObject root, String[] path) {
    Object current = root;
    for (int i = 0; i < path.length - 1; i++) {
      if (current instanceof JsonObject object) {
        current = object.getValue(path[i]);
      } else if (current instanceof JsonArray array) {
        Integer index = parseIndex(path[i]);
        if (index == null || index < 0 || index >= array.size()) {
          return null;
        }
        current = array.getValue(index);
      } else {
        return null;
      }
    }
    return current;
  }

  private Integer parseIndex(String value) {
    if (value == null) {
      return null;
    }
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private record ValidationResult(ImageContent image, boolean valid) {}
}
