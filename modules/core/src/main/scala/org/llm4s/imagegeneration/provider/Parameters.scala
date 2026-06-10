package org.llm4s.imagegeneration.provider

/**
 * Diffusion model generation parameters passed inside a Hugging Face API request.
 *
 * @param guidance_scale  Classifier-free guidance scale controlling adherence to the prompt.
 * @param inferenceSteps  Number of denoising steps; higher values improve quality at the cost of speed.
 * @param negative_prompt Optional text describing content to exclude from the generated image.
 * @param seed            Optional random seed for reproducible generation.
 */
case class Parameters(
  guidance_scale: Double,
  inferenceSteps: Int,
  negative_prompt: Option[String],
  seed: Option[Long]
)

/** Companion object providing uPickle serialization for `Parameters`. */
object Parameters {
  // The variable e could be replaced with _ - works well in Scala 3 but gives error for Scala 2
  implicit val e: upickle.default.ReadWriter[Parameters] = upickle.default.macroRW[Parameters]
}
