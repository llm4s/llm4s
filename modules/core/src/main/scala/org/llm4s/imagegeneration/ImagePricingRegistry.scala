package org.llm4s.imagegeneration

import org.llm4s.imagegeneration.ImageGenerationProvider

/**
 * Registry for image generation pricing data.
 *
 * Provides cost estimation for various image generation providers and models.
 * Pricing is approximate and should be verified against current provider pricing.
 */
object ImagePricingRegistry {

  /**
   * Pricing data for a specific image model configuration.
   *
   * @param provider Provider name
   * @param model Model name
   * @param size Image size (e.g., "1024x1024")
   * @param quality Quality setting if applicable (e.g., "standard", "hd")
   * @param costPerImageUsd Cost per image in USD
   */
  case class ImagePricing(
    provider: String,
    model: String,
    size: String,
    quality: Option[String],
    costPerImageUsd: Double
  )

  // OpenAI GPT Image / DALL-E Pricing (as of 2026)
  private val openAIPricing: Seq[ImagePricing] = Seq(
    // DALL-E 3 Standard
    ImagePricing("openai", "dall-e-3", "1024x1024", Some("standard"), 0.040),
    ImagePricing("openai", "dall-e-3", "1024x1792", Some("standard"), 0.080),
    ImagePricing("openai", "dall-e-3", "1792x1024", Some("standard"), 0.080),
    // DALL-E 3 HD
    ImagePricing("openai", "dall-e-3", "1024x1024", Some("hd"), 0.080),
    ImagePricing("openai", "dall-e-3", "1024x1792", Some("hd"), 0.120),
    ImagePricing("openai", "dall-e-3", "1792x1024", Some("hd"), 0.120),
    // GPT Image (gpt-4o-mini)
    ImagePricing("openai", "gpt-image-1", "1024x1024", Some("low"), 0.011),
    ImagePricing("openai", "gpt-image-1", "1024x1024", Some("medium"), 0.042),
    ImagePricing("openai", "gpt-image-1", "1024x1024", Some("high"), 0.167),
    // DALL-E 2
    ImagePricing("openai", "dall-e-2", "1024x1024", None, 0.020),
    ImagePricing("openai", "dall-e-2", "512x512", None, 0.018),
    ImagePricing("openai", "dall-e-2", "256x256", None, 0.016)
  )

  // Stability AI Pricing
  private val stabilityPricing: Seq[ImagePricing] = Seq(
    ImagePricing("stability", "ultra", "1024x1024", None, 0.080),
    ImagePricing("stability", "core", "1024x1024", None, 0.030),
    ImagePricing("stability", "sd3.5-large", "1024x1024", None, 0.065),
    ImagePricing("stability", "sd3.5-medium", "1024x1024", None, 0.035)
  )

  // Google Vertex AI Imagen Pricing
  private val vertexPricing: Seq[ImagePricing] = Seq(
    ImagePricing("vertex", "imagen-4.0-generate-001", "1024x1024", None, 0.040),
    ImagePricing("vertex", "imagegeneration@006", "1024x1024", None, 0.020)
  )

  // AWS Bedrock Pricing (Titan)
  private val bedrockPricing: Seq[ImagePricing] = Seq(
    ImagePricing("bedrock", "amazon.titan-image-generator-v1", "1024x1024", None, 0.010),
    ImagePricing("bedrock", "amazon.titan-image-generator-v2:0", "1024x1024", None, 0.008)
  )

  // Fal AI Pricing
  private val falPricing: Seq[ImagePricing] = Seq(
    ImagePricing("fal", "fal-ai/flux/dev", "1024x1024", None, 0.025),
    ImagePricing("fal", "fal-ai/fast-sdxl", "1024x1024", None, 0.003)
  )

  // HuggingFace Pricing (typically free tier or per-inference)
  private val huggingfacePricing: Seq[ImagePricing] = Seq(
    ImagePricing("huggingface", "stabilityai/stable-diffusion-xl-base-1.0", "1024x1024", None, 0.001)
  )

  // All pricing combined
  private val allPricing: Seq[ImagePricing] =
    openAIPricing ++ stabilityPricing ++ vertexPricing ++ bedrockPricing ++ falPricing ++ huggingfacePricing

  /**
   * Get the cost per image for a given provider, model, and size.
   *
   * @param provider Provider name
   * @param model Model name
   * @param size Image size (default: "1024x1024")
   * @param quality Quality setting if applicable
   * @return Cost per image in USD, or default fallback
   */
  def getCostPerImage(
    provider: String,
    model: String,
    size: String = "1024x1024",
    quality: Option[String] = None
  ): Double = {
    allPricing
      .find { p =>
        p.provider.equalsIgnoreCase(provider) &&
        (p.model.equalsIgnoreCase(model) || model.contains(p.model) || p.model.contains(model)) &&
        (p.size == size || size == "*") &&
        (p.quality == quality || quality.isEmpty || p.quality.isEmpty)
      }
      .map(_.costPerImageUsd)
      .getOrElse(0.020) // Default fallback: $0.02/image
  }

  /**
   * Estimate total cost for image generation.
   *
   * @param provider Provider name
   * @param model Model name
   * @param imageCount Number of images
   * @param size Image size
   * @param quality Quality setting
   * @return Estimated total cost in USD
   */
  def estimateCost(
    provider: String,
    model: String,
    imageCount: Int,
    size: String = "1024x1024",
    quality: Option[String] = None
  ): Double = {
    getCostPerImage(provider, model, size, quality) * imageCount
  }

  /**
   * Get pricing for a specific ImageGenerationProvider enum.
   */
  def getCostForProvider(
    providerEnum: ImageGenerationProvider,
    model: String,
    size: String = "1024x1024"
  ): Double = {
    val providerName = providerEnum match {
      case ImageGenerationProvider.DALLE           => "openai"
      case ImageGenerationProvider.StableDiffusion => "stability"
      case ImageGenerationProvider.StabilityAI     => "stability"
      case ImageGenerationProvider.VertexAI        => "vertex"
      case ImageGenerationProvider.Bedrock         => "bedrock"
      case ImageGenerationProvider.FalAI           => "fal"
      case ImageGenerationProvider.HuggingFace     => "huggingface"
      case ImageGenerationProvider.Midjourney      => "midjourney"
    }
    getCostPerImage(providerName, model, size)
  }
}
