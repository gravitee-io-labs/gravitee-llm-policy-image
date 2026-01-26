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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.gravitee.gateway.api.buffer.Buffer;
import io.gravitee.gateway.reactive.api.context.http.HttpPlainExecutionContext;
import io.gravitee.gateway.reactive.api.context.http.HttpPlainRequest;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.observers.TestObserver;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.rxjava3.core.Vertx;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class LlmImagePolicyTest {

  @Test
  void passesThroughWhenValidationSucceeds() {
    VisionModelClient client = mock(VisionModelClient.class);
    when(client.validateImage(any())).thenReturn(Single.just(true));

    LlmImagePolicy policy = new TestPolicy(config(), client);

    Vertx vertx = Vertx.vertx();
    try {
      HttpPlainExecutionContext ctx = mock(HttpPlainExecutionContext.class);
      HttpPlainRequest request = mock(HttpPlainRequest.class);
      when(ctx.request()).thenReturn(request);
      when(ctx.getComponent(Vertx.class)).thenReturn(vertx);
      when(request.body())
        .thenReturn(Maybe.just(Buffer.buffer(singleImageRequest().encode())));

      TestObserver<Void> observer = policy.onRequest(ctx).test();
      observer.assertComplete();

      verify(request, never()).body(any(Buffer.class));
    } finally {
      vertx.close();
    }
  }

  @Test
  void redactsWhenValidationFails() {
    VisionModelClient client = mock(VisionModelClient.class);
    when(client.validateImage(any())).thenReturn(Single.just(false));

    LlmImagePolicy policy = new TestPolicy(config(), client);

    Vertx vertx = Vertx.vertx();
    try {
      HttpPlainExecutionContext ctx = mock(HttpPlainExecutionContext.class);
      HttpPlainRequest request = mock(HttpPlainRequest.class);
      when(ctx.request()).thenReturn(request);
      when(ctx.getComponent(Vertx.class)).thenReturn(vertx);
      when(request.body())
        .thenReturn(Maybe.just(Buffer.buffer(singleImageRequest().encode())));

      TestObserver<Void> observer = policy.onRequest(ctx).test();
      observer.assertComplete();

      ArgumentCaptor<Buffer> bodyCaptor = ArgumentCaptor.forClass(Buffer.class);
      verify(request).body(bodyCaptor.capture());

      JsonObject modified = new JsonObject(bodyCaptor.getValue().toString());
      JsonObject contentItem = modified
        .getJsonArray("input")
        .getJsonObject(0)
        .getJsonArray("content")
        .getJsonObject(1);

      assertThat(contentItem.getString("type")).isEqualTo("input_text");
      assertThat(contentItem.getString("text"))
        .isEqualTo("[Image redacted: validation failed]");
    } finally {
      vertx.close();
    }
  }

  @Test
  void redactsOnlyInvalidImages() {
    VisionModelClient client = mock(VisionModelClient.class);
    when(client.validateImage(any()))
      .thenAnswer(invocation -> {
        ImageContent image = invocation.getArgument(0);
        return Single.just(!image.imageUrl().contains("bad"));
      });

    LlmImagePolicy policy = new TestPolicy(config(), client);

    Vertx vertx = Vertx.vertx();
    try {
      HttpPlainExecutionContext ctx = mock(HttpPlainExecutionContext.class);
      HttpPlainRequest request = mock(HttpPlainRequest.class);
      when(ctx.request()).thenReturn(request);
      when(ctx.getComponent(Vertx.class)).thenReturn(vertx);
      when(request.body())
        .thenReturn(Maybe.just(Buffer.buffer(mixedImagesRequest().encode())));

      TestObserver<Void> observer = policy.onRequest(ctx).test();
      observer.assertComplete();

      ArgumentCaptor<Buffer> bodyCaptor = ArgumentCaptor.forClass(Buffer.class);
      verify(request).body(bodyCaptor.capture());

      JsonObject modified = new JsonObject(bodyCaptor.getValue().toString());
      JsonArray content = modified
        .getJsonArray("input")
        .getJsonObject(0)
        .getJsonArray("content");

      assertThat(content.getJsonObject(0).getString("image_url"))
        .isEqualTo("https://example.com/good.png");
      assertThat(content.getJsonObject(1).getString("type"))
        .isEqualTo("input_text");
      assertThat(content.getJsonObject(1).getString("text"))
        .isEqualTo("[Image redacted: validation failed]");
    } finally {
      vertx.close();
    }
  }

  private static LlmImagePolicyConfiguration config() {
    return LlmImagePolicyConfiguration
      .builder()
      .visionEndpoint("http://localhost:8000/v1/chat/completions")
      .build();
  }

  private static JsonObject singleImageRequest() {
    return new JsonObject()
      .put(
        "input",
        new JsonArray()
          .add(
            new JsonObject()
              .put(
                "content",
                new JsonArray()
                  .add(
                    new JsonObject()
                      .put("type", "input_text")
                      .put("text", "hello")
                  )
                  .add(
                    new JsonObject()
                      .put("type", "input_image")
                      .put("image_url", "https://example.com/image.png")
                  )
              )
          )
      );
  }

  private static JsonObject mixedImagesRequest() {
    return new JsonObject()
      .put(
        "input",
        new JsonArray()
          .add(
            new JsonObject()
              .put(
                "content",
                new JsonArray()
                  .add(
                    new JsonObject()
                      .put("type", "input_image")
                      .put("image_url", "https://example.com/good.png")
                  )
                  .add(
                    new JsonObject()
                      .put("type", "input_image")
                      .put("image_url", "https://example.com/bad.png")
                  )
              )
          )
      );
  }

  private static class TestPolicy extends LlmImagePolicy {

    private final VisionModelClient client;

    private TestPolicy(
      LlmImagePolicyConfiguration config,
      VisionModelClient client
    ) {
      super(config);
      this.client = client;
    }

    @Override
    protected VisionModelClient createClient(
      Vertx vertx,
      LlmImagePolicyConfiguration effectiveConfig
    ) {
      return client;
    }
  }
}
