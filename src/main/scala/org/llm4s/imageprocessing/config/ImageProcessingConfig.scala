package org.llm4s.imageprocessing.config

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
 */
case class OpenAIVisionConfig(
  apiKey: String,
  model: String = "gpt-4-vision-preview",
  baseUrl: String = "https://api.openai.com/v1"
) extends ImageProcessingConfig

/**
 * Configuration for Anthropic Claude Vision API.
 *
 * @param apiKey Anthropic API key
 * @param model Claude model to use
 * @param baseUrl Base URL for Anthropic API (default: official Anthropic endpoint)
 */
case class AnthropicVisionConfig(
  apiKey: String,
  model: String = "claude-3-sonnet-20240229",
  baseUrl: String = "https://api.anthropic.com"
) extends ImageProcessingConfig

/**
 * Configuration for local image processing.
 * This doesn't require external API calls.
 */
case class LocalImageProcessingConfig() extends ImageProcessingConfig
