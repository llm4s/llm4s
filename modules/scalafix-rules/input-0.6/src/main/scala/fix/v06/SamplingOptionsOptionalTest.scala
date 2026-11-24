/*
rule = SamplingOptionsOptional
*/
package fix.v06

import org.llm4s.llmconnect.model.CompletionOptions

object SamplingOptionsOptionalTest {
  val precise = CompletionOptions(temperature = 0.2, maxTokens = Some(500))

  val every = CompletionOptions(
    temperature = 0.2,
    topP = 0.9,
    presencePenalty = 0.5,
    frequencyPenalty = -0.5
  )

  def at(t: Double): CompletionOptions = CompletionOptions(temperature = t / 2)

  // the setters still take the value
  val set = CompletionOptions().withTemperature(0.2).withTopP(0.9)
}
