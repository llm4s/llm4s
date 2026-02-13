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
    // DALL-E 2
    ImagePricing("openai", "dall-e-2", "1024x1024", None, 0.020),
    ImagePricing("openai", "dall-e-2", "512x512", None, 0.018),
    ImagePricing("openai", "dall-e-2", "256x256", None, 0.016)
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
    openAIPricing ++ falPricing ++ huggingfacePricing

  /** Model aliases to canonical names per provider to ensure deterministic matching */
  private val modelAliases: Map[String, Map[String, String]] = Map(
    "openai" -> Map(
      "dalle-2" -> "dall-e-2",
      "dalle-3" -> "dall-e-3"
    ),
    "fal" -> Map(
      "flux-dev"  -> "fal-ai/flux/dev",
      "fast-sdxl" -> "fal-ai/fast-sdxl"
    )
  )

  /**
   * Get the cost per image for a given provider, model, and size.
   *
   * @param provider Provider name
   * @param model Model name or alias
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
    val canonicalModel = modelAliases
      .get(provider.toLowerCase)
      .flatMap(_.get(model.toLowerCase))
      .getOrElse(model)

    allPricing
      .find { p =>
        p.provider.equalsIgnoreCase(provider) &&
        p.model.equalsIgnoreCase(canonicalModel) &&
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
  ): Double =
    getCostPerImage(provider, model, size, quality) * imageCount

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
      case ImageGenerationProvider.FalAI           => "fal"
      case ImageGenerationProvider.HuggingFace     => "huggingface"
    }
    getCostPerImage(providerName, model, size)
  }
}
