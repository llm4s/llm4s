package fix.v06

import org.llm4s.error.LLMError
import org.llm4s.types.Result

import scala.util.Try
import org.llm4s.error.Safety
import org.llm4s.security.NetworkSecurity
import org.llm4s.syntax.{ toLLMError, toResult }

object ResultSyntaxMovedTest {
  def parse(raw: String): Result[Int] = Try(raw.toInt).toResult

  def all(raw: List[String]): Result[List[Int]] = Result.traverse(raw)(parse)

  def mapped(t: Throwable): LLMError = t.toLLMError

  def first(raw: List[String]): Result[String] = raw.headOption.toResult(mapped(new NoSuchElementException("empty")))

  def safe(raw: String): Result[Int] = Safety.safely(raw.toInt)

  def url(raw: String) = NetworkSecurity.validateUrl(raw)
}
