/*
rule = PositionalPatternsGainFields
*/
package fix.v06

import org.llm4s.agent.events.ModelCallCompleted
import org.llm4s.error.ContentFilteredError
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

  // gained finishReason since 0.6.0 (RFC 0009 PR 6, #2687)
  def model(event: ModelCallCompleted): String = event match {
    case ModelCallCompleted(_, model, _, _, _, _, _, _) => model
  }

  // gained usage since 0.6.0, but its hand-written unapply keeps 0.6.0's four fields: left alone
  def blockedPrompt(error: ContentFilteredError): Boolean = error match {
    case ContentFilteredError(_, _, _, promptBlocked) => promptBlocked
  }

  def failedModel(event: TraceEvent): Option[String] = event match {
    case TraceEvent.ErrorOccurred(_, _, _, _, _, model, _, _, _) => model
    case _ => None
  }
}
