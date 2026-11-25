---
layout: page
title: Image Processing and Vision
description: "Resize, crop and convert images locally with llm4s-image, or ask a vision model about them from Scala: clients, operations, errors and the save path check."
search_title: "Image processing and vision in Scala with LLM4S"
parent: Core
nav_order: 116.5
grand_parent: User Guide
---

# Image Processing and Vision
{: .no_toc }

Transform an image file on your own machine, or send it to a vision model and ask about it.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## The module

```sbt
libraryDependencies ++= Seq(
  "org.llm4s" %% "llm4s-core"  % "{{ site.data.project.latest_release }}",
  "org.llm4s" %% "llm4s-image" % "{{ site.data.project.latest_release }}"
)
```

The package is `org.llm4s.imageprocessing`. For creating new images from a prompt, see
[Image Generation](image-generation).

`ImageProcessingClient` works on a file path:

| Method | Returns |
|--------|---------|
| `analyzeImage(imagePath, prompt)` | `Either[LLMError, ImageAnalysisResult]`; `prompt` is an optional `String` |
| `analyzeImageAsync(imagePath, prompt)` | A `Future` of the same `Either`; needs an implicit `ExecutionContext` |
| `preprocessImage(imagePath, operations)` | `Either[LLMError, ProcessedImage]` |
| `convertFormat(imagePath, targetFormat)` | `Either[LLMError, ProcessedImage]` |
| `resizeImage(imagePath, width, height, maintainAspectRatio)` | `Either[LLMError, ProcessedImage]`; the last argument defaults to `true` |

---

## Choosing a client

| Factory on `ImageProcessing` | API key | What `analyzeImage` does |
|------------------------------|---------|--------------------------|
| `localImageProcessor()` | None | Reports basic facts: dimensions, orientation, colour or greyscale, transparency |
| `openAIVisionClient(apiKey, model)` | OpenAI | Sends the image and prompt to an OpenAI model |
| `anthropicVisionClient(apiKey, model)` | Anthropic | Sends the image and prompt to a Claude model |
| `geminiVisionClient(apiKey, model)` | Google | Sends the image and prompt to a Gemini model |

`preprocessImage`, `convertFormat` and `resizeImage` run locally on every client: the vision clients delegate them to
the local processor, so they make no API call.

```scala
import org.llm4s.imageprocessing.{ ImageProcessing, ImageProcessingClient }

def localClient(): ImageProcessingClient =
  ImageProcessing.localImageProcessor()

def openAIClient(apiKey: String, model: String): ImageProcessingClient =
  ImageProcessing.openAIVisionClient(apiKey, model)

def anthropicClient(apiKey: String, model: String): ImageProcessingClient =
  ImageProcessing.anthropicVisionClient(apiKey, model)

def geminiClient(apiKey: String, model: String): ImageProcessingClient =
  ImageProcessing.geminiVisionClient(apiKey, model)
```

The factories take the key as an argument, so read it from your application's configuration and pass it in.

---

## Local operations

This example needs no API key. It fits an image inside 512 x 512 pixels, makes it greyscale and saves it:

```scala
import org.llm4s.error.LLMError
import org.llm4s.imageprocessing.{ ImageOperation, ImageProcessing, ProcessedImage }
import java.nio.file.Paths

def makeThumbnail(inputPath: String): Either[LLMError, ProcessedImage] = {
  val processor  = ImageProcessing.localImageProcessor()
  val operations = List(ImageOperation.Resize(width = 512, height = 512), ImageOperation.Grayscale)

  for {
    thumbnail <- processor.preprocessImage(inputPath, operations)
    _         <- thumbnail.saveToFile(Paths.get("thumbnail.png"))
  } yield thumbnail
}
```

Operations run in list order, each on the result of the one before, and the first one that is refused stops the chain.
`preprocessImage` and `resizeImage` always return PNG, whatever the input format was. A `ProcessedImage` holds the
encoded bytes (`data`), `format`, `width`, `height` and `metadata`, which records the original path and the operations.

To change the format, or to resize to exact dimensions:

```scala
import org.llm4s.error.LLMError
import org.llm4s.imageprocessing.{ ImageProcessing, ProcessedImage }
import org.llm4s.media.MediaType

def convertAndResize(inputPath: String): Either[LLMError, (ProcessedImage, ProcessedImage)] = {
  val processor = ImageProcessing.localImageProcessor()

  for {
    jpeg    <- processor.convertFormat(inputPath, MediaType.Jpeg)
    resized <- processor.resizeImage(inputPath, width = 800, height = 600, maintainAspectRatio = false)
  } yield (jpeg, resized)
}
```

Both methods read the file at `inputPath`, so the resized image above is not made from the converted one.

Images are read and written with the JDK's `ImageIO`, which limits the formats:

- A `convertFormat` to `MediaType.WebP` writes **PNG bytes**, although the result's `format` says `WebP`.
- The JDK has no WebP reader, so without an `ImageIO` plugin for it a WebP input is an `InvalidInputError`.
- The processor does not check whether `ImageIO` could write the image. Where it has no writer for it, for example
  JPEG from an image with transparency, the result is a `Right` with empty `data`; check `sizeInBytes`.

The local processor also implements `analyzeImage`. It does not call a model: the description is built from the
image's properties, and a prompt you pass is only appended to that text.

```scala
import org.llm4s.imageprocessing.ImageProcessing

def describeLocally(inputPath: String): Unit = {
  val processor = ImageProcessing.localImageProcessor()

  processor.analyzeImage(inputPath) match {
    case Right(analysis) => println(s"${analysis.description} (tags: ${analysis.tags.mkString(", ")})")
    case Left(error)     => println(error.message)
  }
}
```

---

## Analysing an image with a vision model

You need an API key for the provider and a model that accepts image input. Pass the model explicitly. The factory
methods have defaults (`gpt-4-vision-preview`, `claude-3-sonnet-20240229` and `gemini-3.6-flash`), but they are fixed
strings in the library, not recommendations: check the provider's current model list. The OpenAI client sends the
reply limit as `max_tokens`, so the OpenAI model must accept that parameter.

```scala
import org.llm4s.error.LLMError
import org.llm4s.imageprocessing.{ ImageAnalysisResult, ImageProcessing }

def askAboutImage(apiKey: String, model: String, imagePath: String): Either[LLMError, ImageAnalysisResult] = {
  val client = ImageProcessing.openAIVisionClient(apiKey = apiKey, model = model)

  client.analyzeImage(imagePath, Some("What is in this picture? Answer in two sentences."))
}
```

Swap in `anthropicVisionClient` or `geminiVisionClient` for the other providers. Without a prompt, the client asks for
a detailed description with the objects, text and people it sees, and tags. The OpenAI and Anthropic clients limit the
reply to 1000 tokens.

The file is sent as it is on disk, not resized, and its media type comes from the file extension (JPEG when the
extension names no image type). To send a smaller image, preprocess it, save it and analyse the saved file.
The client first decodes the file with `ImageIO`, so a file it cannot read (WebP without a plugin) is refused before
any request.

Only `description` is the model's reply. The clients fill in the rest of `ImageAnalysisResult` with simple rules, so do
not treat it as structured output from the model:

| Field | What a vision client puts in it |
|-------|---------------------------------|
| `description` | The text of the model's reply |
| `confidence` | The constant `0.85` (the local processor uses `0.5`) |
| `tags` | Words of the reply that appear in a fixed keyword list |
| `objects` | A fixed set of keywords found anywhere in the reply, each with a placeholder bounding box |
| `emotions` | Always empty |
| `text` | A quotation after `text says`, `reads` or `contains the text` in the reply, lower-cased; otherwise `None` |
| `metadata` | Facts read from the local file |

`analyzeImageAsync` runs the same call in a `Future`:

```scala
import org.llm4s.imageprocessing.ImageProcessingClient
import scala.concurrent.Future

def printDescriptionLater(client: ImageProcessingClient, imagePath: String): Future[Unit] = {
  import scala.concurrent.ExecutionContext.Implicits.global

  client.analyzeImageAsync(imagePath).map(result => println(result.map(_.description)))
}
```

To change the endpoint or the timeouts, build the client from its config class (`OpenAIVisionConfig`,
`AnthropicVisionConfig` or `GeminiVisionConfig`):

```scala
import org.llm4s.imageprocessing.ImageProcessingClient
import org.llm4s.imageprocessing.config.OpenAIVisionConfig
import org.llm4s.imageprocessing.provider.OpenAIVisionClient
import org.llm4s.security.Secret
import scala.concurrent.duration._

def clientForGateway(apiKey: String, model: String, baseUrl: String): ImageProcessingClient = {
  val config = OpenAIVisionConfig(
    apiKey = Secret(apiKey),
    model = model,
    baseUrl = baseUrl,
    requestTimeout = 2.minutes
  )

  new OpenAIVisionClient(config)
}
```

---

## Image operations

The cases of `ImageOperation`, as the local processor applies them:

| Operation | Effect | Refused with `InvalidInputError` when |
|-----------|--------|---------------------------------------|
| `Resize(width, height, maintainAspectRatio = true)` | Fits the image inside `width` x `height` keeping its proportions; with `false`, uses exactly that size | `width` or `height` is 0 or less |
| `Crop(x, y, width, height)` | Keeps the rectangle whose top-left corner is at `x`, `y` | The rectangle is empty or not wholly inside the image |
| `Rotate(degrees)` | Rotates about the centre and resizes the canvas to the rotated bounds; uncovered corners are black | Never |
| `Blur(radius)` | Box blur; the window is at least 3 x 3, even for a radius of 0 | Never |
| `Brightness(level)` | Adds `level` percent of 255 to each colour channel, clamped to 0 to 255; intended range -100 to 100 | Never; the range is not checked |
| `Contrast(level)` | Scales each colour channel about 128; intended range -100 to 100 | Never; the range is not checked |
| `Grayscale` | Converts to an 8-bit greyscale image | Never |
| `Normalize` | Currently does nothing: the image is returned unchanged | Never |

Every operation except `Normalize` drops transparency: `Grayscale` produces a greyscale image, the others an RGB one.

With `maintainAspectRatio`, the computed side is truncated to a whole number. If a very elongated image is fitted into
a very small box, that side can become 0, and the JDK then throws an exception instead of returning a `Left`.

---

## Errors and the save path check

The client methods and `saveToFile` report failures as a `Left` holding an `LLMError`:

| Situation | Error |
|-----------|-------|
| The file does not exist or reading it fails | `ProcessingError` |
| The file is not an image `ImageIO` can decode | `InvalidInputError` |
| A resize or crop with invalid values | `InvalidInputError` |
| A blank API key (OpenAI and Gemini clients) or a blank model name (Gemini client) | `ConfigurationError` |
| The provider answers with a status other than 200 | `ServiceError` |
| A timeout, connection failure or interruption during the request | `TimeoutError`, `NetworkError` or `CancelledError` |
| A Gemini reply with status 200 and no text | `ServiceError` |
| `saveToFile` is given a path that climbs out with `..` | `InvalidInputError` |
| Writing the file fails, for example because the directory does not exist | `ProcessingError` |

The OpenAI and Anthropic clients can return a `Right` for a status 200 reply they cannot read, with the description
`Could not parse response from OpenAI Vision API` (or `Anthropic Vision API`). JSON without the expected `choices`
(or `content`) field is a `ServiceError` with status 200.

```scala
import org.llm4s.error.{ ConfigurationError, InvalidInputError, LLMError, ProcessingError, ServiceError }

def explain(error: LLMError): String =
  error match {
    case e: InvalidInputError  => s"Rejected ${e.field}: ${e.reason}"
    case e: ConfigurationError => s"Fix the configuration: ${e.message}"
    case e: ServiceError       => s"${e.provider} answered HTTP ${e.status}: ${e.message}"
    case e: ProcessingError    => s"The ${e.operation} step failed: ${e.message}"
    case other                 => other.message
  }
```

`ProcessedImage.saveToFile` takes a `java.nio.file.Path`. It normalises the path and refuses it, with the reason
`Path traversal detected`, if the result still starts with `..`:

```scala
import org.llm4s.error.LLMError
import org.llm4s.imageprocessing.ProcessedImage
import java.nio.file.Paths

def saveOutsideWorkingDirectory(image: ProcessedImage): Either[LLMError, Unit] =
  // A Left: the normalised path is ../escaped.png
  image.saveToFile(Paths.get("out/../../escaped.png"))
```

This is not a sandbox: an absolute path is accepted, symbolic links are not resolved, and an existing file is
overwritten. If the path comes from a user, check it against the directory you allow before calling `saveToFile`.

---

## See also

- API reference: {% include api.html t="org.llm4s.imageprocessing.ImageProcessingClient" %},
  {% include api.html t="org.llm4s.imageprocessing.ImageProcessing$" %},
  {% include api.html t="org.llm4s.imageprocessing.ImageOperation" %} and
  {% include api.html t="org.llm4s.imageprocessing.ProcessedImage" %}
- [Image Generation](image-generation) - Create and edit images from a prompt
- [Multimodal Content](multimodal) - Send images to a chat model
- [Media Types](media-types) - The `ImageMediaType` values `convertFormat` takes
