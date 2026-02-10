package org.llm4s.imagegeneration.provider

import org.llm4s.imagegeneration._
import org.slf4j.LoggerFactory
import upickle.default._
import java.nio.file.Path
import scala.util.Try
import scala.concurrent.{ Future, ExecutionContext }
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient
import software.amazon.awssdk.services.bedrockruntime.model.{ InvokeModelRequest, InvokeModelResponse }
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.auth.credentials.{ AwsBasicCredentials, StaticCredentialsProvider }
import software.amazon.awssdk.core.SdkBytes

/**
 * AWS Bedrock client for image generation.
 *
 * This client connects to AWS Bedrock for text-to-image generation using
 * Amazon Titan Image Generator or Stability AI models.
 *
 * @param config Configuration containing region, model, and authentication settings
 *
 * @example
 * {{{
 * val config = BedrockConfig(
 *   region = "us-east-1",
 *   model = "amazon.titan-image-generator-v1",
 *   accessKeyId = Some("your-access-key"),
 *   secretAccessKey = Some("your-secret-key")
 * )
 * val client = new BedrockClient(config)
 *
 * client.generateImage("a beautiful landscape") match {
 *   case Right(image) => println(s"Generated image: ${image.size}")
 *   case Left(error) => println(s"Error: ${error.message}")
 * }
 * }}}
 */
class BedrockClient(config: BedrockConfig) extends ImageGenerationClient {

  private val logger = LoggerFactory.getLogger(getClass)

  private lazy val client: BedrockRuntimeClient = {
    val builder = BedrockRuntimeClient
      .builder()
      .region(Region.of(config.region))
      .overrideConfiguration(
        ClientOverrideConfiguration
          .builder()
          .apiCallTimeout(java.time.Duration.ofMillis(config.timeout))
          .build()
      )

    // Use specific credentials if provided, otherwise default chain
    (config.accessKeyId, config.secretAccessKey) match {
      case (Some(accessKey), Some(secretKey)) =>
        builder.credentialsProvider(
          StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey))
        )
      case _ =>
        // Default credential provider chain (env, profile, instance role, etc.)
        builder
    }

    builder.build()
  }

  override def generateImage(
    prompt: String,
    options: ImageGenerationOptions = ImageGenerationOptions()
  ): Either[ImageGenerationError, GeneratedImage] =
    generateImages(prompt, 1, options).map(_.head)

  override def generateImages(
    prompt: String,
    count: Int,
    options: ImageGenerationOptions = ImageGenerationOptions()
  ): Either[ImageGenerationError, Seq[GeneratedImage]] = {
    logger.info(s"Generating $count image(s) with prompt: ${prompt.take(100)}...")

    val result = for {
      _        <- validatePrompt(prompt)
      _        <- validateCount(count)
      payload  <- Right(buildPayload(prompt, count, options))
      response <- invokeModel(payload)
      images   <- parseResponse(response, prompt, options)
    } yield images

    result
  }

  override def editImage(
    imagePath: Path,
    prompt: String,
    maskPath: Option[Path] = None,
    options: ImageEditOptions = ImageEditOptions()
  ): Either[ImageGenerationError, Seq[GeneratedImage]] =
    Left(ValidationError("Image editing is not yet supported for Bedrock provider"))

  override def generateImageAsync(
    prompt: String,
    options: ImageGenerationOptions = ImageGenerationOptions()
  )(implicit ec: ExecutionContext): Future[Either[ImageGenerationError, GeneratedImage]] =
    Future {
      generateImage(prompt, options)
    }

  override def generateImagesAsync(
    prompt: String,
    count: Int,
    options: ImageGenerationOptions = ImageGenerationOptions()
  )(implicit ec: ExecutionContext): Future[Either[ImageGenerationError, Seq[GeneratedImage]]] =
    Future {
      generateImages(prompt, count, options)
    }

  override def editImageAsync(
    imagePath: Path,
    prompt: String,
    maskPath: Option[Path] = None,
    options: ImageEditOptions = ImageEditOptions()
  )(implicit ec: ExecutionContext): Future[Either[ImageGenerationError, Seq[GeneratedImage]]] =
    Future {
      editImage(imagePath, prompt, maskPath, options)
    }

  override def health(): Either[ImageGenerationError, ServiceStatus] =
    // Bedrock doesn't have a standardized "ping" for the service itself,
    // but we can check if the client can be built and credentials are sane.
    Try {
      // Just accessing the lazy val triggers build
      val _ = client
      ServiceStatus(HealthStatus.Healthy, "AWS Bedrock client initialized")
    }.toEither.left.map(e => ServiceError(s"Failed to initialize AWS Bedrock client: ${e.getMessage}", 0))

  private def validatePrompt(prompt: String): Either[ImageGenerationError, String] =
    Either.cond(prompt.trim.nonEmpty, prompt, ValidationError("Prompt cannot be empty"))

  private def validateCount(count: Int): Either[ImageGenerationError, Int] =
    Either.cond(count > 0 && count <= 5, count, ValidationError("Count must be between 1 and 5 for Bedrock"))

  private def buildPayload(prompt: String, count: Int, options: ImageGenerationOptions): ujson.Value = {
    val (width, height) = (options.size.width, options.size.height)

    // Titan Image Generator format
    if (config.model.startsWith("amazon.titan")) {
      val imageGenConfig = ujson.Obj(
        "numberOfImages" -> count,
        "width"          -> width,
        "height"         -> height
      )

      options.seed.foreach(seed => imageGenConfig("seed") = seed)

      ujson.Obj(
        "taskType" -> "TEXT_IMAGE",
        "textToImageParams" -> ujson.Obj(
          "text" -> prompt
        ),
        "imageGenerationConfig" -> imageGenConfig
      )
    } else {
      // Stability AI format
      ujson.Obj(
        "text_prompts" -> ujson.Arr(
          ujson.Obj("text" -> prompt)
        ),
        "cfg_scale" -> 7,
        "seed"      -> options.seed.map(_.toInt).getOrElse(0),
        "steps"     -> 50,
        "width"     -> width,
        "height"    -> height,
        "samples"   -> count
      )
    }
  }

  private def invokeModel(payload: ujson.Value): Either[ImageGenerationError, InvokeModelResponse] =
    Try {
      val payloadBytes = SdkBytes.fromUtf8String(write(payload))

      val request = InvokeModelRequest
        .builder()
        .modelId(config.model)
        .body(payloadBytes)
        .contentType("application/json")
        .accept("application/json")
        .build()

      client.invokeModel(request)
    }.toEither.left.map {
      case e: software.amazon.awssdk.services.bedrockruntime.model.AccessDeniedException =>
        AuthenticationError(s"Access denied: ${e.getMessage}")
      case e: software.amazon.awssdk.services.bedrockruntime.model.ValidationException =>
        ValidationError(s"Validation failed: ${e.getMessage}")
      case e: software.amazon.awssdk.services.bedrockruntime.model.ThrottlingException =>
        RateLimitError(s"Throttling: ${e.getMessage}")
      case e: Exception =>
        ServiceError(s"AWS SDK error: ${e.getMessage}", 500)
    }

  private def parseResponse(
    response: InvokeModelResponse,
    prompt: String,
    options: ImageGenerationOptions
  ): Either[ImageGenerationError, Seq[GeneratedImage]] =
    Try {
      val responseBodyString = response.body().asUtf8String()
      val responseJson       = read[ujson.Value](responseBodyString)

      // Titan returns images in "images" array
      val imagesOpt = responseJson.obj.get("images").map(_.arr.toSeq)
      // Stability returns in "artifacts" array
      val artifactsOpt = responseJson.obj.get("artifacts").map(_.arr.toSeq)

      val imageDataList = imagesOpt
        .orElse(artifactsOpt.map(_.flatMap(artifact => artifact.obj.get("base64").map(_.str))))
        .getOrElse(Seq.empty)

      if (imageDataList.isEmpty) {
        throw new RuntimeException("No images returned from the API")
      } else {
        val generatedImages = imageDataList.map { imageData =>
          val data = imageData match {
            case s: ujson.Str => s.str
            case other        => other.toString
          }
          GeneratedImage(
            data = data,
            format = ImageFormat.PNG,
            size = options.size,
            prompt = prompt,
            seed = options.seed
          )
        }
        logger.info(s"Successfully generated ${generatedImages.length} image(s)")
        generatedImages
      }
    }.toEither.left.map(e => ServiceError(s"Failed to parse response: ${e.getMessage}", 500))
}
