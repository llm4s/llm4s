package fix

import org.llm4s.error.RateLimitError
import org.llm4s.rag.loader.{ CrawlerConfig, UrlLoader }
import org.llm4s.toolapi.builtin.http.HttpConfig
import org.llm4s.toolapi.builtin.search.BraveSearchConfig
import org.llm4s.toolapi.builtin.shell.ShellConfig
import scala.concurrent.duration.*

object MillisToFiniteDurationTest {
  val crawler = CrawlerConfig(delay = 500.millis, timeout = 30.seconds)

  val limited = Left(RateLimitError("openai", 1.second))

  val http = HttpConfig(timeout = 2500.millis)

  def shell(seconds: Long): ShellConfig = ShellConfig(timeout = (seconds * 1000).millis)

  val brave = BraveSearchConfig(timeout = 10.seconds)

  val loader = UrlLoader(Seq("https://example.com"), timeout = 5.seconds)

  def slower(config: HttpConfig, millis: Int): HttpConfig = config.copy(timeout = millis.millis)

  def crawlTimeout(config: CrawlerConfig): Int = config.timeout.toMillis.toInt

  def shellTimeout(config: ShellConfig): Long = config.timeout.toMillis
}

