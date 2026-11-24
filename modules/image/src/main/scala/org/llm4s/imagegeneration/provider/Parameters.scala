package org.llm4s.imagegeneration.provider

/**
 * Text-to-image parameters inside a Hugging Face Inference Providers request, named as the
 * [[https://huggingface.co/docs/inference-providers/tasks/text-to-image text-to-image task]] documents them.
 *
 * @param guidance_scale      Classifier-free guidance scale controlling adherence to the prompt.
 * @param num_inference_steps Number of denoising steps; higher values improve quality at the cost of speed.
 * @param negative_prompt     Optional text describing content to exclude from the generated image.
 * @param width               Optional width of the image in pixels; absent for `ImageSize.Auto`.
 * @param height              Optional height of the image in pixels; absent for `ImageSize.Auto`.
 * @param seed                Optional random seed for reproducible generation.
 */
case class Parameters(
  guidance_scale: Double,
  num_inference_steps: Int,
  negative_prompt: Option[String],
  width: Option[Int],
  height: Option[Int],
  seed: Option[Long]
)

/** Companion object providing uPickle serialization for `Parameters`. */
object Parameters {
  // The variable e could be replaced with _ - works well in Scala 3 but gives error for Scala 2
  implicit val e: upickle.default.ReadWriter[Parameters] = upickle.default.macroRW[Parameters]
}
