---
layout: page
title: Image Generation
parent: User Guide
nav_order: 8
---

# Image Generation with LLM4S

The LLM4S library provides a powerful and flexible API for generating images using different providers like Stable Diffusion (via a local WebUI) and the Hugging Face Inference API.

---

## Core Concepts

The API is centered around the `ImageGenerationClient` trait, which defines the common interface for interacting with different image generation services. You can get a pre-configured client using the `ImageGeneration` factory object.

Key models include:
- `ImageGenerationConfig`: A trait for provider-specific configurations (`StableDiffusionConfig`, `HuggingFaceConfig`).
- `ImageGenerationOptions`: A class to control generation parameters like image size, seed, and negative prompts.
- `GeneratedImage`: A case class representing the generated image, including its data and metadata.

---

## Using the Stable Diffusion Client

This client connects to an instance of the [AUTOMATIC1111/stable-diffusion-webui](https://github.com/AUTOMATIC1111/stable-diffusion-webui). You must have this running locally or on a remote server.

### Setup

First, ensure your `build.sbt` includes the LLM4S dependency.

```scala
libraryDependencies += "org.llm4s" %% "llm4s-core" % "0.1.0-SNAPSHOT"
```

### Example Usage

Here's how to create a client and generate an image:

```scala
import org.llm4s.imagegeneration._
import java.nio.file.Paths

// 1. Create a client for a local Stable Diffusion server
val sdClient = ImageGeneration.stableDiffusionClient(
  baseUrl = "http://localhost:7860" // Default URL
)

// 2. Define a prompt and generation options
val prompt = "A photorealistic portrait of a majestic lion"
val options = ImageGenerationOptions(
  size = ImageSize.Square512,
  negativePrompt = Some("cartoon, drawing, sketch, blurry")
)

// 3. Generate the image
println("Generating image with Stable Diffusion...")
sdClient.generateImage(prompt, options) match {
  case Right(image) =>
    println("Image generated successfully!")
    // Save the image
    val path = Paths.get("stable_diffusion_lion.png")
    image.saveToFile(path)
    println(s"Image saved to ${path.toAbsolutePath}")

  case Left(error) =>
    println(s"Error generating image: ${error.message}")
}
```

---

## Using the Hugging Face Client

This client uses the Hugging Face Inference API, which requires an API token.

### Setup

You will need a Hugging Face account and an API token with write permissions.

### Example Usage

The process is very similar. The main difference is the configuration.

```scala
import org.llm4s.imagegeneration._
import java.nio.file.Paths

// 1. Get your API token (it's best to use an environment variable)
val hfApiKey = sys.env.getOrElse("HF_API_TOKEN", "your_hf_api_token_here")

if (hfApiKey == "your_hf_api_token_here") {
  println("Please set the HF_API_TOKEN environment variable.")
} else {
  // 2. Create a Hugging Face client
  val hfClient = ImageGeneration.huggingFaceClient(
    apiKey = hfApiKey,
    model = "runwayml/stable-diffusion-v1-5" // You can choose other models
  )

  // 3. Define a prompt
  val prompt = "A cute robot reading a book, sci-fi concept art"

  // 4. Generate the image
  println("Generating image with Hugging Face...")
  hfClient.generateImage(prompt) match {
    case Right(image) =>
      println("Image generated successfully!")
      val path = Paths.get("hugging_face_robot.png")
      image.saveToFile(path)
      println(s"Image saved to ${path.toAbsolutePath}")

    case Left(error) =>
      println(s"Error generating image: ${error.message}")
  }
}
```

---

## Customizing Generation

The `ImageGenerationOptions` class allows you to fine-tune the generation process:

| Option | Type | Description |
|--------|------|-------------|
| `size` | `ImageSize` | The dimensions of the output image (e.g., `ImageSize.Square512`) |
| `format` | `ImageFormat` | The image format (`ImageFormat.PNG` or `ImageFormat.JPEG`) |
| `seed` | `Option[Long]` | A specific seed for reproducible results |
| `guidanceScale` | `Double` | How strictly the model should follow the prompt |
| `inferenceSteps` | `Int` | The number of steps in the diffusion process |
| `negativePrompt` | `Option[String]` | A prompt describing what you *don't* want to see |

---

## See Also

- [Examples](/examples/) - Working code examples
- [API Reference](/api/llm-client) - Complete API documentation
