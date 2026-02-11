package org.llm4s.imagegeneration.provider
import ujson._
import org.llm4s.imagegeneration._
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import java.nio.charset.StandardCharsets
import scala.util.{Try, Success, Failure}
import java.time.Instant

/**
 * Client for Stability AI image generation API.
 * 
 * Supports text-to-image generation using Stability AI's models including
 * Stable Diffusion XL and other variants.
 * 
 * API Documentation: https://platform.stability.ai/docs/api-reference
 */
class StabilityAIClient(config: StabilityAIConfig) extends ImageGenerationClient {
  
  private val httpClient = HttpClient.newHttpClient()
  
  override def generateImage(
    prompt: String,
    options: ImageGenerationOptions = ImageGenerationOptions()
  ): Either[ImageGenerationError, GeneratedImage] = {
    generateImages(prompt, 1, options).map(_.head)
  }

  override def generateImages(
    prompt: String,
    count: Int,
    options: ImageGenerationOptions = ImageGenerationOptions()
  ): Either[ImageGenerationError, Seq[GeneratedImage]] = {
    
    // Build the request body
    val requestBody = buildRequestBody(prompt, count, options)
    
    // Build the HTTP request
    val endpoint = s"${config.baseUrl}/v1/generation/${config.model}/text-to-image"
    val request = HttpRequest.newBuilder()
      .uri(URI.create(endpoint))
      .header("Content-Type", "application/json")
      .header("Authorization", s"Bearer ${config.apiKey}")
      .header("Accept", "application/json")
      .timeout(Duration.ofMillis(config.timeout))
      .POST(HttpRequest.BodyPublishers.ofString(requestBody))
      .build()
    
    // Send request and handle response
    Try {
      val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
      (response.statusCode(), response.body())
    } match {
      case Success((200, body)) =>
        parseSuccessResponse(body, prompt, options)
      
      case Success((401, body)) =>
        Left(AuthenticationError(s"Invalid API key: $body"))
      
      case Success((429, body)) =>
        Left(RateLimitError(s"Rate limit exceeded: $body"))
      
      case Success((400, body)) =>
        Left(ValidationError(s"Invalid request: $body"))
      
      case Success((code, body)) =>
        Left(ServiceError(s"Stability AI API error: $body", code))
      
      case Failure(ex) =>
        Left(UnknownError(ex))
    }
  }

  override def health(): Either[ImageGenerationError, ServiceStatus] = {
    // Stability AI doesn't have a dedicated health endpoint
    // We'll do a minimal test by checking if we can reach the API
    val endpoint = s"${config.baseUrl}/v1/user/account"
    val request = HttpRequest.newBuilder()
      .uri(URI.create(endpoint))
      .header("Authorization", s"Bearer ${config.apiKey}")
      .timeout(Duration.ofMillis(5000)) // Short timeout for health check
      .GET()
      .build()
    
    Try {
      val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
      response.statusCode()
    } match {
      case Success(200) =>
        Right(ServiceStatus(
          status = HealthStatus.Healthy,
          message = "Stability AI API is accessible",
          lastChecked = Instant.now()
        ))
      
      case Success(401) =>
        Right(ServiceStatus(
          status = HealthStatus.Unhealthy,
          message = "Authentication failed - check API key",
          lastChecked = Instant.now()
        ))
      
      case Success(code) =>
        Right(ServiceStatus(
          status = HealthStatus.Degraded,
          message = s"API returned status code: $code",
          lastChecked = Instant.now()
        ))
      
      case Failure(ex) =>
        Right(ServiceStatus(
          status = HealthStatus.Unhealthy,
          message = s"Failed to reach API: ${ex.getMessage}",
          lastChecked = Instant.now()
        ))
    }
  }

  // ===== PRIVATE HELPER METHODS =====

  private def buildRequestBody(
    prompt: String,
    count: Int,
    options: ImageGenerationOptions
  ): String = {
    val textPrompts = ujson.Arr(
      ujson.Obj("text" -> prompt, "weight" -> 1.0)
    )
    
    // Add negative prompt if provided
    options.negativePrompt.foreach { negPrompt =>
      textPrompts.value += ujson.Obj("text" -> negPrompt, "weight" -> -1.0)
    }
    
    val body = ujson.Obj(
      "text_prompts" -> textPrompts,
      "cfg_scale" -> options.guidanceScale,
      "height" -> options.size.height,
      "width" -> options.size.width,
      "samples" -> count,
      "steps" -> options.inferenceSteps
    )
    
    // Add seed if provided
    options.seed.foreach { s =>
      body("seed") = s
    }
    
    // Add sampler if provided
    options.samplerName.foreach { sampler =>
      body("sampler") = sampler
    }
    
    body.render()
  }

  private def parseSuccessResponse(
    responseBody: String,
    prompt: String,
    options: ImageGenerationOptions
  ): Either[ImageGenerationError, Seq[GeneratedImage]] = {
    Try {
      val json = ujson.read(responseBody)
      val artifacts = json("artifacts").arr
      
      artifacts.map { artifact =>
        val base64Data = artifact("base64").str
        val seed = artifact.obj.get("seed").flatMap(_.numOpt).map(_.toLong)
        
        GeneratedImage(
          data = base64Data,
          format = options.format,
          size = options.size,
          createdAt = Instant.now(),
          prompt = prompt,
          seed = seed.orElse(options.seed)
        )
      }.toSeq
    } match {
      case Success(images) if images.nonEmpty =>
        Right(images)
      
      case Success(_) =>
        Left(ServiceError("No images returned in response", 200))
      
      case Failure(ex) =>
        Left(UnknownError(new Exception(s"Failed to parse response: ${ex.getMessage}", ex)))
    }
  }
}