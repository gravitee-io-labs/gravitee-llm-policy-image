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
import io.gravitee.gateway.reactive.api.ExecutionFailure;
import io.gravitee.gateway.reactive.api.context.http.HttpPlainExecutionContext;
import io.gravitee.gateway.reactive.api.policy.http.HttpPolicy;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.rxjava3.core.Vertx;
import java.util.List;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class LlmImagePolicy implements HttpPolicy {

  private static final String POLICY_ID = "policy-llm-image";
  private static final String REDACTED_MESSAGE =
    "[Image redacted: validation failed]";

  // Block mode error response
  private static final String BLOCK_ERROR_KEY = "IMAGE_VALIDATION_FAILED";
  private static final String BLOCK_ERROR_MESSAGE =
    "Request blocked: inappropriate image(s) detected";
  private static final int BLOCK_STATUS_CODE = 400;
  private static final String DEFAULT_MODEL_NAME = "qwen3-vl:qwen3-vl:2b";
  private static final String PROMPT_TEMPLATE =
    """
      You are an image content moderator. Analyze this image and determine if it should be ACCEPTED or REJECTED.

      REJECT images that contain:
      %s

      ACCEPT images that are:
      %s

      Respond ONLY with a JSON object in this exact format:
      {"decision": "ACCEPT" or "REJECT", "confidence": 0.0-1.0, "reason": "brief explanation", "flags": ["list", "of", "concerns"]}

      Do not include any text outside the JSON object.
      """;
  private static final int DEFAULT_TIMEOUT_MS = 30000;

  private static final List<String> DEFAULT_REJECTED_CATEGORIES = List.of(
    "Explicit sexual content or nudity",
    "Graphic violence or gore",
    "Hate symbols or extremist content",
    "Illegal activities",
    "Child exploitation (always reject immediately)",
    "Spam, scams, or phishing attempts",
    "Personal identifying information (IDs, credit cards, etc.)"
  );

  private static final List<String> DEFAULT_ACCEPTED_CATEGORIES = List.of(
    "Safe for general audiences",
    "Professional or educational content",
    "Artistic content without explicit material",
    "General photography, illustrations, diagrams"
  );

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
    ResolvedEndpoint resolvedEndpoint = resolveEndpointGroup(ctx);
    if (resolvedEndpoint == null) {
      log.warn(
        "Vision endpoint is not configured; set llmProxyApiId to enable image validation"
      );
      return Completable.complete();
    }
    LlmImagePolicyConfiguration effectiveConfig = resolveConfig(
      resolvedEndpoint
    );

    return ctx
      .request()
      .body()
      .flatMapCompletable(body ->
        handleBody(ctx, body, effectiveConfig, resolvedEndpoint)
      )
      .onErrorResumeNext(throwable -> {
        // Only ignore known safe errors (network issues, etc.)
        // All other errors (including interrupt signals) propagate to framework
        if (isIgnorableError(throwable)) {
          log.warn(
            "Failed to process request body for image validation",
            throwable
          );
          return Completable.complete();
        }
        return Completable.error(throwable);
      });
  }

  /**
   * Check if this throwable is an error we should ignore (not propagate).
   * Only specific expected exceptions (like client/network failures) are ignored.
   * All other errors, including interrupt signals, are propagated to the
   * framework.
   */
  private boolean isIgnorableError(Throwable throwable) {
    // Walk the cause chain to find known ignorable exception types
    Throwable current = throwable;
    while (current != null) {
      // Network/connection errors from the vision client can be ignored
      if (current instanceof java.io.IOException) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  protected VisionModelClient createClient(
    Vertx vertx,
    LlmImagePolicyConfiguration effectiveConfig,
    ResolvedEndpoint resolvedEndpoint
  ) {
    return new VisionModelClient(vertx, effectiveConfig, resolvedEndpoint);
  }

  private Completable handleBody(
    HttpPlainExecutionContext ctx,
    Buffer body,
    LlmImagePolicyConfiguration effectiveConfig,
    ResolvedEndpoint resolvedEndpoint
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
    log.info("Found {} image(s) in request", images.size());
    if (!images.isEmpty()) {
      images.forEach(img ->
        log.info(
          "  - Image at path: {} ({})",
          String.join(".", img.jsonPath()),
          img.isBase64() ? "base64" : "url"
        )
      );
    }
    if (images.isEmpty()) {
      return Completable.complete();
    }

    Vertx vertx = ctx.getComponent(Vertx.class);
    if (vertx == null) {
      log.warn("Vertx component unavailable; skipping image validation");
      return Completable.complete();
    }

    VisionModelClient client = createClient(
      vertx,
      effectiveConfig,
      resolvedEndpoint
    );
    return Flowable
      .fromIterable(images)
      .flatMapSingle(image ->
        client
          .validateImage(image)
          .map(valid -> new ValidationResult(image, valid))
      )
      .toList()
      .flatMapCompletable(results -> {
        List<ValidationResult> failures = new java.util.ArrayList<>();

        // Log all results and collect failures in single pass
        for (ValidationResult result : results) {
          log.info(
            "Validation result for image at {}: {}",
            String.join(".", result.image().jsonPath()),
            result.valid() ? "PASSED" : "FAILED"
          );
          if (!result.valid()) {
            failures.add(result);
          }
        }

        if (failures.isEmpty()) {
          return Completable.complete();
        }

        // Check violation mode from config (configuration guarantees non-null)
        ViolationMode mode = effectiveConfig.getOnViolation();

        if (mode == ViolationMode.BLOCK) {
          log.info(
            "Blocking request: {} image(s) failed validation",
            failures.size()
          );
          return ctx.interruptWith(
            new ExecutionFailure(BLOCK_STATUS_CODE)
              .key(BLOCK_ERROR_KEY)
              .message(BLOCK_ERROR_MESSAGE)
          );
        }

        // REDACT mode: replace flagged images with placeholder
        for (ValidationResult failure : failures) {
          redactImage(requestBody, failure.image().jsonPath());
        }
        log.info(
          "Request body modified: {} image(s) redacted",
          failures.size()
        );
        ctx.request().body(Buffer.buffer(requestBody.encode()));
        return Completable.complete();
      });
  }

  protected ResolvedEndpoint resolveEndpointGroup(
    HttpPlainExecutionContext ctx
  ) {
    String apiId = config.getLlmProxyApiId();
    if (apiId == null || apiId.isBlank()) {
      return null;
    }
    return EndpointGroupResolver.resolve(ctx);
  }

  private LlmImagePolicyConfiguration resolveConfig(ResolvedEndpoint resolved) {
    String modelName = firstNonBlank(
      config.getLlmModel(),
      resolved.model(),
      DEFAULT_MODEL_NAME
    );
    int timeoutMs = config.getTimeoutMs() > 0
      ? config.getTimeoutMs()
      : DEFAULT_TIMEOUT_MS;

    // Get category lists from config or use defaults
    List<String> rejectedCategories = config.getRejectedCategories() != null &&
      !config.getRejectedCategories().isEmpty()
      ? config.getRejectedCategories()
      : DEFAULT_REJECTED_CATEGORIES;
    List<String> acceptedCategories = config.getAcceptedCategories() != null &&
      !config.getAcceptedCategories().isEmpty()
      ? config.getAcceptedCategories()
      : DEFAULT_ACCEPTED_CATEGORIES;

    // If validationPrompt is explicitly set, use it; otherwise build from
    // categories
    String validationPrompt = config.getValidationPrompt() != null &&
      !config.getValidationPrompt().isBlank()
      ? config.getValidationPrompt()
      : buildValidationPrompt(rejectedCategories, acceptedCategories);

    ViolationMode onViolation = config.getOnViolation() != null
      ? config.getOnViolation()
      : ViolationMode.BLOCK;

    return LlmImagePolicyConfiguration
      .builder()
      .llmModel(modelName)
      .validationPrompt(validationPrompt)
      .timeoutMs(timeoutMs)
      .rejectedCategories(rejectedCategories)
      .acceptedCategories(acceptedCategories)
      .onViolation(onViolation)
      .build();
  }

  private String buildValidationPrompt(
    List<String> rejectedCategories,
    List<String> acceptedCategories
  ) {
    String rejectedList = rejectedCategories
      .stream()
      .map(cat -> "- " + cat)
      .collect(Collectors.joining("\n"));
    String acceptedList = acceptedCategories
      .stream()
      .map(cat -> "- " + cat)
      .collect(Collectors.joining("\n"));
    return String.format(PROMPT_TEMPLATE, rejectedList, acceptedList);
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
      .put("type", "text")
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
