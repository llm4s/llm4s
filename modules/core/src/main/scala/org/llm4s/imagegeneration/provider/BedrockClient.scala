package org.llm4s.imagegeneration.provider

import org.llm4s.imagegeneration._
import org.slf4j.LoggerFactory
import java.nio.file.Path
import scala.util.Try
import scala.concurrent.{Future, ExecutionContext}
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.time.{ZonedDateTime, ZoneOffset}
import java.time.format.DateTimeFormatter
import java.security.MessageDigest
import ujson._

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
  private val service = "bedrock"
  private val host = s"bedrock-runtime.${config.region}.amazonaws.com"

  private def apiUrl: String =
    s"https://$host/model/${config.model}/invoke"

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
    for {
      _        <- validatePrompt(prompt)
      _        <- validateCount(count)
      payload  <- Right(buildPayload(prompt, count, options))
      response <- makeHttpRequest(payload)
      images   <- parseResponse(response, prompt, options)
    } yield images
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

  override def health(): Either[ImageGenerationError, ServiceStatus] = {
    // Bedrock doesn't have a dedicated health endpoint
    // Check if credentials are available
    val hasCredentials = config.accessKeyId.isDefined && config.secretAccessKey.isDefined
    if (hasCredentials) {
      Right(ServiceStatus(HealthStatus.Healthy, "AWS Bedrock credentials configured"))
    } else {
      Right(ServiceStatus(HealthStatus.Degraded, "AWS credentials not explicitly configured (using environment/IAM)"))
    }
  }

  private def validatePrompt(prompt: String): Either[ImageGenerationError, String] =
    Either.cond(prompt.trim.nonEmpty, prompt, ValidationError("Prompt cannot be empty"))

  private def validateCount(count: Int): Either[ImageGenerationError, Int] =
    Either.cond(count > 0 && count <= 5, count, ValidationError("Count must be between 1 and 5 for Bedrock"))

  private def buildPayload(prompt: String, count: Int, options: ImageGenerationOptions): ujson.Value = {
    val (width, height) = (options.size.width, options.size.height)

    // Titan Image Generator format
    if (config.model.startsWith("amazon.titan")) {
      val imageGenConfig = ujson.Obj(
        "numberOfImages" -> ujson.Num(count),
        "width" -> ujson.Num(width),
        "height" -> ujson.Num(height)
      )

      options.seed.foreach { seed =>
        imageGenConfig("seed") = ujson.Num(seed.toDouble)
      }

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
        "seed" -> ujson.Num(options.seed.map(_.toDouble).getOrElse(0.0)),
        "steps" -> 50,
        "width" -> ujson.Num(width),
        "height" -> ujson.Num(height),
        "samples" -> ujson.Num(count)
      )
    }
  }

  private def makeHttpRequest(payload: ujson.Value): Either[ImageGenerationError, requests.Response] = {
    val payloadBytes = write(payload).getBytes("UTF-8")


    val headers = (config.accessKeyId, config.secretAccessKey) match {
      case (Some(accessKey), Some(secretKey)) =>
        buildSignedHeaders(payloadBytes, accessKey, secretKey)
      case _ =>
        // Fallback to environment credentials
        val envAccessKey = sys.env.getOrElse("AWS_ACCESS_KEY_ID", "")
        val envSecretKey = sys.env.getOrElse("AWS_SECRET_ACCESS_KEY", "")
        if (envAccessKey.isEmpty || envSecretKey.isEmpty) {
          return Left(AuthenticationError("AWS credentials not provided"))
        }
        buildSignedHeaders(payloadBytes, envAccessKey, envSecretKey)
    }

    logger.debug(s"Making request to: $apiUrl")
    logger.debug(s"Payload: ${write(payload, indent = 2)}")

    Try {
      requests.post(
        url = apiUrl,
        data = payloadBytes,
        headers = headers,
        readTimeout = config.timeout,
        connectTimeout = 10000
      )
    }.toEither.left.map(e => UnknownError(e))
  }

  private def buildSignedHeaders(payload: Array[Byte], accessKey: String, secretKey: String): Map[String, String] = {
    val now = ZonedDateTime.now(ZoneOffset.UTC)
    val amzDate = now.format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"))
    val dateStamp = now.format(DateTimeFormatter.ofPattern("yyyyMMdd"))

    val payloadHash = sha256Hex(payload)
    val contentType = "application/json"

    val canonicalHeaders = s"content-type:$contentType\nhost:$host\nx-amz-date:$amzDate\n"
    val signedHeaders = "content-type;host;x-amz-date"

    val canonicalRequest = s"POST\n/model/${config.model}/invoke\n\n$canonicalHeaders\n$signedHeaders\n$payloadHash"

    val algorithm = "AWS4-HMAC-SHA256"
    val credentialScope = s"$dateStamp/${config.region}/$service/aws4_request"
    val stringToSign = s"$algorithm\n$amzDate\n$credentialScope\n${sha256Hex(canonicalRequest.getBytes("UTF-8"))}"

    val signingKey = getSignatureKey(secretKey, dateStamp, config.region, service)
    val signature = hmacSha256Hex(signingKey, stringToSign)

    val authorizationHeader = s"$algorithm Credential=$accessKey/$credentialScope, SignedHeaders=$signedHeaders, Signature=$signature"

    Map(
      "Content-Type" -> contentType,
      "X-Amz-Date" -> amzDate,
      "Authorization" -> authorizationHeader,
      "X-Amz-Content-Sha256" -> payloadHash
    )
  }

  private def sha256Hex(data: Array[Byte]): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.digest(data).map("%02x".format(_)).mkString
  }

  private def hmacSha256(key: Array[Byte], data: String): Array[Byte] = {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(key, "HmacSHA256"))
    mac.doFinal(data.getBytes("UTF-8"))
  }

  private def hmacSha256Hex(key: Array[Byte], data: String): String = {
    hmacSha256(key, data).map("%02x".format(_)).mkString
  }

  private def getSignatureKey(key: String, dateStamp: String, region: String, service: String): Array[Byte] = {
    val kDate = hmacSha256(("AWS4" + key).getBytes("UTF-8"), dateStamp)
    val kRegion = hmacSha256(kDate, region)
    val kService = hmacSha256(kRegion, service)
    hmacSha256(kService, "aws4_request")
  }

  private def parseResponse(
    response: requests.Response,
    prompt: String,
    options: ImageGenerationOptions
  ): Either[ImageGenerationError, Seq[GeneratedImage]] = {
    if (response.statusCode == 401 || response.statusCode == 403) {
      return Left(AuthenticationError("Invalid or missing AWS credentials"))
    }

    if (response.statusCode != 200) {
      val errorMsg = s"API request failed with status ${response.statusCode}: ${response.text()}"
      logger.error(errorMsg)
      return Left(ServiceError(errorMsg, response.statusCode))
    }

    Try {
      val responseJson = read(response.text())
      responseJson
    }.toEither.left
      .map(e => UnknownError(e))
      .flatMap { responseJson =>
        // Titan returns images in "images" array
        val imagesOpt = responseJson.obj.get("images").map(_.arr.toSeq.map(_.str))
        // Stability returns in "artifacts" array
        val artifactsOpt = responseJson.obj.get("artifacts").map(_.arr.toSeq.flatMap(_.obj.get("base64").map(_.str)))

        val imageDataList = imagesOpt.orElse(artifactsOpt).getOrElse(Seq.empty)

        if (imageDataList.isEmpty) {
          Left(ValidationError("No images returned from the API"))
        } else {
          val generatedImages = imageDataList.map { imageData =>
            GeneratedImage(
              data = imageData,
              format = ImageFormat.PNG,
              size = options.size,
              prompt = prompt,
              seed = options.seed
            )
          }
          logger.info(s"Successfully generated ${generatedImages.length} image(s)")
          Right(generatedImages)
        }
      }
  }
}
