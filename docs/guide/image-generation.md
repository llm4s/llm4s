---
layout: page
title: Image Generation
description: "Generate images from Scala with LLM4S: OpenAI GPT Image and DALL-E, Stability AI, Hugging Face or a Stable Diffusion server, with options."
search_title: "Generate images in Scala with LLM4S"
parent: Core
nav_order: 116
grand_parent: User Guide
---

# Image Generation with LLM4S

`llm4s-image` generates images with OpenAI (GPT Image and DALL-E), Stability AI, Hugging Face Inference Providers, or a
Stable Diffusion server you run (the AUTOMATIC1111 web UI).

```sbt
libraryDependencies ++= Seq(
  "org.llm4s" %% "llm4s-core"  % "{{ site.data.project.latest_release }}",
  "org.llm4s" %% "llm4s-image" % "{{ site.data.project.latest_release }}"
)
```

---

## Core Concepts

Everything is in `org.llm4s.imagegeneration`:

- `ImageGenerationClient`: `generateImage(prompt, options)`, `generateImages(prompt, count, options)` and
  `editImage(...)`, each returning `Either[LLMError, ...]`.
- `ImageGeneration`: builds a client. `ImageGeneration.openAIClient`, `stabilityAIClient`, `huggingFaceClient` and
  `stableDiffusionClient` each return `Either[LLMError, ImageGenerationClient]`; `ImageGeneration.client(config)` takes
  a config (`OpenAIConfig`, `StabilityAIConfig`, `HuggingFaceConfig`, `StableDiffusionConfig`).
- `ImageGenerationOptions`: size, format, seed, negative prompt and the provider-specific options below.
- `GeneratedImage`: the image (base64 `data`, `format`, `size`, `prompt`, `seed`), with `saveToFile(path)`.

The library reads no API key for image generation itself: read the key from your application's configuration at its
edge and pass it in.

---

## OpenAI

`model` is a GPT Image model (any id starting `gpt-image`), `dall-e-3` or `dall-e-2` (the default).

> **Checked 2026-11-24: pass a current GPT Image model.** OpenAI's
> [deprecations page](https://developers.openai.com/api/docs/deprecations) lists `dall-e-2` and `dall-e-3` as shut down
> on 2026-05-12 and `gpt-image-1` on 2026-10-23, so the client's default, `dall-e-2`, no longer works, and
> `editImage` still sends `dall-e-2`. Its deprecation warning still names `gpt-image-1` among the models to move to.
> OpenAI's [models page](https://developers.openai.com/api/docs/models) lists `gpt-image-2.5-sunburst` and the
> `-flare` variants as current.

```scala
import org.llm4s.imagegeneration._
import java.nio.file.Paths

def drawLighthouse(apiKey: String): Unit = {
  val result = for {
    client <- ImageGeneration.openAIClient(apiKey, model = "gpt-image-2.5-sunburst")
    image  <- client.generateImage(
                "A watercolour of a lighthouse at dawn",
                ImageGenerationOptions(size = ImageSize.Square1024, quality = Some("high"))
              )
    saved  <- image.saveToFile(Paths.get("lighthouse.png"))
  } yield saved

  result match {
    case Right(image) => println(s"Saved a ${image.size} image")
    case Left(error)  => println(s"Error generating image: ${error.message}")
  }
}
```

---

## Stability AI

```scala
import org.llm4s.imagegeneration._

def drawWithStability(apiKey: String): Either[org.llm4s.error.LLMError, GeneratedImage] =
  for {
    client <- ImageGeneration.stabilityAIClient(apiKey) // model stable-diffusion-xl-1024-v1-0 by default
    image  <- client.generateImage("A cute robot reading a book, sci-fi concept art")
  } yield image
```

---

## Hugging Face

The client calls [Hugging Face Inference Providers](https://huggingface.co/docs/inference-providers/index) through
Hugging Face's own `hf-inference` provider: it posts the documented
[text-to-image request](https://huggingface.co/docs/inference-providers/tasks/text-to-image) (`inputs`, and
`parameters` with `guidance_scale`, `num_inference_steps`, `negative_prompt`, `width`, `height` and `seed`) to
`https://router.huggingface.co/hf-inference/models/<model>` and reads the image back as raw bytes. It needs an access
token with the "Make calls to Inference Providers" permission.

The default model is `stabilityai/stable-diffusion-3-medium-diffusers`, the text-to-image model the Hub
[lists as served by `hf-inference`](https://huggingface.co/models?inference_provider=hf-inference&pipeline_tag=text-to-image)
(checked 2026-11-24). A model that only third-party providers serve (fal, Replicate, WaveSpeed) takes their own request
formats, which this client does not send. `health()` asks the Hub
([`?expand[]=inferenceProviderMapping`](https://huggingface.co/docs/inference-providers/hub-api#get-model-providers))
whether `hf-inference` serves the configured model. `HuggingFaceConfig.baseUrl` overrides the router route, for a
proxy or a test server.

```scala
import org.llm4s.imagegeneration._

def drawWithHuggingFace(token: String): Either[org.llm4s.error.LLMError, GeneratedImage] =
  for {
    client <- ImageGeneration.huggingFaceClient(apiKey = token)
    image  <- client.generateImage("A cute robot reading a book, sci-fi concept art")
  } yield image
```

---

## A Stable Diffusion server

This client connects to the [AUTOMATIC1111 stable-diffusion-webui](https://github.com/AUTOMATIC1111/stable-diffusion-webui)
API, running locally or on a server of yours (`http://localhost:7860` by default; pass `apiKey` if it needs one).

```scala
import org.llm4s.imagegeneration._

val lion = for {
  client <- ImageGeneration.stableDiffusionClient(baseUrl = "http://localhost:7860")
  image  <- client.generateImage(
              "A photorealistic portrait of a majestic lion",
              ImageGenerationOptions(
                size = ImageSize.Square512,
                negativePrompt = Some("cartoon, drawing, sketch, blurry")
              )
            )
} yield image
```

---

## Customizing Generation

`ImageGenerationOptions` holds every option; a provider ignores the ones it has no use for.

| Option | Type | Used by | Description |
|--------|------|---------|-------------|
| `size` | `ImageSize` | all | `Square512` (default), `Square1024`, `Landscape768x512`, `Portrait512x768`, `Landscape1536x1024`, `Portrait1024x1536`, `Auto`, or `Custom(width, height)` |
| `format` | `ImageMediaType` | all | The image format, from `org.llm4s.media` (`MediaType.Png` by default, or `MediaType.Jpeg`) |
| `seed` | `Option[Long]` | Stable Diffusion, Stability AI, Hugging Face | A seed for reproducible results |
| `guidanceScale` | `Double` | Stable Diffusion, Stability AI, Hugging Face | How strictly the model follows the prompt (default 7.5) |
| `inferenceSteps` | `Int` | Stable Diffusion, Stability AI, Hugging Face | Diffusion steps (default 20) |
| `negativePrompt` | `Option[String]` | Stable Diffusion, Stability AI, Hugging Face | What you *don't* want to see |
| `samplerName` | `Option[String]` | Stable Diffusion, Stability AI | The sampler |
| `quality` | `Option[String]` | OpenAI | DALL-E 3: `standard` or `hd`; GPT Image: `low`, `medium`, `high` or `auto` |
| `style` | `Option[String]` | OpenAI DALL-E 3 | `vivid` or `natural` |
| `responseFormat` | `Option[String]` | OpenAI DALL-E | `url` or `b64_json` |
| `outputFormat` | `Option[String]` | OpenAI GPT Image | `png`, `jpeg` or `webp` |
| `background` | `Option[String]` | OpenAI GPT Image | `transparent`, `opaque` or `auto` |
| `outputCompression` | `Option[Int]` | OpenAI GPT Image | 0 to 100, for `jpeg` and `webp` |
| `user` | `Option[String]` | OpenAI | An end-user identifier for abuse monitoring |

Image generation records metrics through `InstrumentedImageGenerationClient` and a `MetricsCollector`. The metrics are
declared in `ImageMetrics` and recorded through the collector's generic `increment` and `observe`, so any backend that
exports generic metrics exports them; see [Monitoring](observability/#metrics) for the series.

---

## See Also

- [Examples](/examples/) - Working code examples
- [Multimodal Content](multimodal) - Send images to a chat model
- [Data Protection](data-protection) - Where image prompts are sent
