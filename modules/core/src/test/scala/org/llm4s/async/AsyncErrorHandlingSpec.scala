package org.llm4s.async

import org.llm4s.error.LLMError
import org.llm4s.imagegeneration._
import org.llm4s.imageprocessing.{
  ImageAnalysisResult,
  ImageProcessingClient,
  ImageFormat,
  ImageOperation,
  ProcessedImage
}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{ Millis, Seconds, Span }

import scala.concurrent.{ ExecutionContext, Future, blocking }
import scala.concurrent.ExecutionContext.Implicits.global

/**
 * Verifies that async methods return Left(error) inside a successful Future
 * instead of propagating exceptions as failed Futures (issue #678).
 *
 * The default async implementations in ImageProcessingClient and the concrete
 * image generation provider clients wrap synchronous calls in
 * Future { blocking { ... } }.recover { ... } so that thrown exceptions
 * are captured as Left values.
 */
class AsyncErrorHandlingSpec extends AnyFlatSpec with Matchers with ScalaFutures {

  implicit val defaultPatience: PatienceConfig =
    PatienceConfig(timeout = Span(5, Seconds), interval = Span(50, Millis))

  // ========== ImageProcessingClient ==========

  "ImageProcessingClient.analyzeImageAsync" should "return Left(error) when analyzeImage throws" in {
    val client = new ThrowingImageProcessingClient

    val future = client.analyzeImageAsync("test.png")

    whenReady(future)(result => result.isLeft shouldBe true)
  }

  // ========== ImageGenerationClient async pattern ==========

  "Future-wrapped sync call with recover" should "return Left(error) for generateImage" in {
    val client = new ThrowingImageGenerationClient

    val future: Future[Either[ImageGenerationError, GeneratedImage]] =
      Future {
        blocking {
          client.generateImage("a cat")
        }
      }.recover { case ex => Left(UnknownError(ex)) }

    whenReady(future) { result =>
      result.isLeft shouldBe true
      result.swap.toOption.get shouldBe an[UnknownError]
    }
  }

  it should "return Left(error) for generateImages" in {
    val client = new ThrowingImageGenerationClient

    val future: Future[Either[ImageGenerationError, Seq[GeneratedImage]]] =
      Future {
        blocking {
          client.generateImages("a cat", 2)
        }
      }.recover { case ex => Left(UnknownError(ex)) }

    whenReady(future) { result =>
      result.isLeft shouldBe true
      result.swap.toOption.get shouldBe an[UnknownError]
    }
  }

  it should "return Left(error) for editImage" in {
    val client = new ThrowingImageGenerationClient

    val future: Future[Either[ImageGenerationError, Seq[GeneratedImage]]] =
      Future {
        blocking {
          client.editImage(java.nio.file.Paths.get("/fake"), "edit")
        }
      }.recover { case ex => Left(UnknownError(ex)) }

    whenReady(future) { result =>
      result.isLeft shouldBe true
      result.swap.toOption.get shouldBe an[UnknownError]
    }
  }

  it should "not interfere with successful results" in {
    val future: Future[Either[ImageGenerationError, String]] =
      Future {
        blocking {
          Right("success"): Either[ImageGenerationError, String]
        }
      }.recover { case ex => Left(UnknownError(ex)) }

    whenReady(future)(result => result shouldBe Right("success"))
  }
}

/** Stub that always throws from synchronous methods. */
private class ThrowingImageProcessingClient extends ImageProcessingClient {
  override def analyzeImage(
    imagePath: String,
    prompt: Option[String]
  ): Either[LLMError, ImageAnalysisResult] =
    throw new RuntimeException("boom")

  override def preprocessImage(
    imagePath: String,
    operations: List[ImageOperation]
  ): Either[LLMError, ProcessedImage] =
    throw new RuntimeException("boom")

  override def convertFormat(
    imagePath: String,
    targetFormat: ImageFormat
  ): Either[LLMError, ProcessedImage] =
    throw new RuntimeException("boom")

  override def resizeImage(
    imagePath: String,
    width: Int,
    height: Int,
    maintainAspectRatio: Boolean
  ): Either[LLMError, ProcessedImage] =
    throw new RuntimeException("boom")
}

/** Stub that always throws from synchronous methods. */
private class ThrowingImageGenerationClient extends ImageGenerationClient {
  override def generateImage(
    prompt: String,
    options: ImageGenerationOptions
  ): Either[ImageGenerationError, GeneratedImage] =
    throw new RuntimeException("boom")

  override def generateImages(
    prompt: String,
    count: Int,
    options: ImageGenerationOptions
  ): Either[ImageGenerationError, Seq[GeneratedImage]] =
    throw new RuntimeException("boom")

  override def editImage(
    imagePath: java.nio.file.Path,
    prompt: String,
    maskPath: Option[java.nio.file.Path],
    options: ImageEditOptions
  ): Either[ImageGenerationError, Seq[GeneratedImage]] =
    throw new RuntimeException("boom")
}
