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

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.List;
import org.junit.jupiter.api.Test;

class ImageExtractorTest {

  @Test
  void extractsUrlBasedImages() {
    JsonObject requestBody = new JsonObject()
      .put(
        "messages",
        new JsonArray()
          .add(
            new JsonObject()
              .put(
                "content",
                new JsonArray()
                  .add(
                    new JsonObject().put("type", "text").put("text", "hello")
                  )
                  .add(
                    new JsonObject()
                      .put("type", "image_url")
                      .put("image_url", "https://example.com/image.png")
                  )
              )
          )
      );

    List<ImageContent> images = ImageExtractor.extractImages(requestBody);

    assertThat(images).hasSize(1);
    ImageContent image = images.get(0);
    assertThat(image.imageUrl()).isEqualTo("https://example.com/image.png");
    assertThat(image.isUrl()).isTrue();
    assertThat(image.jsonPath())
      .containsExactly("messages", "0", "content", "1");
  }

  @Test
  void extractsBase64Images() {
    String dataUrl = "data:image/png;base64,abcd";
    JsonObject requestBody = new JsonObject()
      .put(
        "messages",
        new JsonArray()
          .add(
            new JsonObject()
              .put(
                "content",
                new JsonArray()
                  .add(
                    new JsonObject()
                      .put("type", "image_url")
                      .put("image_url", dataUrl)
                  )
              )
          )
      );

    List<ImageContent> images = ImageExtractor.extractImages(requestBody);

    assertThat(images).hasSize(1);
    ImageContent image = images.get(0);
    assertThat(image.imageUrl()).isEqualTo(dataUrl);
    assertThat(image.isBase64()).isTrue();
  }

  @Test
  void extractsMultipleImagesFromConversation() {
    JsonObject requestBody = new JsonObject()
      .put(
        "messages",
        new JsonArray()
          .add(
            new JsonObject()
              .put(
                "content",
                new JsonArray()
                  .add(
                    new JsonObject()
                      .put("type", "image_url")
                      .put("image_url", "https://example.com/first.png")
                  )
              )
          )
          .add(
            new JsonObject()
              .put(
                "content",
                new JsonArray()
                  .add(new JsonObject().put("type", "text").put("text", "next"))
                  .add(
                    new JsonObject()
                      .put("type", "image_url")
                      .put("image_url", "https://example.com/second.png")
                  )
              )
          )
      );

    List<ImageContent> images = ImageExtractor.extractImages(requestBody);

    assertThat(images).hasSize(2);
    assertThat(images.get(1).jsonPath())
      .containsExactly("messages", "1", "content", "1");
    assertThat(images.get(1).imageUrl())
      .isEqualTo("https://example.com/second.png");
  }

  @Test
  void returnsEmptyWhenNoImages() {
    JsonObject requestBody = new JsonObject()
      .put(
        "messages",
        new JsonArray()
          .add(
            new JsonObject()
              .put(
                "content",
                new JsonArray()
                  .add(
                    new JsonObject()
                      .put("type", "text")
                      .put("text", "only text")
                  )
              )
          )
      );

    List<ImageContent> images = ImageExtractor.extractImages(requestBody);

    assertThat(images).isEmpty();
  }
}
