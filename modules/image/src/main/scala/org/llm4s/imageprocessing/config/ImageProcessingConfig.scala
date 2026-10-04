package org.llm4s.imageprocessing.config

import scala.concurrent.duration.*

/**
 * Base trait for image processing configurations.
 */
sealed trait ImageProcessingConfig

/**
 * Configuration for OpenAI Vision API.
 *
 * @param apiKey OpenAI API key
 * @param model Vision model to use
 * @param baseUrl Base URL for OpenAI API (default: official OpenAI endpoint)
 * @param connectTimeout Connection timeout (default: 30 seconds)
 * @param requestTimeout Request timeout (default: 60 seconds)
 */
case class OpenAIVisionConfig(
  apiKey: String,
  model: String = "gpt-4-vision-preview",
  baseUrl: String = "https://api.openai.com/v1",
  connectTimeout: FiniteDuration = 30.seconds,
  requestTimeout: FiniteDuration = 60.seconds
) extends ImageProcessingConfig

/**
 * Configuration for Anthropic Claude Vision API.
 *
 * @param apiKey Anthropic API key
 * @param model Claude model to use
 * @param baseUrl Base URL for Anthropic API (default: official Anthropic endpoint)
 * @param connectTimeout Connection timeout (default: 30 seconds)
 * @param requestTimeout Request timeout (default: 60 seconds)
 */
case class AnthropicVisionConfig(
  apiKey: String,
  model: String = "claude-3-sonnet-20240229",
  baseUrl: String = "https://api.anthropic.com",
  connectTimeout: FiniteDuration = 30.seconds,
  requestTimeout: FiniteDuration = 60.seconds
) extends ImageProcessingConfig

/**
 * Configuration for Google Gemini Vision API.
 *
 * @param apiKey Google API key
 * @param model Gemini model to use
 * @param baseUrl Base URL for Google Generative Language API
 * @param connectTimeoutSeconds Connection timeout in seconds (default: 30)
 * @param requestTimeoutSeconds Request timeout in seconds (default: 60)
 */
case class GeminiVisionConfig(
  apiKey: String,
  model: String = "gemini-1.5-flash",
  baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
  connectTimeoutSeconds: Int = 30,
  requestTimeoutSeconds: Int = 60
) extends ImageProcessingConfig {

  /** Never prints the API key. */
  override def toString: String =
    s"GeminiVisionConfig(apiKey=***,model=$model,baseUrl=$baseUrl,connectTimeoutSeconds=$connectTimeoutSeconds,requestTimeoutSeconds=$requestTimeoutSeconds)"
}

/**
 * Configuration for local image processing.
 * This doesn't require external API calls.
 */
case class LocalImageProcessingConfig() extends ImageProcessingConfig
