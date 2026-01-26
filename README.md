# Gravitee Policy :: LLM Image

Gravitee policy that validates images in OpenAI Responses-style requests using a backend vision model (for example, Qwen3-VL on VLLM). Images that fail validation are redacted before forwarding the request.

## Description

This policy inspects the incoming request body, extracts `input_image` items from the OpenAI Responses format, and sends each image to a configured vision endpoint along with a short validation prompt. If the vision endpoint responds with a non-200 status (or the call fails), the image is replaced with a placeholder text message:

```
[Image redacted: validation failed]
```

## Configuration

| Property | Type | Default | Description |
| --- | --- | --- | --- |
| `visionEndpoint` | string | - | URL of the vision model API (for example, `http://localhost:8000/v1/chat/completions`). Primary configuration source. |
| `modelName` | string | `qwen3-vl` | Model name sent to the vision endpoint. |
| `validationPrompt` | string | `Describe this image in a few words.` | Prompt sent with each image for validation. |
| `timeoutMs` | integer | `30000` | Timeout for vision model API calls in milliseconds. |

Fallback configuration: if `visionEndpoint` is not set in the policy configuration, the policy will use the `GRAVITEE_LLM_IMAGE_VISION_ENDPOINT` environment variable.

## OpenAI Responses Format Support

The policy expects the request body to follow the OpenAI Responses format with an `input` array containing `content` items. It supports both URL-based images and base64 data URLs.

Example input:

```json
{
  "input": [
    {
      "role": "user",
      "content": [
        {"type": "input_text", "text": "Describe this"},
        {"type": "input_image", "image_url": "https://example.com/image.png"}
      ]
    }
  ]
}
```

The `image_url` field may also be an object with a `url` field:

```json
{"type": "input_image", "image_url": {"url": "data:image/png;base64,..."}}
```

## Example

Request body before validation:

```json
{
  "input": [
    {
      "role": "user",
      "content": [
        {"type": "input_text", "text": "What is in this image?"},
        {"type": "input_image", "image_url": "https://example.com/image.png"}
      ]
    }
  ]
}
```

If validation fails, the request body is forwarded as:

```json
{
  "input": [
    {
      "role": "user",
      "content": [
        {"type": "input_text", "text": "What is in this image?"},
        {"type": "input_text", "text": "[Image redacted: validation failed]"}
      ]
    }
  ]
}
```

## Tests

```bash
mvn clean test
mvn clean package
```

## Compatibility

| Plugin version | APIM version |
| -------------- | ------------ |
| 1.x            | 4.x          |
