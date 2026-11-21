/*
rule = PositionalPatternsGainFields
*/
package fix.v06

import org.llm4s.llmconnect.model.Completion
import org.llm4s.model.ModelCapabilities

object PositionalPatternsGainFieldsTest {
  def summary(completion: Completion): String = completion match {
    case Completion(id, _, content, _, _, _, _, _, _, _) => s"$id $content"
  }

  def vision(capabilities: ModelCapabilities): Boolean = capabilities match {
    case ModelCapabilities(_, _, vision, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _) => vision.contains(true)
  }
}
