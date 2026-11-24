/*
rule = Llm4sMigrateFrom_0_6
*/
package fix.v06

import org.llm4s.llmconnect.config.OpenAIConfig
import org.llm4s.llmconnect.model.Completion
import org.llm4s.security.Secret

// 0.6.0 code already past the 0.5.1 breaks. Llm4sMigrateFrom_0_5 would rewrite each of these a second time
// (`config.apiKey.reveal.reveal`, `completion.created.getEpochSecond`); Llm4sMigrateFrom_0_6 leaves them alone.
object Llm4sMigrateFrom_0_6KeepsSecretsTest {
  def key(config: OpenAIConfig): String = config.apiKey.reveal

  def rotated(config: OpenAIConfig): OpenAIConfig = config.withApiKey(Secret("sk-new"))

  def createdAt(completion: Completion): java.time.Instant = completion.created
}
