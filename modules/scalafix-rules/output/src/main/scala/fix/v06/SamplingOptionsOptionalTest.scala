package fix.v06

import org.llm4s.llmconnect.model.CompletionOptions

object SamplingOptionsOptionalTest {
  val precise = CompletionOptions(temperature = Some(0.2), maxTokens = Some(500))

  val every = CompletionOptions(
    temperature = Some(0.2),
    topP = Some(0.9),
    presencePenalty = Some(0.5),
    frequencyPenalty = Some(-0.5)
  )

  def at(t: Double): CompletionOptions = CompletionOptions(temperature = Some(t / 2))

  // the setters still take the value
  val set = CompletionOptions().withTemperature(0.2).withTopP(0.9)
}
