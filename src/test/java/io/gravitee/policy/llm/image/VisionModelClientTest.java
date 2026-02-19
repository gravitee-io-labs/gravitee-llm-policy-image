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
import io.vertx.rxjava3.core.Vertx;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class VisionModelClientTest {

  private static Vertx vertx;
  private static VisionModelClient client;

  @BeforeAll
  static void setup() {
    vertx = Vertx.vertx();
    LlmImagePolicyConfiguration config = LlmImagePolicyConfiguration
      .builder()
      .llmModel("test-model")
      .validationPrompt("test prompt")
      .timeoutMs(5000)
      .build();
    client =
      new VisionModelClient(
        vertx,
        config,
        new ResolvedEndpoint("http://localhost:8000/v1", null, null, null)
      );
  }

  @AfterAll
  static void teardown() {
    if (vertx != null) {
      vertx.close();
    }
  }

  @Test
  void parseDecision_acceptDecision_returnsTrue() {
    String responseBody = chatCompletionResponse(
      new JsonObject()
        .put("decision", "ACCEPT")
        .put("confidence", 0.95)
        .put("reason", "Safe image")
        .put("flags", new JsonArray())
        .encode()
    );

    ValidationDecision decision = client.parseDecision(responseBody);

    assertThat(decision.isAccepted()).isTrue();
    assertThat(decision.decision()).isEqualTo("ACCEPT");
    assertThat(decision.confidence()).isEqualTo(0.95);
    assertThat(decision.reason()).isEqualTo("Safe image");
    assertThat(decision.flags()).isEmpty();
  }

  @Test
  void parseDecision_rejectDecision_returnsFalse() {
    String responseBody = chatCompletionResponse(
      new JsonObject()
        .put("decision", "REJECT")
        .put("confidence", 0.87)
        .put("reason", "Contains inappropriate content")
        .put("flags", new JsonArray().add("nudity").add("violence"))
        .encode()
    );

    ValidationDecision decision = client.parseDecision(responseBody);

    assertThat(decision.isAccepted()).isFalse();
    assertThat(decision.decision()).isEqualTo("REJECT");
    assertThat(decision.confidence()).isEqualTo(0.87);
    assertThat(decision.reason()).isEqualTo("Contains inappropriate content");
    assertThat(decision.flags()).containsExactly("nudity", "violence");
  }

  @Test
  void parseDecision_lowercaseAccept_returnsTrue() {
    String responseBody = chatCompletionResponse(
      new JsonObject()
        .put("decision", "accept")
        .put("confidence", 0.9)
        .put("reason", "OK")
        .put("flags", new JsonArray())
        .encode()
    );

    ValidationDecision decision = client.parseDecision(responseBody);

    assertThat(decision.isAccepted()).isTrue();
  }

  @Test
  void parseDecision_malformedJson_returnsFalse() {
    String responseBody = chatCompletionResponse("this is not valid json");

    ValidationDecision decision = client.parseDecision(responseBody);

    assertThat(decision.isAccepted()).isFalse();
    assertThat(decision.decision()).isEqualTo("REJECT");
  }

  @Test
  void parseDecision_missingDecisionField_returnsFalse() {
    String responseBody = chatCompletionResponse(
      new JsonObject()
        .put("confidence", 0.5)
        .put("reason", "No decision")
        .encode()
    );

    ValidationDecision decision = client.parseDecision(responseBody);

    assertThat(decision.isAccepted()).isFalse();
    assertThat(decision.decision()).isEqualTo("REJECT");
    assertThat(decision.reason()).contains("Missing decision field");
  }

  @Test
  void parseDecision_emptyChoices_returnsFalse() {
    String responseBody = new JsonObject()
      .put("choices", new JsonArray())
      .encode();

    ValidationDecision decision = client.parseDecision(responseBody);

    assertThat(decision.isAccepted()).isFalse();
    assertThat(decision.decision()).isEqualTo("REJECT");
  }

  @Test
  void parseDecision_noMessage_returnsFalse() {
    String responseBody = new JsonObject()
      .put("choices", new JsonArray().add(new JsonObject()))
      .encode();

    ValidationDecision decision = client.parseDecision(responseBody);

    assertThat(decision.isAccepted()).isFalse();
    assertThat(decision.decision()).isEqualTo("REJECT");
  }

  @Test
  void parseDecision_emptyContent_returnsFalse() {
    String responseBody = new JsonObject()
      .put(
        "choices",
        new JsonArray()
          .add(
            new JsonObject().put("message", new JsonObject().put("content", ""))
          )
      )
      .encode();

    ValidationDecision decision = client.parseDecision(responseBody);

    assertThat(decision.isAccepted()).isFalse();
    assertThat(decision.decision()).isEqualTo("REJECT");
  }

  @Test
  void parseDecision_jsonInCodeBlock_parsesCorrectly() {
    String jsonContent = new JsonObject()
      .put("decision", "ACCEPT")
      .put("confidence", 0.99)
      .put("reason", "Safe")
      .put("flags", new JsonArray())
      .encode();
    String wrappedContent = "```json\n" + jsonContent + "\n```";
    String responseBody = chatCompletionResponse(wrappedContent);

    ValidationDecision decision = client.parseDecision(responseBody);

    assertThat(decision.isAccepted()).isTrue();
    assertThat(decision.decision()).isEqualTo("ACCEPT");
  }

  @Test
  void parseDecision_jsonInPlainCodeBlock_parsesCorrectly() {
    String jsonContent = new JsonObject()
      .put("decision", "REJECT")
      .put("confidence", 0.8)
      .put("reason", "Unsafe")
      .put("flags", new JsonArray().add("violence"))
      .encode();
    String wrappedContent = "```\n" + jsonContent + "\n```";
    String responseBody = chatCompletionResponse(wrappedContent);

    ValidationDecision decision = client.parseDecision(responseBody);

    assertThat(decision.isAccepted()).isFalse();
    assertThat(decision.decision()).isEqualTo("REJECT");
  }

  @Test
  void parseDecision_missingFlags_returnsEmptyList() {
    String responseBody = chatCompletionResponse(
      new JsonObject()
        .put("decision", "ACCEPT")
        .put("confidence", 0.9)
        .put("reason", "OK")
        .encode()
    );

    ValidationDecision decision = client.parseDecision(responseBody);

    assertThat(decision.isAccepted()).isTrue();
    assertThat(decision.flags()).isEmpty();
  }

  @Test
  void parseDecision_missingConfidence_defaultsToZero() {
    String responseBody = chatCompletionResponse(
      new JsonObject()
        .put("decision", "ACCEPT")
        .put("reason", "OK")
        .put("flags", new JsonArray())
        .encode()
    );

    ValidationDecision decision = client.parseDecision(responseBody);

    assertThat(decision.confidence()).isEqualTo(0.0);
  }

  @Test
  void parseDecision_invalidResponseFormat_returnsFalse() {
    String responseBody = "completely invalid response";

    ValidationDecision decision = client.parseDecision(responseBody);

    assertThat(decision.isAccepted()).isFalse();
    assertThat(decision.decision()).isEqualTo("REJECT");
  }

  private static String chatCompletionResponse(String content) {
    return new JsonObject()
      .put(
        "choices",
        new JsonArray()
          .add(
            new JsonObject()
              .put(
                "message",
                new JsonObject()
                  .put("role", "assistant")
                  .put("content", content)
              )
          )
      )
      .encode();
  }
}
