package org.llm4s.imagegeneration.provider

case class Parameters(
  guidance_scale: Double,
  inferenceSteps: Int,
  negative_prompt: Option[String],
  seed: Option[Long]
)

object Parameters {
  // The variable e could be replaced with _ - works well in Scala 3 but gives error for Scala 2
  implicit val e: upickle.default.ReadWriter[Parameters] = upickle.default.macroRW[Parameters]
}
