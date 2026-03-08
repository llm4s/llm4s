package org.llm4s.samples.imagegeneration

import org.llm4s.imagegeneration._
import org.slf4j.LoggerFactory
import java.nio.file.Paths

/**
 * Example demonstrating the Image Generation API for Stable Diffusion.
 *
 * This shows how to:
 * - Generate single and multiple images
 * - Use custom options (size, seed, etc.)
 * - Handle errors gracefully
 * - Save images to disk
 */
object ImageGenerationExample {
  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    logger.info("=== Image Generation API Demo ===")

    // Example 1: Basic image generation
    basicExample()

    // Example 2: Advanced options
    advancedExample()

    // Example 3: Multiple images
    multipleImagesExample()

    // Example 4: Error handling
    errorHandlingExample()

    // Example 5: Image editing / inpainting
    imageEditingExample()

    // Example 6: GPT Image models (opt-in via explicit model)
    openAIGptImageExample()
  }

  def basicExample(): Unit = {
    logger.info("\n--- Basic Example ---")

    val prompt = "A beautiful sunset over mountains, digital art"

    ImageGeneration.generateWithStableDiffusion(prompt) match {
      case Right(image) =>
        logger.info(s"Generated image: ${image.size.description}")

        // Save to file
        val outputPath = Paths.get("sunset.png")
        image.saveToFile(outputPath).foreach(savedImage => logger.info(s"Saved to: ${savedImage.filePath.get}"))

      case Left(error) =>
        logger.error(s"Generation failed: ${error.message}")
    }
  }

  def advancedExample(): Unit = {
    logger.info("\n--- Advanced Example ---")

    val prompt = "A cyberpunk city at night with neon lights"
    val options = ImageGenerationOptions(
      size = ImageSize.Landscape768x512,
      seed = Some(42), // Reproducible results
      guidanceScale = 8.0,
      inferenceSteps = 30,
      negativePrompt = Some("blurry, low quality")
    )

    val config = StableDiffusionConfig(
      baseUrl = "http://localhost:7860",
      timeout = 120000
    )

    ImageGeneration.generateImage(prompt, config, options) match {
      case Right(image) =>
        logger.info(s"Generated cyberpunk image with seed: ${image.seed.get}")

        val filename = s"cyberpunk_${image.seed.get}.png"
        image.saveToFile(Paths.get(filename)).foreach(_ => logger.info(s"Saved: $filename"))

      case Left(error) =>
        logger.error(s"Advanced generation failed: ${error.message}")
    }
  }

  def multipleImagesExample(): Unit = {
    logger.info("\n--- Multiple Images Example ---")

    val prompt = "A cute robot, cartoon style"
    val config = StableDiffusionConfig()

    ImageGeneration.generateImages(prompt, 3, config) match {
      case Right(images) =>
        logger.info(s"Generated ${images.length} robot images")

        images.zipWithIndex.foreach { case (image, index) =>
          val filename = s"robot_${index + 1}.png"
          image.saveToFile(Paths.get(filename)).foreach(_ => logger.info(s"Saved: $filename"))
        }

      case Left(error) =>
        logger.error(s"Multiple generation failed: ${error.message}")
    }
  }

  def errorHandlingExample(): Unit = {
    logger.info("\n--- Error Handling Example ---")

    // This will fail because there's no server at this address
    ImageGeneration.generateWithStableDiffusion(
      "This will fail",
      baseUrl = "http://localhost:99999"
    ) match {
      case Right(_) =>
        logger.info("Unexpected success!")

      case Left(error) =>
        error match {
          case ServiceError(msg, code) =>
            logger.info(s"Expected service error: $msg (code: $code)")
          case UnknownError(throwable) =>
            logger.info(s"Expected connection error: ${throwable.getMessage}")
          case _ =>
            logger.info(s"Other expected error: ${error.message}")
        }
    }

    // Health check example
    val config = StableDiffusionConfig()
    ImageGeneration.healthCheck(config) match {
      case Right(status) =>
        logger.info(s"Service status: ${status.status} - ${status.message}")
      case Left(error) =>
        logger.info(s"Health check failed as expected: ${error.message}")
    }
  }

  def imageEditingExample(): Unit = {
    logger.info("\n--- Image Editing Example ---")

    val sourceImage = Paths.get("input.png")
    val maskImage   = Paths.get("mask.png")
    val config      = StableDiffusionConfig()

    ImageGeneration.editImage(
      imagePath = sourceImage,
      prompt = "replace the sky with a dramatic sunset",
      maskPath = Some(maskImage),
      config = config,
      options = ImageEditOptions(
        size = Some(ImageSize.Square512),
        n = 1
      )
    ) match {
      case Right(editedImages) if editedImages.nonEmpty =>
        val output = Paths.get("edited_output.png")
        editedImages.head.saveToFile(output).foreach(_ => logger.info(s"Edited image saved to: $output"))
      case Right(_) =>
        logger.info("Image editing returned no images")
      case Left(error) =>
        logger.info(s"Image editing failed (expected if input files are missing): ${error.message}")
    }
  }

  def openAIGptImageExample(): Unit = {
    logger.info("\n--- OpenAI GPT Image Example (Opt-In) ---")

    val maybeApiKey = sys.env.get("OPENAI_API_KEY")
    maybeApiKey match {
      case None =>
        logger.info("Skipping OpenAI GPT Image example (OPENAI_API_KEY not set)")
      case Some(apiKey) =>
        val config = OpenAIConfig(
          apiKey = apiKey,
          model = "gpt-image-1.5"
        )
        val options = ImageGenerationOptions(
          size = ImageSize.Auto,
          responseFormat = Some("url"),
          outputFormat = Some("png"),
          background = Some("auto"),
          outputCompression = Some(80)
        )

        ImageGeneration.generateImage(
          prompt = "A studio product photo of a glass bottle with soft daylight",
          config = config,
          options = options
        ) match {
          case Right(image) =>
            logger.info(
              s"OpenAI image generated. URL: ${image.url.getOrElse("n/a")}, format: ${image.format.extension}"
            )
          case Left(error) =>
            logger.info(s"OpenAI generation failed: ${error.message}")
        }
    }
  }
}
