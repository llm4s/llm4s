/*
rule = MillisToFiniteDuration
*/
package fix

import org.llm4s.error.RateLimitError
import org.llm4s.rag.loader.{ CrawlerConfig, UrlLoader }
import org.llm4s.toolapi.builtin.http.HttpConfig
import org.llm4s.toolapi.builtin.search.BraveSearchConfig
import org.llm4s.toolapi.builtin.shell.ShellConfig

object MillisToFiniteDurationTest {
  val crawler = CrawlerConfig(delayMs = 500, timeoutMs = 30000)

  val limited = Left(RateLimitError("openai", 1000L))

  val http = HttpConfig(timeoutMs = 2500)

  def shell(seconds: Long): ShellConfig = ShellConfig(timeoutMs = seconds * 1000)

  val brave = BraveSearchConfig(timeoutMs = 10000)

  val loader = UrlLoader(Seq("https://example.com"), timeoutMs = 5000)

  def slower(config: HttpConfig, millis: Int): HttpConfig = config.copy(timeoutMs = millis)

  def crawlTimeout(config: CrawlerConfig): Int = config.timeoutMs

  def shellTimeout(config: ShellConfig): Long = config.timeoutMs
}
