package org.llm4s.samples.imagegeneration

import org.llm4s.imagegeneration._
import org.slf4j.LoggerFactory
import java.nio.file.Paths

/**
 * Example demonstrating the Image Generation API for OpenAI.
 *
 * This shows how to:
 * - Generate single and multiple images
 * - Use custom options (size, seed, etc.)
 * - Handle errors gracefully
 * - Save images to disk
 */
object ImageGenerationExample {
  private val logger = LoggerFactory.getLogger(getClass)

  // NOTE: You need to set the OPENAI_API_KEY environment variable to run this example
  private val apiKey = sys.env.getOrElse("OPENAI_API_KEY", "your-api-key")

  def main(args: Array[String]): Unit = {
    logger.info("=== Image Generation API Demo (OpenAI) ===")

    if (apiKey == "your-api-key") {
      logger.warn("Please set OPENAI_API_KEY environment variable to run this example.")
      return
    }

    // Example 1: Basic image generation
    basicExample()

    // Example 2: Advanced options
    advancedExample()

    // Example 3: Multiple images
    multipleImagesExample()

    // Example 4: Error handling
    errorHandlingExample()
  }

  def basicExample(): Unit = {
    logger.info("\n--- Basic Example ---")

    val prompt = "A beautiful sunset over mountains, digital art"

    ImageGeneration.generateWithOpenAI(prompt, apiKey) match {
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
      size = ImageSize.Square1024,
      quality = Some("hd"),
      style = Some("vivid"),
      responseFormat = Some("b64_json")
    )

    val config = OpenAIConfig(
      apiKey = apiKey,
      model = "dall-e-3"
    )

    ImageGeneration.generateImage(prompt, config, options) match {
      case Right(image) =>
        logger.info(s"Generated cyberpunk image with model: ${config.model}")

        val filename = s"cyberpunk_${System.currentTimeMillis()}.png"
        image.saveToFile(Paths.get(filename)).foreach(_ => logger.info(s"Saved: $filename"))

      case Left(error) =>
        logger.error(s"Advanced generation failed: ${error.message}")
    }
  }

  def multipleImagesExample(): Unit = {
    logger.info("\n--- Multiple Images Example ---")

    val prompt = "A cute robot, cartoon style"
    val config = OpenAIConfig(apiKey = apiKey, model = "dall-e-2") // dall-e-2 supports multiple images

    ImageGeneration.generateImages(prompt, 2, config) match {
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

    // This will fail because API key is invalid
    ImageGeneration.generateWithOpenAI(
      "This will fail",
      apiKey = "invalid-key"
    ) match {
      case Right(_) =>
        logger.info("Unexpected success!")

      case Left(error) =>
        error match {
          case AuthenticationError(msg) =>
            logger.info(s"Expected authentication error: $msg")
          case ServiceError(msg, code) =>
            logger.info(s"Expected service error: $msg (code: $code)")
          case UnknownError(throwable) =>
            logger.info(s"Expected connection error: ${throwable.getMessage}")
          case _ =>
            logger.info(s"Other expected error: ${error.message}")
        }
    }

    // Health check example
    val config = OpenAIConfig(apiKey = apiKey)
    ImageGeneration.healthCheck(config) match {
      case Right(status) =>
        logger.info(s"Service status: ${status.status} - ${status.message}")
      case Left(error) =>
        logger.info(
          s"Health check failed as expected (OpenAI doesn't support generic health check yet): ${error.message}"
        )
    }
  }
}
