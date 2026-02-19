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

import io.gravitee.definition.model.v4.Api;
import io.gravitee.definition.model.v4.endpointgroup.Endpoint;
import io.gravitee.definition.model.v4.endpointgroup.EndpointGroup;
import io.gravitee.gateway.api.buffer.Buffer;
import io.gravitee.gateway.reactive.api.ExecutionFailure;
import io.gravitee.gateway.reactive.api.context.http.HttpPlainExecutionContext;
import io.gravitee.gateway.reactive.api.context.http.HttpPlainRequest;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.observers.TestObserver;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.rxjava3.core.Vertx;
import java.util.List;
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

    LlmImagePolicy policy = new TestPolicy(redactConfig(), client);

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
        .getJsonArray("messages")
        .getJsonObject(0)
        .getJsonArray("content")
        .getJsonObject(1);

      assertThat(contentItem.getString("type")).isEqualTo("text");
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

    LlmImagePolicy policy = new TestPolicy(redactConfig(), client);

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
        .getJsonArray("messages")
        .getJsonObject(0)
        .getJsonArray("content");

      assertThat(
        content.getJsonObject(0).getJsonObject("image_url").getString("url")
      )
        .isEqualTo("https://example.com/good.png");
      assertThat(content.getJsonObject(1).getString("type")).isEqualTo("text");
      assertThat(content.getJsonObject(1).getString("text"))
        .isEqualTo("[Image redacted: validation failed]");
    } finally {
      vertx.close();
    }
  }

  private static LlmImagePolicyConfiguration config() {
    return LlmImagePolicyConfiguration.builder().build();
  }

  private static LlmImagePolicyConfiguration redactConfig() {
    return LlmImagePolicyConfiguration
      .builder()
      .onViolation(ViolationMode.REDACT)
      .build();
  }

  private static LlmImagePolicyConfiguration blockConfig() {
    return LlmImagePolicyConfiguration
      .builder()
      .onViolation(ViolationMode.BLOCK)
      .build();
  }

  // ========================================
  // BLOCK MODE TESTS
  // ========================================

  /**
   * Tests that non-ignorable errors (like interrupt signals from interruptWith)
   * propagate to the caller rather than being swallowed. This verifies the fix
   * for the bug where .onErrorComplete() was swallowing all errors.
   */
  @Test
  void blockModeErrorSignalPropagates() {
    VisionModelClient client = mock(VisionModelClient.class);
    when(client.validateImage(any())).thenReturn(Single.just(false));

    LlmImagePolicy policy = new TestPolicy(blockConfig(), client);

    Vertx vertx = Vertx.vertx();
    try {
      HttpPlainExecutionContext ctx = mock(HttpPlainExecutionContext.class);
      HttpPlainRequest request = mock(HttpPlainRequest.class);
      when(ctx.request()).thenReturn(request);
      when(ctx.getComponent(Vertx.class)).thenReturn(vertx);
      when(request.body())
        .thenReturn(Maybe.just(Buffer.buffer(singleImageRequest().encode())));

      // Non-ignorable error should propagate (not an IOException or network error)
      IllegalStateException interruptSignal = new IllegalStateException(
        "Block signal from framework"
      );
      when(ctx.interruptWith(any(ExecutionFailure.class)))
        .thenReturn(Completable.error(interruptSignal));

      TestObserver<Void> observer = policy.onRequest(ctx).test();

      // The error should propagate, not be swallowed
      observer.assertError(interruptSignal);

      // Verify interruptWith was called with correct parameters
      ArgumentCaptor<ExecutionFailure> failureCaptor = ArgumentCaptor.forClass(
        ExecutionFailure.class
      );
      verify(ctx).interruptWith(failureCaptor.capture());
      assertThat(failureCaptor.getValue().statusCode()).isEqualTo(400);
    } finally {
      vertx.close();
    }
  }

  /**
   * Tests that ignorable network errors are swallowed gracefully
   * to allow the request to continue even if vision validation fails.
   */
  @Test
  void networkErrorsAreIgnored() {
    VisionModelClient client = mock(VisionModelClient.class);
    when(client.validateImage(any()))
      .thenReturn(Single.error(new java.io.IOException("Connection refused")));

    LlmImagePolicy policy = new TestPolicy(blockConfig(), client);

    Vertx vertx = Vertx.vertx();
    try {
      HttpPlainExecutionContext ctx = mock(HttpPlainExecutionContext.class);
      HttpPlainRequest request = mock(HttpPlainRequest.class);
      when(ctx.request()).thenReturn(request);
      when(ctx.getComponent(Vertx.class)).thenReturn(vertx);
      when(request.body())
        .thenReturn(Maybe.just(Buffer.buffer(singleImageRequest().encode())));

      TestObserver<Void> observer = policy.onRequest(ctx).test();

      // IOException should be ignored, request completes normally
      observer.assertComplete();
      observer.assertNoErrors();

      // interruptWith should not have been called
      verify(ctx, never()).interruptWith(any(ExecutionFailure.class));
    } finally {
      vertx.close();
    }
  }

  @Test
  void blocksRequestWhenValidationFailsInBlockMode() {
    VisionModelClient client = mock(VisionModelClient.class);
    when(client.validateImage(any())).thenReturn(Single.just(false));

    LlmImagePolicy policy = new TestPolicy(blockConfig(), client);

    Vertx vertx = Vertx.vertx();
    try {
      HttpPlainExecutionContext ctx = mock(HttpPlainExecutionContext.class);
      HttpPlainRequest request = mock(HttpPlainRequest.class);
      when(ctx.request()).thenReturn(request);
      when(ctx.getComponent(Vertx.class)).thenReturn(vertx);
      when(request.body())
        .thenReturn(Maybe.just(Buffer.buffer(singleImageRequest().encode())));
      when(ctx.interruptWith(any(ExecutionFailure.class)))
        .thenReturn(Completable.complete());

      TestObserver<Void> observer = policy.onRequest(ctx).test();
      observer.assertComplete();

      ArgumentCaptor<ExecutionFailure> failureCaptor = ArgumentCaptor.forClass(
        ExecutionFailure.class
      );
      verify(ctx).interruptWith(failureCaptor.capture());

      ExecutionFailure failure = failureCaptor.getValue();
      assertThat(failure.statusCode()).isEqualTo(400);
      assertThat(failure.key()).isEqualTo("IMAGE_VALIDATION_FAILED");
      assertThat(failure.message())
        .isEqualTo("Request blocked: inappropriate image(s) detected");

      verify(request, never()).body(any(Buffer.class));
    } finally {
      vertx.close();
    }
  }

  @Test
  void passesRequestWhenValidationSucceedsInBlockMode() {
    VisionModelClient client = mock(VisionModelClient.class);
    when(client.validateImage(any())).thenReturn(Single.just(true));

    LlmImagePolicy policy = new TestPolicy(blockConfig(), client);

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

      verify(ctx, never()).interruptWith(any(ExecutionFailure.class));
      verify(request, never()).body(any(Buffer.class));
    } finally {
      vertx.close();
    }
  }

  // ========================================
  // ENDPOINT GROUP RESOLUTION TESTS
  // ========================================

  @Test
  void resolvesEndpointFromEndpointGroupWhenConfigured() {
    VisionModelClient client = mock(VisionModelClient.class);
    when(client.validateImage(any())).thenReturn(Single.just(true));

    LlmImagePolicyConfiguration endpointGroupConfig =
      LlmImagePolicyConfiguration
        .builder()
        .llmProxyApiId("my-vision-group")
        .build();

    LlmImagePolicy policy = new RealResolverTestPolicy(
      endpointGroupConfig,
      client
    );

    Vertx vertx = Vertx.vertx();
    try {
      HttpPlainExecutionContext ctx = mock(HttpPlainExecutionContext.class);
      HttpPlainRequest request = mock(HttpPlainRequest.class);
      when(ctx.request()).thenReturn(request);
      when(ctx.getComponent(Vertx.class)).thenReturn(vertx);
      when(request.body())
        .thenReturn(Maybe.just(Buffer.buffer(singleImageRequest().encode())));

      // Set up API definition with an endpoint group
      var epConfig = new JsonObject()
        .put("target", "https://api.openai.com/v1")
        .put(
          "authentication",
          new JsonObject().put("type", "BEARER").put("bearer", "sk-test-token")
        )
        .put(
          "models",
          new JsonArray().add(new JsonObject().put("name", "gpt-4o"))
        )
        .encode();

      var endpoint = Endpoint.builder().name("ep-1").build();
      endpoint.setConfiguration(epConfig);

      var group = EndpointGroup
        .builder()
        .name("my-vision-group")
        .type("llm-proxy")
        .endpoints(List.of(endpoint))
        .build();

      var api = Api.builder().endpointGroups(List.of(group)).build();
      when(ctx.getComponent(Api.class)).thenReturn(api);

      TestObserver<Void> observer = policy.onRequest(ctx).test();
      observer.assertComplete();

      // Verify the vision client was called (endpoint was resolved)
      verify(client).validateImage(any());
      // Verify body was not modified (validation passed)
      verify(request, never()).body(any(Buffer.class));
    } finally {
      vertx.close();
    }
  }

  @Test
  void skipsValidationWhenEndpointGroupNotFound() {
    VisionModelClient client = mock(VisionModelClient.class);

    LlmImagePolicyConfiguration fallbackConfig = LlmImagePolicyConfiguration
      .builder()
      .llmProxyApiId("nonexistent-group")
      .build();

    LlmImagePolicy policy = new RealResolverTestPolicy(fallbackConfig, client);

    Vertx vertx = Vertx.vertx();
    try {
      HttpPlainExecutionContext ctx = mock(HttpPlainExecutionContext.class);
      HttpPlainRequest request = mock(HttpPlainRequest.class);
      when(ctx.request()).thenReturn(request);
      when(ctx.getComponent(Vertx.class)).thenReturn(vertx);
      when(ctx.getComponent(Api.class)).thenReturn(null);
      when(request.body())
        .thenReturn(Maybe.just(Buffer.buffer(singleImageRequest().encode())));

      TestObserver<Void> observer = policy.onRequest(ctx).test();
      observer.assertComplete();

      // No static fallback — validation is skipped entirely
      verify(client, never()).validateImage(any());
    } finally {
      vertx.close();
    }
  }

  private static JsonObject singleImageRequest() {
    return new JsonObject()
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
                      .put(
                        "image_url",
                        new JsonObject()
                          .put("url", "https://example.com/image.png")
                      )
                  )
              )
          )
      );
  }

  private static JsonObject mixedImagesRequest() {
    return new JsonObject()
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
                      .put(
                        "image_url",
                        new JsonObject()
                          .put("url", "https://example.com/good.png")
                      )
                  )
                  .add(
                    new JsonObject()
                      .put("type", "image_url")
                      .put(
                        "image_url",
                        new JsonObject()
                          .put("url", "https://example.com/bad.png")
                      )
                  )
              )
          )
      );
  }

  /** Stubs both resolveEndpointGroup (fixed endpoint) and createClient. */
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
    protected ResolvedEndpoint resolveEndpointGroup(
      io.gravitee.gateway.reactive.api.context.http.HttpPlainExecutionContext ctx
    ) {
      return new ResolvedEndpoint("http://test-endpoint/v1", null, null, null);
    }

    @Override
    protected VisionModelClient createClient(
      Vertx vertx,
      LlmImagePolicyConfiguration effectiveConfig,
      ResolvedEndpoint resolvedEndpoint
    ) {
      return client;
    }
  }

  /** Only stubs createClient; lets real resolveEndpointGroup run. */
  private static class RealResolverTestPolicy extends LlmImagePolicy {

    private final VisionModelClient client;

    private RealResolverTestPolicy(
      LlmImagePolicyConfiguration config,
      VisionModelClient client
    ) {
      super(config);
      this.client = client;
    }

    @Override
    protected VisionModelClient createClient(
      Vertx vertx,
      LlmImagePolicyConfiguration effectiveConfig,
      ResolvedEndpoint resolvedEndpoint
    ) {
      return client;
    }
  }
}
