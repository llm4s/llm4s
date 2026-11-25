package fix.v06

import org.llm4s.error.LLMError
import org.llm4s.llmconnect.model.EmbeddingUsage
import org.llm4s.types.Result

import scala.util.Try
import org.llm4s.context.tokens.TokenizerId
import org.llm4s.error.{ EmbeddingError, Safety }
import org.llm4s.security.NetworkSecurity
import org.llm4s.syntax.{ toLLMError, toResult }
import org.llm4s.trace.model.TraceId
import org.llm4s.types.ProviderModelTypes.ProviderId

object ResultSyntaxMovedTest {
  def parse(raw: String): Result[Int] = Try(raw.toInt).toResult

  def all(raw: List[String]): Result[Seq[Int]] = Result.traverse(raw)(parse)

  def mapped(t: Throwable): LLMError = t.toLLMError

  def first(raw: List[String]): Result[String] = raw.headOption.toResult(mapped(new NoSuchElementException("empty")))

  def safe(raw: String): Result[Int] = Safety.safely(raw.toInt)

  def url(raw: String) = NetworkSecurity.validateUrl(raw)

  def vocabulary: TokenizerId = TokenizerId.CL100K_BASE

  def refused(usage: EmbeddingUsage): EmbeddingError =
    EmbeddingError(Some("401"), s"refused after ${usage.totalTokens} tokens", ProviderId("openai"))

  def trace(raw: String): TraceId = TraceId(raw)
}
