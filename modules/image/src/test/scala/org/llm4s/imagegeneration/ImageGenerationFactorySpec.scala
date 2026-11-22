package org.llm4s.imagegeneration

import org.llm4s.error.LLMError
import org.llm4s.imagegeneration.provider.StabilityAIClient
import org.llm4s.security.Secret
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.nio.file.Path
import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.concurrent.duration.*

/**
 * `ImageGeneration`'s factory and convenience methods, the config classes' `toString` (which must hide the key)
 * and defaults, and the default methods of `ImageGenerationClient`. Nothing here sends a request.
 */
class ImageGenerationFactorySpec extends AnyFunSuite with Matchers {

  implicit private val ec: ExecutionContext = ExecutionContext.global

  private def await[A](f: Future[A]): A = Await.result(f, 5.seconds)

  /** A config `client` has no case for: it matches on the config's class, not on its provider. */
  private object UnknownConfig extends ImageGenerationConfig {
    def provider: ImageGenerationProvider = ImageGenerationProvider.DALLE
    def model: String                     = "unknown-model"
  }

  private val unsupported = Left(UnsupportedOperation("Provider DALLE is not supported."))

  /** Implements only the two abstract methods, so every other method is the trait's default. */
  private object MinimalClient extends ImageGenerationClient {
    def generateImage(prompt: String, options: ImageGenerationOptions): Either[LLMError, GeneratedImage] =
      Left(ImageServiceError("unused", 500))
    def generateImages(
      prompt: String,
      count: Int,
      options: ImageGenerationOptions
    ): Either[LLMError, Seq[GeneratedImage]] = Left(ImageServiceError("unused", 500))
  }

  private val image = Path.of("in.png")
  private val mask  = Some(Path.of("mask.png"))

  // ==========================================================================
  // client and the Stability AI factory
  // ==========================================================================

  test("client builds a StabilityAIClient for a StabilityAIConfig") {
    ImageGeneration.client(StabilityAIConfig(apiKey = Secret("k"))) should matchPattern {
      case Right(_: StabilityAIClient) =>
    }
  }

  test("stabilityAIClient builds a StabilityAIClient") {
    ImageGeneration.stabilityAIClient("k") should matchPattern { case Right(_: StabilityAIClient) => }
  }

  test("client refuses a config class it has no case for, naming the provider") {
    ImageGeneration.client(UnknownConfig) shouldBe unsupported
  }

  // ==========================================================================
  // Convenience methods with an unsupported config
  // ==========================================================================

  test("the synchronous convenience methods return client's error for an unsupported config") {
    ImageGeneration.generateImage("a cat", UnknownConfig) shouldBe unsupported
    ImageGeneration.generateImages("a cat", 2, UnknownConfig) shouldBe unsupported
    ImageGeneration.editImage(image, "make it blue", mask, UnknownConfig) shouldBe unsupported
    ImageGeneration.healthCheck(UnknownConfig) shouldBe unsupported
  }

  test("the async convenience methods complete with client's error for an unsupported config") {
    await(ImageGeneration.generateImageAsync("a cat", UnknownConfig)) shouldBe unsupported
    await(ImageGeneration.generateImagesAsync("a cat", 2, UnknownConfig)) shouldBe unsupported
    await(ImageGeneration.editImageAsync(image, "make it blue", mask, UnknownConfig)) shouldBe unsupported
  }

  // ==========================================================================
  // toString hides the key
  // ==========================================================================

  private val secretKey = "sk-image-factory-spec-0123456789"

  test("OpenAIConfig's toString hides the key and shows the model and base URL") {
    val text =
      OpenAIConfig(apiKey = Secret(secretKey), model = "gpt-image-1", baseUrl = "https://openai.example").toString
    (text should not).include(secretKey)
    text should include("apiKey=***")
    text should include("model=gpt-image-1")
    text should include("baseUrl=https://openai.example")
  }

  test("StabilityAIConfig's toString hides the key and shows the model and base URL") {
    val text =
      StabilityAIConfig(apiKey = Secret(secretKey), model = "sd-v1-6", baseUrl = "https://stability.example").toString
    (text should not).include(secretKey)
    text should include("apiKey=***")
    text should include("model=sd-v1-6")
    text should include("baseUrl=https://stability.example")
  }

  test("HuggingFaceConfig's toString hides the key and shows the model") {
    val text = HuggingFaceConfig(apiKey = Secret(secretKey), model = "acme/diffusion").toString
    (text should not).include(secretKey)
    text should include("apiKey=***")
    text should include("model=acme/diffusion")
  }

  test("StableDiffusionConfig's toString hides the key and shows the base URL") {
    val text = StableDiffusionConfig(baseUrl = "http://sd.example:7860", apiKey = Some(Secret(secretKey))).toString
    (text should not).include(secretKey)
    text should include("apiKey=Some(***)")
    text should include("baseUrl=http://sd.example:7860")
  }

  test("StableDiffusionConfig's toString prints None when there is no key") {
    StableDiffusionConfig().toString should include("apiKey=None")
  }

  // ==========================================================================
  // ImageGenerationClient's default methods
  // ==========================================================================

  test("a client's default edit and async methods return UnsupportedOperation") {
    MinimalClient.editImage(image, "make it blue") shouldBe
      Left(UnsupportedOperation("Image editing is not supported by this provider"))
    await(MinimalClient.generateImageAsync("a cat")) shouldBe
      Left(UnsupportedOperation("Async generation is not supported by this provider"))
    await(MinimalClient.generateImagesAsync("a cat", 2)) shouldBe
      Left(UnsupportedOperation("Async generation is not supported by this provider"))
    await(MinimalClient.editImageAsync(image, "make it blue", mask)) shouldBe
      Left(UnsupportedOperation("Async editing is not supported by this provider"))
  }

  test("a client's default health is Unknown") {
    // ServiceStatus also carries the time of the check, so compare only what the default decides.
    MinimalClient.health().map(s => (s.status, s.message)) shouldBe
      Right((HealthStatus.Unknown, "Health check not implemented"))
  }

  // ==========================================================================
  // Defaults
  // ==========================================================================

  test("ImageGenerationConfig's default timeout is 30 seconds") {
    UnknownConfig.timeout shouldBe 30.seconds
  }

  // ImageGenerationTest checks the other defaults of StableDiffusionConfig, HuggingFaceConfig and OpenAIConfig.
  test("the config defaults ImageGenerationTest does not check") {
    StableDiffusionConfig().model shouldBe "stable-diffusion-v1-5"
    OpenAIConfig(apiKey = Secret("k")).baseUrl shouldBe "https://api.openai.com/v1"

    val stability = StabilityAIConfig(apiKey = Secret("k"))
    stability.model shouldBe "stable-diffusion-xl-1024-v1-0"
    stability.baseUrl shouldBe "https://api.stability.ai"
    stability.timeout shouldBe 2.minutes
    stability.provider shouldBe ImageGenerationProvider.StabilityAI
  }
}
