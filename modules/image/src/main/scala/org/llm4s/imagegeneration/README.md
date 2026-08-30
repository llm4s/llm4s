# Image Generation API

A type-safe Scala API for generating images using Stable Diffusion and other AI models.

## Quick Start

```scala
import org.llm4s.imagegeneration._

// Simple generation
val result = ImageGeneration.generateWithStableDiffusion("A sunset over mountains")
result match {
  case Right(image) => 
    println(s"Generated ${image.size.description} image")
    image.saveToFile(Paths.get("sunset.png"))
  case Left(error) => 
    println(s"Error: ${error.message}")
}

// Advanced options
val options = ImageGenerationOptions(
  size = ImageSize.Landscape768x512,
  seed = Some(42),
  guidanceScale = 8.0,
  negativePrompt = Some("blurry, low quality")
)

val config = StableDiffusionConfig(baseUrl = "http://localhost:7860")
ImageGeneration.generateImage("A cyberpunk city", config, options)
```

## Features

- **Type-safe**: Strong typing for all options and responses
- **Multiple formats**: PNG and JPEG support
- **Error handling**: Comprehensive error types for robust applications
- **Flexible sizing**: Predefined image sizes (512x512, 1024x1024, etc.)
- **Configurable**: Control inference steps, guidance scale, seeds, and more
- **File I/O**: Built-in support for saving images to disk

## API Structure

- `ImageGeneration` - Main factory object with convenience methods
- `ImageGenerationClient` - Core trait for different providers
- `StableDiffusionClient` - Implementation for Stable Diffusion WebUI API
- `GeneratedImage` - Container for image data with save functionality
- `ImageGenerationOptions` - Type-safe configuration for generation parameters

## Testing

Run the example:
```scala
sbt "runMain org.llm4s.imagegeneration.examples.ImageGenerationExample"
```

Run tests:
```bash
sbt test
```

## Requirements

- Stable Diffusion WebUI running on `http://localhost:7860` (default)
- Or configure a different base URL in `StableDiffusionConfig`

## Architecture

This implementation follows the same patterns as the existing LLM API:
- Provider pattern for different image generation services
- Either-based error handling
- Immutable case classes for configuration
- Factory pattern for easy client creation 