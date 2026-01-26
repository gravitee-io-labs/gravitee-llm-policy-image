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
import io.vertx.rxjava3.ext.web.client.WebClient;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class VisionModelClient {

  private final WebClient webClient;
  private final LlmImagePolicyConfiguration config;

  public VisionModelClient(Vertx vertx, LlmImagePolicyConfiguration config) {
    this.webClient = WebClient.create(vertx);
    this.config = config;
  }

  /**
   * Validate an image by sending it to the vision model.
   * Returns Single<Boolean> - true if validation passes (200 response), false otherwise.
   */
  public Single<Boolean> validateImage(ImageContent image) {
    String endpoint = config.getVisionEndpoint();
    if (endpoint == null || endpoint.isBlank()) {
      log.warn(
        "Vision endpoint is not configured; redacting image at path {}",
        (Object) image.jsonPath()
      );
      return Single.just(false);
    }

    JsonObject payload = buildRequest(image);
    return webClient
      .postAbs(endpoint)
      .timeout(config.getTimeoutMs())
      .rxSendJsonObject(payload)
      .map(response -> response.statusCode() == 200)
      .doOnError(error ->
        log.warn(
          "Vision validation failed for image at path {}",
          (Object) image.jsonPath(),
          error
        )
      )
      .onErrorReturnItem(false);
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
      .put("model", config.getModelName())
      .put("messages", new JsonArray().add(message));
  }
}
