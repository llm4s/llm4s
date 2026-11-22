/*
rule = PositionalPatternsGainFields
*/
package fix.v06

import org.llm4s.llmconnect.model.Completion
import org.llm4s.model.ModelCapabilities
import org.llm4s.trace.TraceEvent

object PositionalPatternsGainFieldsTest {
  def summary(completion: Completion): String = completion match {
    case Completion(id, _, content, _, _, _, _, _, _, _) => s"$id $content"
  }

  def vision(capabilities: ModelCapabilities): Boolean = capabilities match {
    case ModelCapabilities(_, _, vision, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _) => vision.contains(true)
  }

  def failedModel(event: TraceEvent): Option[String] = event match {
    case TraceEvent.ErrorOccurred(_, _, _, _, _, model, _, _, _) => model
    case _ => None
  }
}
