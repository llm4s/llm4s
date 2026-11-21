/*
rule = RemovedApis_1_0
*/
package fix.v1

import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.model.CompletionChunk
import org.llm4s.trace.AnsiColors
import org.llm4s.types.typeclass.LLMCapable

object RemovedApis_1_0Test {
  def text(chunk: CompletionChunk): String = chunk.id // assert: RemovedApis_1_0.completionChunkRemoved

  def capable[A](capable: LLMCapable[A]): LLMCapable[A] = capable // assert: RemovedApis_1_0.typeclassRemoved

  val green = AnsiColors.GREEN // assert: RemovedApis_1_0.ansiColorsInternal

  val chunking = Llm4sConfig.embeddingsChunking() // assert: RemovedApis_1_0.embeddingsSampleSettingsRemoved
}
