package org.llm4s.imagegeneration.provider

import org.llm4s.imagegeneration.ImageGenerationOptions

/**
 * Request payload sent to the Hugging Face inference API for image generation.
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
   * @param options Image generation options (guidance scale, inference steps, negative prompt, seed).
   * @return A fully populated `HuggingClientPayload` ready for the Hugging Face API.
   */
  def apply(prompt: String, options: ImageGenerationOptions): HuggingClientPayload =
    HuggingClientPayload(
      inputs = prompt,
      parameters = Parameters(
        guidance_scale = options.guidanceScale,
        inferenceSteps = options.inferenceSteps,
        negative_prompt = options.negativePrompt.map(_.toString),
        seed = options.seed
      )
    )
  // The variable e could be replaced with _ - works well in Scala 3 but gives error for Scala 2
  implicit val e: upickle.default.ReadWriter[HuggingClientPayload] = upickle.default.macroRW[HuggingClientPayload]
}
