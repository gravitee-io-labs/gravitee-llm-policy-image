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

import io.reactivex.rxjava3.core.Single;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.rxjava3.core.Vertx;
import io.vertx.rxjava3.ext.web.client.HttpResponse;
import io.vertx.rxjava3.ext.web.client.WebClient;
import java.util.Collections;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class VisionModelClient {

  private final WebClient webClient;
  private final LlmImagePolicyConfiguration config;
  private final ResolvedEndpoint resolvedEndpoint;

  public VisionModelClient(
    Vertx vertx,
    LlmImagePolicyConfiguration config,
    ResolvedEndpoint resolvedEndpoint
  ) {
    this.webClient = WebClient.create(vertx);
    this.config = config;
    this.resolvedEndpoint = resolvedEndpoint;
  }

  /**
   * Validate an image by sending it to the vision model.
   * Returns Single<Boolean> - true if decision is ACCEPT, false if REJECT or on error.
   */
  public Single<Boolean> validateImage(ImageContent image) {
    String endpoint = resolvedEndpoint.target() + "/chat/completions";

    log.info("Calling vision model at {} for image validation", endpoint);
    log.debug(
      "Vision request payload: model={}, prompt={}",
      config.getLlmModel(),
      config.getValidationPrompt()
    );
    JsonObject payload = buildRequest(image);
    var request = webClient.postAbs(endpoint).timeout(config.getTimeoutMs());
    if (
      resolvedEndpoint.authHeader() != null &&
      resolvedEndpoint.authValue() != null
    ) {
      request.putHeader(
        resolvedEndpoint.authHeader(),
        resolvedEndpoint.authValue()
      );
    }
    return request
      .rxSendJsonObject(payload)
      .doOnSuccess(response ->
        log.info(
          "Vision model responded with status: {}",
          response.statusCode()
        )
      )
      .map(this::parseResponse)
      .doOnError(error ->
        log.warn(
          "Vision validation failed for image at path {}",
          (Object) image.jsonPath(),
          error
        )
      )
      .onErrorReturnItem(false);
  }

  /**
   * Parse the vision model response and extract the validation decision.
   * Returns true if decision is ACCEPT, false otherwise (REJECT, error, or malformed response).
   */
  private boolean parseResponse(HttpResponse<?> response) {
    if (response.statusCode() != 200) {
      log.warn(
        "Vision model returned non-200 status: {}",
        response.statusCode()
      );
      return false;
    }

    String responseBody = response.bodyAsString();
    if (responseBody == null || responseBody.isBlank()) {
      log.warn("Vision model returned empty response body");
      return false;
    }

    ValidationDecision decision = parseDecision(responseBody);
    log.info(
      "Validation decision: {} (confidence: {}, reason: {}, flags: {})",
      decision.decision(),
      decision.confidence(),
      decision.reason(),
      decision.flags()
    );

    return decision.isAccepted();
  }

  /**
   * Parse the model's JSON response to extract the validation decision.
   * Expected format is OpenAI chat completions format with JSON content.
   */
  ValidationDecision parseDecision(String responseBody) {
    try {
      JsonObject response = new JsonObject(responseBody);
      JsonArray choices = response.getJsonArray("choices");
      if (choices == null || choices.isEmpty()) {
        log.warn("No choices in vision model response");
        return rejectDecision("No choices in response");
      }

      JsonObject firstChoice = choices.getJsonObject(0);
      JsonObject message = firstChoice.getJsonObject("message");
      if (message == null) {
        log.warn("No message in first choice");
        return rejectDecision("No message in response");
      }

      String content = message.getString("content");
      if (content == null || content.isBlank()) {
        log.warn("Empty content in message");
        return rejectDecision("Empty content in response");
      }

      return parseContentJson(content);
    } catch (Exception e) {
      log.warn("Failed to parse vision model response: {}", e.getMessage());
      return rejectDecision("Parse error: " + e.getMessage());
    }
  }

  /**
   * Parse the JSON content from the model's message.
   * Handles cases where the model might wrap the JSON in markdown code blocks.
   */
  private ValidationDecision parseContentJson(String content) {
    String jsonContent = extractJson(content);

    try {
      JsonObject decision = new JsonObject(jsonContent);
      String decisionValue = decision.getString("decision");
      if (decisionValue == null) {
        log.warn("No 'decision' field in response content");
        return rejectDecision("Missing decision field");
      }

      double confidence = decision.getDouble("confidence", 0.0);
      String reason = decision.getString("reason", "");
      List<String> flags = extractFlags(decision);

      return new ValidationDecision(decisionValue, confidence, reason, flags);
    } catch (Exception e) {
      log.warn("Failed to parse content JSON: {}", e.getMessage());
      return rejectDecision("Invalid JSON in content: " + e.getMessage());
    }
  }

  /**
   * Extract JSON from content, handling potential markdown code block wrapping.
   */
  private String extractJson(String content) {
    String trimmed = content.trim();

    // Handle markdown code blocks: ```json ... ``` or ``` ... ```
    if (trimmed.startsWith("```")) {
      int startIndex = trimmed.indexOf('\n');
      if (startIndex == -1) {
        return trimmed;
      }
      int endIndex = trimmed.lastIndexOf("```");
      if (endIndex > startIndex) {
        return trimmed.substring(startIndex + 1, endIndex).trim();
      }
    }

    return trimmed;
  }

  /**
   * Extract flags from the decision JSON, handling both array and missing cases.
   */
  private List<String> extractFlags(JsonObject decision) {
    JsonArray flagsArray = decision.getJsonArray("flags");
    if (flagsArray == null) {
      return Collections.emptyList();
    }
    return flagsArray
      .stream()
      .filter(String.class::isInstance)
      .map(String.class::cast)
      .toList();
  }

  /**
   * Create a REJECT decision with the given reason.
   */
  private ValidationDecision rejectDecision(String reason) {
    return new ValidationDecision(
      "REJECT",
      0.0,
      reason,
      Collections.emptyList()
    );
  }

  private JsonObject buildRequest(ImageContent image) {
    JsonObject imagePart = new JsonObject()
      .put("type", "image_url")
      .put("image_url", new JsonObject().put("url", image.imageUrl()));
    JsonObject textPart = new JsonObject()
      .put("type", "text")
      .put("text", config.getValidationPrompt());
    JsonObject message = new JsonObject()
      .put("role", "user")
      .put("content", new JsonArray().add(textPart).add(imagePart));
    return new JsonObject()
      .put("model", config.getLlmModel())
      .put("messages", new JsonArray().add(message));
  }
}
