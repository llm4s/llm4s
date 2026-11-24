package org.llm4s.imagegeneration.provider

import org.llm4s.imagegeneration.ImageGenerationOptions

/**
 * Request payload sent to Hugging Face Inference Providers (`hf-inference`) for text-to-image generation.
 *
 * @param inputs     The text prompt describing the image to generate.
 * @param parameters Generation parameters controlling guidance, steps, seed, etc.
 */
case class HuggingClientPayload(
  inputs: String,
  parameters: Parameters
)

/** Companion object providing a convenience constructor and uPickle serialization for `HuggingClientPayload`. */
object HuggingClientPayload {

  /**
   * Constructs a `HuggingClientPayload` from a prompt string and generation options.
   *
   * @param prompt  The text prompt describing the desired image.
   * @param options Image generation options (guidance scale, inference steps, negative prompt, size, seed).
   *                An `ImageSize.Auto` size sends no width or height, leaving the model's default.
   * @return A fully populated `HuggingClientPayload` ready for the Hugging Face API.
   */
  def apply(prompt: String, options: ImageGenerationOptions): HuggingClientPayload =
    HuggingClientPayload(
      inputs = prompt,
      parameters = Parameters(
        guidance_scale = options.guidanceScale,
        num_inference_steps = options.inferenceSteps,
        negative_prompt = options.negativePrompt.map(_.toString),
        width = Some(options.size.width).filter(_ > 0),
        height = Some(options.size.height).filter(_ > 0),
        seed = options.seed
      )
    )
  // The variable e could be replaced with _ - works well in Scala 3 but gives error for Scala 2
  implicit val e: upickle.default.ReadWriter[HuggingClientPayload] = upickle.default.macroRW[HuggingClientPayload]
}
