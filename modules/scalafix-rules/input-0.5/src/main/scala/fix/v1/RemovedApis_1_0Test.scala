/*
rule = RemovedApis_1_0
*/
package fix.v1

import org.llm4s.config.Llm4sConfig
import org.llm4s.core.safety.DefaultErrorMapper
import org.llm4s.error.{ APIError, LLMError, SystemError }
import org.llm4s.llmconnect.model.CompletionChunk
import org.llm4s.trace.AnsiColors
import org.llm4s.types.typeclass.LLMCapable

object RemovedApis_1_0Test {
  def text(chunk: CompletionChunk): String = chunk.id // assert: RemovedApis_1_0.completionChunkRemoved

  def capable[A](capable: LLMCapable[A]): LLMCapable[A] = capable // assert: RemovedApis_1_0.typeclassRemoved

  val green = AnsiColors.GREEN // assert: RemovedApis_1_0.ansiColorsInternal

  val chunking = Llm4sConfig.embeddingsChunking() // assert: RemovedApis_1_0.embeddingsSampleSettingsRemoved

  val api = APIError("openai", "failed", Some(503)) // assert: RemovedApis_1_0.errorTypeRemoved

  def system(error: LLMError): Boolean = error match {
    case _: SystemError => true // assert: RemovedApis_1_0.errorTypeRemoved
    case _              => false
  }

  val failed = LLMError.processingFailed("resize", "bad image") // assert: RemovedApis_1_0.errorShimRemoved

  val mapped = DefaultErrorMapper(new RuntimeException("boom")) // assert: RemovedApis_1_0.errorMapperRemoved

  val both = org.llm4s.Result.combine(Right(1), Right("a")) // assert: RemovedApis_1_0.resultHelperRemoved

  val done = org.llm4s.AsyncResult.success(1) // assert: RemovedApis_1_0.resultHelperRemoved
}
