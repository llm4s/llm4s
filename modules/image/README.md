# llm4s-image

Image generation and image analysis for LLM4S: clients that generate images from a text prompt (and edit them), and
clients that analyse or transform an existing image (vision models, plus a local processor). It is not a chat
client, and it does not host any model: generation and vision calls go to a provider you configure.

## Installation

Artifact: `org.llm4s::llm4s-image` (`name := "llm4s-image"` in `build.sbt`). It depends on `llm4s-core` and
[`llm4s-media`](../media/README.md). See the [installation guide](../../docs/getting-started/installation.md) for
the current version and how to add it.

## What is in it

Image generation (`org.llm4s.imagegeneration`):

- `ImageGeneration`: factory and convenience methods; `ImageGenerationClient`: the client trait
- `OpenAIImageClient` (DALL-E and `gpt-image-1`), `StabilityAIClient`, `StableDiffusionClient` (a Stable Diffusion
  WebUI server), `HuggingFaceClient`, in `org.llm4s.imagegeneration.provider`
- `InstrumentedImageGenerationClient` and `ImagePricingRegistry`: metrics and cost tracking around any client

Image processing (`org.llm4s.imageprocessing`):

- `ImageProcessing`: factory (`openAIVisionClient`, `anthropicVisionClient`, `geminiVisionClient`,
  `localImageProcessor`); `ImageProcessingClient`: the client trait
- `OpenAIVisionClient`, `AnthropicVisionClient`, `GeminiVisionClient` (vision models) and `LocalImageProcessor`
  (no network)

The generation API is also described in
[`imagegeneration/README.md`](src/main/scala/org/llm4s/imagegeneration/README.md), which this page does not repeat.

## Configuration

Configured in code: the module has no `reference.conf` and reads no environment variables itself. Each client takes a
config case class: `OpenAIConfig`, `StabilityAIConfig`, `StableDiffusionConfig` and `HuggingFaceConfig` for
generation (in `ImageGeneration.scala`), and `OpenAIVisionConfig`, `AnthropicVisionConfig`, `GeminiVisionConfig` and
`LocalImageProcessingConfig` for vision (in `ImageProcessingConfig.scala`). Where the key comes from is up to your
application: the [sample](../samples/src/main/scala/org/llm4s/samples/imagegeneration/ImageGenerationExample.scala)
reuses a chat provider section loaded through `Llm4sConfig`.

What needs what:

- OpenAI, Stability AI, HuggingFace, and the OpenAI, Anthropic and Gemini vision clients need an API key.
- `StableDiffusionClient` needs a running Stable Diffusion WebUI server (default `http://localhost:7860`); the key
  is optional.
- `LocalImageProcessor` needs neither a key nor a server.

## Tests

```bash
sbt image/test
```

The suites use stub HTTP servers, fixtures and `LocalImageProcessor`, so none needs an API key, and none is skipped.
The vision client suites in `modules/it` (`@Local` and `@Cloud` tiers) are not part of `image/test`.

## More

- User guide: [docs/guide/image-generation.md](../../docs/guide/image-generation.md)
- Sample: [`ImageGenerationExample.scala`](../samples/src/main/scala/org/llm4s/samples/imagegeneration/ImageGenerationExample.scala)
