package org.llm4s.imagegeneration.provider

import org.llm4s.imagegeneration.ImageGenerationOptions

case class HuggingClientPayload(
  inputs: String,
  parameters: Parameters
)

object HuggingClientPayload {
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
