package org.llm4s.imagegeneration.examples

import org.llm4s.imagegeneration._
import org.slf4j.LoggerFactory

import java.nio.file.Paths
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration

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
  }

  def basicExample(): Unit = {
    logger.info("\n--- Basic Example ---")

    val prompt = "A beautiful sunset over mountains, digital art"

    val result = ImageGeneration.generateWithStableDiffusion(prompt).map {
      case Right(image) =>
        logger.info(s"✓ Generated image: ${image.size.description}")

        // Save to file
        val outputPath = Paths.get("sunset.png")
        image.saveToFile(outputPath).foreach(savedImage => logger.info(s"✓ Saved to: ${savedImage.filePath.get}"))

      case Left(error) =>
        logger.error(s"✗ Generation failed: ${error.message}")
    }

    Await.result(result, Duration.Inf)
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

    val result = ImageGeneration.generateImage(prompt, config, options).map {
      case Right(image) =>
        logger.info(s"✓ Generated cyberpunk image with seed: ${image.seed.get}")

        val filename = s"cyberpunk_${image.seed.get}.png"
        image.saveToFile(Paths.get(filename)).foreach(_ => logger.info(s"✓ Saved: $filename"))

      case Left(error) =>
        logger.error(s"✗ Advanced generation failed: ${error.message}")
    }

    Await.result(result, Duration.Inf)
  }

  def multipleImagesExample(): Unit = {
    logger.info("\n--- Multiple Images Example ---")

    val prompt = "A cute robot, cartoon style"
    val config = StableDiffusionConfig()

    val result = ImageGeneration.generateImages(prompt, 3, config).map {
      case Right(images) =>
        logger.info(s"✓ Generated ${images.length} robot images")

        images.zipWithIndex.foreach { case (image, index) =>
          val filename = s"robot_${index + 1}.png"
          image.saveToFile(Paths.get(filename)).foreach(_ => logger.info(s"✓ Saved: $filename"))
        }

      case Left(error) =>
        logger.error(s"✗ Multiple generation failed: ${error.message}")
    }

    Await.result(result, Duration.Inf)
  }

  def errorHandlingExample(): Unit = {
    logger.info("\n--- Error Handling Example ---")

    // This will fail because there's no server at this address
    val result1 = ImageGeneration
      .generateWithStableDiffusion(
        "This will fail",
        baseUrl = "http://localhost:99999"
      )
      .map {
        case Right(_) =>
          logger.info("Unexpected success!")

        case Left(error) =>
          error match {
            case ServiceError(msg, code) =>
              logger.info(s"✓ Expected service error: $msg (code: $code)")
            case UnknownError(throwable) =>
              logger.info(s"✓ Expected connection error: ${throwable.getMessage}")
            case _ =>
              logger.info(s"✓ Other expected error: ${error.message}")
          }
      }

    Await.result(result1, Duration.Inf)

    // Health check example
    val config = StableDiffusionConfig()
    ImageGeneration.healthCheck(config) match {
      case Right(status) =>
        logger.info(s"✓ Service status: ${status.status} - ${status.message}")
      case Left(error) =>
        logger.info(s"✓ Health check failed as expected: ${error.message}")
    }
  }
}
