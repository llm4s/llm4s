package org.llm4s.imagegeneration

import org.scalatest.concurrent.ScalaFutures
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{ Future, ExecutionContext }
import scala.concurrent.ExecutionContext.Implicits.global

import java.nio.file.{ Files, Path }
import java.util.Base64

/**
 * Comprehensive test suite for the Image Generation API.
 *
 * Includes unit tests for models, integration tests for the factory,
 * and a mock implementation for testing without a real server.
 */
class ImageGenerationTest extends AnyFunSuite with Matchers with ScalaFutures {

  // ===== MOCK CLIENT FOR TESTING =====

  class MockImageGenerationClient extends ImageGenerationClient {
    private val mockImageData =
      "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="

    override def generateImage(
      prompt: String,
      options: ImageGenerationOptions = ImageGenerationOptions()
    ): Either[ImageGenerationError, GeneratedImage] = {
      if (prompt.trim.isEmpty) return Left(ValidationError("Prompt cannot be empty"))
      if (prompt.toLowerCase.contains("inappropriate"))
        return Left(InvalidPromptError("Prompt contains inappropriate content"))
      Right(
        GeneratedImage(
          data = mockImageData,
          format = options.format,
          size = options.size,
          prompt = prompt,
          seed = options.seed
        )
      )
    }

    override def generateImages(
      prompt: String,
      count: Int,
      options: ImageGenerationOptions = ImageGenerationOptions()
    ): Either[ImageGenerationError, Seq[GeneratedImage]] = {
      if (count <= 0) return Left(ValidationError("Count must be positive"))
      if (count > 10) return Left(InsufficientResourcesError("Cannot generate more than 10 images at once"))
      generateImage(prompt, options) match {
        case Right(singleImage) =>
          Right((1 to count).map(i => singleImage.copy(seed = options.seed.map(_ + i))))
        case Left(error) => Left(error)
      }
    }

    override def editImage(
      imagePath: Path,
      prompt: String,
      maskPath: Option[Path] = None,
      options: ImageEditOptions = ImageEditOptions()
    ): Either[ImageGenerationError, Seq[GeneratedImage]] = {
      if (prompt.trim.isEmpty) return Left(ValidationError("Prompt cannot be empty"))
      Right(
        Seq(
          GeneratedImage(
            data = mockImageData,
            format = ImageFormat.PNG,
            size = options.size.getOrElse(ImageSize.Square512),
            prompt = prompt,
            seed = None
          )
        )
      )
    }

    override def health(): Either[ImageGenerationError, ServiceStatus] =
      Right(
        ServiceStatus(
          status = HealthStatus.Healthy,
          message = "Mock service is always healthy",
          queueLength = Some(0),
          averageGenerationTime = Some(100)
        )
      )

    override def generateImageAsync(
      prompt: String,
      options: ImageGenerationOptions = ImageGenerationOptions()
    )(implicit ec: ExecutionContext): Future[Either[ImageGenerationError, GeneratedImage]] =
      Future.successful(generateImage(prompt, options))

    override def generateImagesAsync(
      prompt: String,
      count: Int,
      options: ImageGenerationOptions = ImageGenerationOptions()
    )(implicit ec: ExecutionContext): Future[Either[ImageGenerationError, Seq[GeneratedImage]]] =
      Future.successful(generateImages(prompt, count, options))

    override def editImageAsync(
      imagePath: Path,
      prompt: String,
      maskPath: Option[Path] = None,
      options: ImageEditOptions = ImageEditOptions()
    )(implicit ec: ExecutionContext): Future[Either[ImageGenerationError, Seq[GeneratedImage]]] =
      Future.successful(editImage(imagePath, prompt, maskPath, options))
  }

  // ===== MODEL UNIT TESTS =====

  test("ImageSize provides correct dimensions") {
    ImageSize.Square512.width shouldBe 512
    ImageSize.Square512.height shouldBe 512
    ImageSize.Square512.description shouldBe "512x512"

    ImageSize.Landscape768x512.width shouldBe 768
    ImageSize.Landscape768x512.height shouldBe 512
    ImageSize.Landscape768x512.description shouldBe "768x512"

    ImageSize.Landscape1536x1024.width shouldBe 1536
    ImageSize.Landscape1536x1024.height shouldBe 1024

    ImageSize.Portrait1024x1536.width shouldBe 1024
    ImageSize.Portrait1024x1536.height shouldBe 1536
  }

  test("ImageFormat provides correct metadata") {
    ImageFormat.PNG.extension shouldBe "png"
    ImageFormat.PNG.mimeType shouldBe "image/png"

    ImageFormat.JPEG.extension shouldBe "jpg"
    ImageFormat.JPEG.mimeType shouldBe "image/jpeg"
  }

  test("ImageGenerationOptions has sensible defaults") {
    val options = ImageGenerationOptions()

    options.size shouldBe ImageSize.Square512
    options.format shouldBe ImageFormat.PNG
    options.seed shouldBe None
    options.guidanceScale shouldBe 7.5
    options.inferenceSteps shouldBe 20
    options.negativePrompt shouldBe None

    options.quality shouldBe None
    options.style shouldBe None
    options.responseFormat shouldBe None
    options.user shouldBe None
  }

  test("ImageEditOptions has sensible defaults") {
    val options = ImageEditOptions()
    options.size shouldBe None
    options.n shouldBe 1
    options.responseFormat shouldBe None
    options.quality shouldBe None
    options.strength shouldBe None
    options.user shouldBe None
  }

  test("GeneratedImage decodes base64 data correctly") {
    val testData   = "test image data".getBytes("UTF-8")
    val base64Data = Base64.getEncoder.encodeToString(testData)

    val image = GeneratedImage(
      data = base64Data,
      format = ImageFormat.PNG,
      size = ImageSize.Square512,
      prompt = "test prompt"
    )

    image.asBytes shouldBe testData
    image.prompt shouldBe "test prompt"
  }

  test("GeneratedImage can save to file") {
    val testData   = "test image data".getBytes("UTF-8")
    val base64Data = Base64.getEncoder.encodeToString(testData)

    val image = GeneratedImage(
      data = base64Data,
      format = ImageFormat.PNG,
      size = ImageSize.Square512,
      prompt = "test prompt"
    )

    val tempFile = Files.createTempFile("test_image", ".png")

    try
      image.saveToFile(tempFile) match {
        case Right(savedImage) =>
          savedImage.filePath shouldBe Some(tempFile)
          Files.readAllBytes(tempFile) shouldBe testData
        case Left(error) =>
          fail(s"Failed to save image: ${error.message}")
      }
    finally
      Files.deleteIfExists(tempFile)
  }

  // ===== FACTORY TESTS =====

  test("ImageGeneration creates correct client for StableDiffusion config") {
    val config = StableDiffusionConfig()
    val client = ImageGeneration.client(config)
    client shouldBe a[org.llm4s.imagegeneration.provider.StableDiffusionClient]
  }

  test("ImageGeneration creates correct client for HuggingFace config") {
    val config = HuggingFaceConfig(apiKey = "test-key")
    val client = ImageGeneration.client(config)
    client shouldBe a[org.llm4s.imagegeneration.provider.HuggingFaceClient]
  }

  test("stableDiffusionClient creates client with correct config") {
    val client = ImageGeneration.stableDiffusionClient(
      baseUrl = "http://test:8080",
      apiKey = Some("test-key")
    )
    client shouldBe a[org.llm4s.imagegeneration.provider.StableDiffusionClient]
  }

  test("huggingFaceClient creates client with correct config") {
    val client = ImageGeneration.huggingFaceClient(apiKey = "test-key")
    client shouldBe a[org.llm4s.imagegeneration.provider.HuggingFaceClient]
  }

  test("openAIClient creates client with correct config") {
    val client = ImageGeneration.openAIClient(apiKey = "test-key")
    client shouldBe a[org.llm4s.imagegeneration.provider.OpenAIImageClient]
  }

  test("ImageGeneration creates correct client for Bedrock config") {
    val config = BedrockConfig()
    val client = ImageGeneration.client(config)
    client shouldBe a[org.llm4s.imagegeneration.provider.BedrockClient]
  }

  test("Config objects have correct default values") {
    val sdConfig = StableDiffusionConfig()
    sdConfig.baseUrl shouldBe "http://localhost:7860"
    sdConfig.apiKey shouldBe None
    sdConfig.timeout shouldBe 60000
    sdConfig.provider shouldBe ImageGenerationProvider.StableDiffusion

    val hfConfig = HuggingFaceConfig(apiKey = "test-key")
    hfConfig.model shouldBe "stabilityai/stable-diffusion-xl-base-1.0"
    hfConfig.timeout shouldBe 120000
    hfConfig.provider shouldBe ImageGenerationProvider.HuggingFace

    val openAIConfig = OpenAIConfig(apiKey = "test-key")
    openAIConfig.model shouldBe "dall-e-2"
    openAIConfig.provider shouldBe ImageGenerationProvider.DALLE
  }

  test("BedrockConfig has correct default values") {
    val config = BedrockConfig()
    config.region shouldBe "us-east-1"
    config.model shouldBe "amazon.titan-image-generator-v1"
    config.accessKeyId shouldBe None
    config.secretAccessKey shouldBe None
    config.timeout shouldBe 120000
    config.provider shouldBe ImageGenerationProvider.Bedrock
  }

  test("Config objects can be customized") {
    val customSdConfig = StableDiffusionConfig(
      baseUrl = "http://custom:9000",
      apiKey = Some("custom-key"),
      timeout = 120000
    )
    customSdConfig.baseUrl shouldBe "http://custom:9000"
    customSdConfig.apiKey shouldBe Some("custom-key")
    customSdConfig.timeout shouldBe 120000

    val customHfConfig = HuggingFaceConfig(
      apiKey = "custom-key",
      model = "custom-model",
      timeout = 120000
    )
    customHfConfig.model shouldBe "custom-model"
    customHfConfig.timeout shouldBe 120000
  }

  // ===== MOCK CLIENT TESTS =====

  val mockClient = new MockImageGenerationClient()

  test("Mock client generates image successfully") {
    val result = mockClient.generateImage("A beautiful landscape")
    result match {
      case Right(image) =>
        image.prompt shouldBe "A beautiful landscape"
        image.format shouldBe ImageFormat.PNG
        image.size shouldBe ImageSize.Square512
        image.data should not be empty
      case Left(error) =>
        fail(s"Expected a successful image generation, but got error: $error")
    }
  }

  test("Mock client respects custom options") {
    val options = ImageGenerationOptions(
      size = ImageSize.Landscape768x512,
      format = ImageFormat.JPEG,
      seed = Some(42),
      guidanceScale = 10.0,
      negativePrompt = Some("blurry")
    )
    val result = mockClient.generateImage("Test prompt", options)
    result match {
      case Right(image) =>
        image.size shouldBe ImageSize.Landscape768x512
        image.format shouldBe ImageFormat.JPEG
        image.seed shouldBe Some(42)
      case Left(error) =>
        fail(s"Expected successful image generation, but got error: $error")
    }
  }

  test("Mock client validates prompts") {
    mockClient.generateImage("") match {
      case Left(_: ValidationError) => succeed
      case Left(other)              => fail(s"Expected ValidationError, got $other")
      case Right(img)               => fail(s"Expected error, but got image: $img")
    }
    mockClient.generateImage("inappropriate content") match {
      case Left(_: InvalidPromptError) => succeed
      case Left(other)                 => fail(s"Expected InvalidPromptError, got $other")
      case Right(img)                  => fail(s"Expected error, but got image: $img")
    }
  }

  test("Mock client generates multiple images") {
    val result = mockClient.generateImages("Test prompt", 3)
    result match {
      case Right(images) =>
        (images should have).length(3)
        images.foreach { image =>
          image.prompt shouldBe "Test prompt"
          image.data should not be empty
        }
      case Left(error) =>
        fail(s"Expected Right with images, but got Left($error)")
    }
  }

  test("Mock client validates image count") {
    mockClient.generateImages("Test", -1) should matchPattern { case Left(_: ValidationError) => }
    mockClient.generateImages("Test", 0) should matchPattern { case Left(_: ValidationError) => }
    mockClient.generateImages("Test", 15) match {
      case Left(_: InsufficientResourcesError) => succeed
      case Left(other)                         => fail(s"Expected InsufficientResourcesError, but got $other")
      case Right(_)                            => fail("Expected failure, but got success")
    }
  }

  test("Mock client reports healthy status") {
    val result = mockClient.health()
    result match {
      case Right(status) =>
        status.status shouldBe HealthStatus.Healthy
        status.message shouldBe "Mock service is always healthy"
        status.queueLength shouldBe Some(0)
        status.averageGenerationTime shouldBe Some(100)
      case Left(error) =>
        fail(s"Expected healthy status, but got error: $error")
    }
  }

  test("Mock client edits image successfully") {
    val tempFile = Files.createTempFile("test_input", ".png")
    val result   = mockClient.editImage(tempFile, "edited prompt")
    try
      result match {
        case Right(images) =>
          images.head.prompt shouldBe "edited prompt"
          images.head.size shouldBe ImageSize.Square512
        case Left(error) =>
          fail(s"Expected successful edit, but got error: $error")
      }
    finally
      Files.deleteIfExists(tempFile)
  }

  // ===== ERROR HANDLING TESTS =====

  test("Error types have correct messages") {
    val authError       = AuthenticationError("Invalid API key")
    val serviceError    = ServiceError("Server error", 500)
    val validationError = ValidationError("Invalid prompt")
    val unknownError    = UnknownError(new RuntimeException("Something went wrong"))

    authError.message shouldBe "Invalid API key"
    serviceError.message shouldBe "Server error"
    serviceError.code shouldBe 500
    validationError.message shouldBe "Invalid prompt"
    unknownError.message shouldBe "Something went wrong"
  }

  // ===== INTEGRATION TESTS =====

  test("generateWithStableDiffusion handles connection errors gracefully") {
    val result = ImageGeneration.generateWithStableDiffusion(
      "test prompt",
      baseUrl = "http://localhost:99999"
    )
    result match {
      case Left(_)      => succeed
      case Right(value) => fail(s"Expected failure but got: $value")
    }
  }

  // ===== ASYNC TESTS =====

  test("generateImageAsync returns Future[Either]") {
    val futureResult = mockClient.generateImageAsync("Async test")
    whenReady(futureResult) { result =>
      result shouldBe a[Right[?, ?]]
      result.map { image =>
        image.prompt shouldBe "Async test"
        image.size shouldBe ImageSize.Square512
      }
    }
  }

  test("generateImagesAsync returns Future[Either]") {
    val futureResult = mockClient.generateImagesAsync("Async multiple", 2)
    whenReady(futureResult) { result =>
      result shouldBe a[Right[?, ?]]
      result.map { images =>
        (images should have).length(2)
        images.head.prompt shouldBe "Async multiple"
      }
    }
  }

  test("editImageAsync returns Future[Either]") {
    val tempFile = Files.createTempFile("async_edit", ".png")
    try {
      val futureResult = mockClient.editImageAsync(tempFile, "Async edit")
      whenReady(futureResult) { result =>
        result shouldBe a[Right[?, ?]]
        result.map(images => images.head.prompt shouldBe "Async edit")
      }
    } finally
      Files.deleteIfExists(tempFile)
  }
}
