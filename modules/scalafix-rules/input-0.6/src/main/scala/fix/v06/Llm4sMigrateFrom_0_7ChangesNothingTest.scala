/*
rule = Llm4sMigrateFrom_0_7
*/
package fix.v06

import org.llm4s.llmconnect.config.OpenAIConfig
import org.llm4s.llmconnect.model.Completion
import org.llm4s.security.Secret

// Nothing has broken since 0.7.0, so Llm4sMigrateFrom_0_7 runs no rule: it is discovered by name and leaves a file
// alone, including what the older umbrellas would rewrite. It gets an input compiled against 0.7.0 with its first rule.
object Llm4sMigrateFrom_0_7ChangesNothingTest {
  def key(config: OpenAIConfig): String = config.apiKey.reveal

  def rotated(config: OpenAIConfig): OpenAIConfig = config.withApiKey(Secret("sk-new"))

  def createdAt(completion: Completion): java.time.Instant = completion.created
}
