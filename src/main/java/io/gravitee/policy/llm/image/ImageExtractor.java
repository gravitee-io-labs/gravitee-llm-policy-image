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

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ImageExtractor {

  private static final String INPUT_FIELD = "messages";
  private static final String CONTENT_FIELD = "content";
  private static final String TYPE_FIELD = "type";
  private static final String IMAGE_TYPE = "image_url";
  private static final String IMAGE_URL_FIELD = "image_url";
  private static final String IMAGE_URL_VALUE_FIELD = "url";

  /**
   * Extract all images from an OpenAI Responses format request body.
   * Handles both URL-based and base64-encoded images.
   *
   * Expected format:
   * {
   *   "model" : "qwen3-vl:qwen3-vl:2b",
   *   "messages" : [
   *     {
   *       "role" : "user",
   *       "content" : [
   *         {
   *           "type" : "text",
   *           "text" : "Describe this image."
   *         },
   *         {
   *           "type" : "image_url",
   *           "image_url" : {
   *             "url" : "data:image/png;base64,..."
   *         }]
   *       }
   *   ]}
   */
  public static List<ImageContent> extractImages(JsonObject requestBody) {
    if (requestBody == null) {
      return Collections.emptyList();
    }

    List<ImageContent> images = new ArrayList<>();
    JsonArray messagesArray = requestBody.getJsonArray(INPUT_FIELD);
    if (messagesArray == null) {
      return images;
    }

    for (int inputIndex = 0; inputIndex < messagesArray.size(); inputIndex++) {
      Object inputEntry = messagesArray.getValue(inputIndex);
      if (inputEntry instanceof JsonObject inputObject) {
        extractFromContent(
          images,
          inputIndex,
          inputObject.getValue(CONTENT_FIELD)
        );
      }
    }

    return images;
  }

  private static void extractFromContent(
    List<ImageContent> images,
    int inputIndex,
    Object contentValue
  ) {
    if (contentValue instanceof JsonArray contentArray) {
      for (
        int contentIndex = 0;
        contentIndex < contentArray.size();
        contentIndex++
      ) {
        Object contentEntry = contentArray.getValue(contentIndex);
        if (contentEntry instanceof JsonObject contentObject) {
          extractFromContentObject(
            images,
            inputIndex,
            contentIndex,
            contentObject
          );
        }
      }
      return;
    }

    if (contentValue instanceof JsonObject contentObject) {
      extractFromContentObject(images, inputIndex, null, contentObject);
    }
  }

  private static void extractFromContentObject(
    List<ImageContent> images,
    int inputIndex,
    Integer contentIndex,
    JsonObject contentObject
  ) {
    String type = contentObject.getString(TYPE_FIELD);
    if (!IMAGE_TYPE.equals(type)) {
      return;
    }

    String imageUrl = extractImageUrl(contentObject);
    if (imageUrl == null || imageUrl.isBlank()) {
      if (contentIndex == null) {
        log.debug(
          "Skipping image entry with empty image_url at input[{}].content",
          inputIndex
        );
      } else {
        log.debug(
          "Skipping image entry with empty image_url at input[{}].content[{}]",
          inputIndex,
          contentIndex
        );
      }
      return;
    }

    String[] path = contentIndex == null
      ? new String[] { INPUT_FIELD, String.valueOf(inputIndex), CONTENT_FIELD }
      : new String[] {
        INPUT_FIELD,
        String.valueOf(inputIndex),
        CONTENT_FIELD,
        String.valueOf(contentIndex),
      };
    images.add(new ImageContent(type, imageUrl, path));
  }

  private static String extractImageUrl(JsonObject contentObject) {
    Object imageUrlValue = contentObject.getValue(IMAGE_URL_FIELD);
    if (imageUrlValue instanceof String value) {
      return value;
    }
    if (imageUrlValue instanceof JsonObject imageObject) {
      return imageObject.getString(IMAGE_URL_VALUE_FIELD);
    }
    return null;
  }
}
