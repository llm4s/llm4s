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

`llm4s-image` generates images with OpenAI (GPT Image and DALL-E), Stability AI, the Hugging Face Inference API, or a
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

`model` is `gpt-image-1` (and its successors), `dall-e-3` or `dall-e-2` (the default). OpenAI has scheduled DALL-E
for removal; the client logs a warning naming the GPT Image models to move to.

```scala
import org.llm4s.imagegeneration._
import java.nio.file.Paths

def drawLighthouse(apiKey: String): Unit = {
  val result = for {
    client <- ImageGeneration.openAIClient(apiKey, model = "gpt-image-1")
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

The Hugging Face Inference API needs an access token. The default model is
`stabilityai/stable-diffusion-xl-base-1.0`.

> **Checked 2026-11-24: do not rely on this client as shipped.** It posts to
> `https://api-inference.huggingface.co/models/<model>`. That host name did not resolve on that date, and Hugging
> Face's [Inference Providers documentation](https://huggingface.co/docs/inference-providers/index) names only
> `https://router.huggingface.co`. The default model's
> [page](https://huggingface.co/stabilityai/stable-diffusion-xl-base-1.0) is still on the Hub, neither gated nor
> disabled, but the Hub lists it as served for text-to-image by a third-party provider, not by Hugging Face's own
> `hf-inference`. The base URL is not configurable.

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

Image generation records metrics through `InstrumentedImageGenerationClient` and a `MetricsCollector`; see
[Monitoring](observability/#metrics).

---

## See Also

- [Examples](/examples/) - Working code examples
- [Multimodal Content](multimodal) - Send images to a chat model
- [Data Protection](data-protection) - Where image prompts are sent
