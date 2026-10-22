// Probe D: CompletionOptions (private constructor, companion apply with defaults, with* setters) and the
// ReasoningEffort enumeration, from Scala 2.13.
import org.llm4s.llmconnect.model.{CompletionOptions, ReasoningEffort}

object Probe extends App {
  val options = CompletionOptions()
    .withTemperature(0.2)
    .withMaxTokens(256)
    .withReasoning(ReasoningEffort.High)
  val parsed = ReasoningEffort.fromString("medium")
  val described = options.reasoning match {
    case Some(ReasoningEffort.High) => "high"
    case Some(other)                => other.name
    case None                       => "none"
  }
  println(s"PASS: temperature=${options.temperature} maxTokens=${options.maxTokens} reasoning=$described parsed=$parsed budget=${options.effectiveBudgetTokens}")
}
