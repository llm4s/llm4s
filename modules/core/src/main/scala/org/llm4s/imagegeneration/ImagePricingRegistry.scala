package org.llm4s.imagegeneration

/**
 * Registry of image generation model pricing.
 *
 * Provides per-image cost lookup for supported image generation models.
 * Costs are in USD per image and vary by model, size, and quality.
 *
 * Pricing sources:
 *  - OpenAI: https://openai.com/api/pricing/
 *  - Stability AI: https://platform.stability.ai/pricing
 *
 * @see [[org.llm4s.model.ModelPricing]] for LLM token-based pricing
 */
object ImagePricingRegistry {

  /**
   * Pricing entry for an image generation model configuration.
   *
   * @param costPerImage Cost in USD per generated image
   * @param model Model identifier
   * @param quality Quality level (e.g., "standard", "hd")
   * @param size Image size descriptor (e.g., "1024x1024")
   */
  case class ImagePricingEntry(
    costPerImage: Double,
    model: String,
    quality: String,
    size: String
  )

  /**
   * Known pricing entries for image generation models.
   *
   * Prices are approximate and may change. For the most accurate pricing,
   * consult the provider's pricing page.
   *
   * Note: Cost estimation uses the requested size, not the actual provider-billed
   * dimensions. For models like dall-e-3 that normalize non-standard sizes to
   * their nearest supported size, actual billing may differ.
   *
   * Last verified: July 2025
   */
  private val pricingTable: Map[(String, String, String), Double] = Map(
    // gpt-image-1
    ("gpt-image-1", "low", "1024x1024")    -> 0.011,
    ("gpt-image-1", "low", "1536x1024")    -> 0.016,
    ("gpt-image-1", "low", "1024x1536")    -> 0.016,
    ("gpt-image-1", "low", "auto")         -> 0.011,
    ("gpt-image-1", "medium", "1024x1024") -> 0.042,
    ("gpt-image-1", "medium", "1536x1024") -> 0.063,
    ("gpt-image-1", "medium", "1024x1536") -> 0.063,
    ("gpt-image-1", "medium", "auto")      -> 0.042,
    ("gpt-image-1", "high", "1024x1024")   -> 0.167,
    ("gpt-image-1", "high", "1536x1024")   -> 0.250,
    ("gpt-image-1", "high", "1024x1536")   -> 0.250,
    ("gpt-image-1", "high", "auto")        -> 0.167,
    // dall-e-3
    ("dall-e-3", "standard", "1024x1024") -> 0.040,
    ("dall-e-3", "standard", "1024x1792") -> 0.080,
    ("dall-e-3", "standard", "1792x1024") -> 0.080,
    ("dall-e-3", "hd", "1024x1024")       -> 0.080,
    ("dall-e-3", "hd", "1024x1792")       -> 0.120,
    ("dall-e-3", "hd", "1792x1024")       -> 0.120,
    // dall-e-2
    ("dall-e-2", "standard", "1024x1024") -> 0.020,
    ("dall-e-2", "standard", "512x512")   -> 0.018,
    ("dall-e-2", "standard", "256x256")   -> 0.016,
    // stability-ai defaults
    ("stable-diffusion-xl-1024-v1-0", "standard", "1024x1024") -> 0.002
  )

  /**
   * Look up the cost per image for a given model, quality, and size.
   *
   * @param model Model name (e.g., "gpt-image-1", "dall-e-3")
   * @param quality Quality level (e.g., "standard", "hd", "low", "medium", "high")
   * @param size Image size as "WxH" string (e.g., "1024x1024")
   * @return Cost in USD per image, or None if pricing is unknown
   */
  def costPerImage(model: String, quality: String, size: String): Option[Double] =
    pricingTable.get((model, quality, size))

  /**
   * Look up the cost per image with automatic quality defaulting.
   *
   * Uses "standard" as the default quality if the provided quality is empty or not found.
   *
   * @param model Model name
   * @param quality Quality level (defaults to "standard" if None or empty)
   * @param size Image size as "WxH" string
   * @return Cost in USD per image, or None if pricing is unknown
   */
  def costPerImageWithDefaults(
    model: String,
    quality: Option[String],
    size: ImageSize
  ): Option[Double] = {
    val q       = quality.filter(_.nonEmpty).getOrElse("standard")
    val sizeStr = sizeToString(size)
    costPerImage(model, q, sizeStr)
  }

  /**
   * Estimate total cost for generating multiple images.
   *
   * @param model Model name
   * @param quality Quality level
   * @param size Image size
   * @param count Number of images to generate
   * @return Total estimated cost in USD, or None if pricing is unknown
   */
  def estimateCost(
    model: String,
    quality: Option[String],
    size: ImageSize,
    count: Int
  ): Option[Double] =
    costPerImageWithDefaults(model, quality, size).map(_ * count)

  /**
   * List all known models in the pricing registry.
   */
  def knownModels: Set[String] =
    pricingTable.keys.map(_._1).toSet

  private def sizeToString(size: ImageSize): String = size match {
    case ImageSize.Auto         => "auto"
    case ImageSize.Custom(w, h) => s"${w}x$h"
    case s                      => s"${s.width}x${s.height}"
  }
}
