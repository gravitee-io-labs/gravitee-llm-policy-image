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

import io.gravitee.policy.api.PolicyConfiguration;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@Builder
public class LlmImagePolicyConfiguration implements PolicyConfiguration {

  /**
   * Vision model endpoint URL (e.g., http://localhost:8000/v1/chat/completions)
   */
  private String visionEndpoint;

  /** Model name to use for vision analysis */
  @Builder.Default
  private String modelName = "qwen3-vl";

  /** Prompt to send with the image for validation (overrides template if set) */
  private String validationPrompt;

  /** Timeout in milliseconds for vision model calls */
  @Builder.Default
  private int timeoutMs = 30000;

  /** List of content categories to reject */
  @Builder.Default
  private List<String> rejectedCategories = List.of(
    "Explicit sexual content or nudity",
    "Graphic violence or gore",
    "Hate symbols or extremist content",
    "Illegal activities",
    "Child exploitation",
    "Spam, scams, or phishing attempts",
    "Personal identifying information (IDs, credit cards, etc.)"
  );

  /** List of content categories to accept */
  @Builder.Default
  private List<String> acceptedCategories = List.of(
    "Safe for general audiences",
    "Professional or educational content",
    "Artistic content without explicit material",
    "General photography, illustrations, diagrams"
  );

  /** Action to take when image validation fails */
  @Builder.Default
  private ViolationMode onViolation = ViolationMode.BLOCK;
}
