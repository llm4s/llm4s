package org.llm4s.config

import com.typesafe.config.{ ConfigFactory, ConfigResolveOptions }
import org.llm4s.error.CancelledError
import org.llm4s.llmconnect.{
  JsonlProviderExchangeSink,
  ProviderExchange,
  ProviderExchangeLogging,
  ProviderExchangeOutcome,
  ProviderExchangeSink
}
import org.llm4s.llmconnect.provider.ProviderExchangeRecorder
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource

import java.nio.file.{ Files, Path }
import java.time.Instant
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

/**
 * The claims of `docs/guide/observability/provider-exchange-logging.md` that need no client: the
 * configuration keys, the file the JSONL sink writes, what it redacts and truncates, and what the recorder
 * does with a sink.
 *
 * If an assertion here fails, the guide describes something that no longer happens: change the guide and
 * this spec together. The end-to-end claims, through a real client, are in `ProviderExchangeLoggingGuideSpec`
 * in `llm4s-openai-compatible`.
 */
class ProviderExchangeLoggingGuideCoreSpec extends AnyFlatSpec with Matchers {

  private def tempDir(): Path = Files.createTempDirectory("exchange-core-guide")

  /** HOCON takes forward slashes on every platform; a Windows path's backslashes would be escapes in it. */
  private def hoconPath(dir: Path): String = dir.toString.replace('\\', '/')

  private val now = Instant.parse("2026-10-07T10:27:19Z")

  private def exchange(
    requestBody: String = "{}",
    responseBody: Option[String] = None,
    errorMessage: Option[String] = None,
    outcome: ProviderExchangeOutcome = ProviderExchangeOutcome.Success
  ): ProviderExchange =
    ProviderExchange(
      exchangeId = "exchange-1",
      provider = "provider",
      model = Some("model"),
      requestId = None,
      correlationId = None,
      startedAt = now,
      completedAt = now,
      duration = 5.millis,
      outcome = outcome,
      requestBody = requestBody,
      responseBody = responseBody,
      errorMessage = errorMessage
    )

  /** Records `ex` through a fresh JSONL sink and returns the one line it wrote, parsed. */
  private def written(ex: ProviderExchange): upickle.core.LinkedHashMap[String, ujson.Value] = {
    val sink = ProviderExchangeSink.createRunScopedJsonl(tempDir()).getOrElse(fail("could not create the sink"))
    sink.record(ex)
    val lines = Files.readAllLines(sink.path).asScala.toList
    lines should have size 1
    ujson.read(lines.head).obj
  }

  // ---- configuration

  "the guide's configuration block" should "switch exchange logging on and name the directory" in {
    val dir = tempDir().resolve("logs")
    val config = ConfigFactory.parseString(s"""
      llm4s.exchangeLogging {
        enabled = true
        dir = "${hoconPath(dir)}"
      }
    """)

    ProviderExchangeLoggingConfigLoader.load(ConfigSource.fromConfig(config)) match {
      case Right(ProviderExchangeLogging.Enabled(sink: JsonlProviderExchangeSink)) =>
        (sink.path.getFileName.toString should fullyMatch).regex("provider-exchanges-.*\\.jsonl")
        Files.isDirectory(dir) shouldBe true
        // the file is created when logging is set up, before any exchange
        Files.size(sink.path) shouldBe 0L
      case other => fail(s"expected an enabled JSONL setting, got $other")
    }
  }

  it should "be off when the section is absent, or `enabled` is false, and then creates nothing" in {
    val dir = tempDir().resolve("never-created")

    ProviderExchangeLoggingConfigLoader.load(ConfigSource.fromConfig(ConfigFactory.empty())) shouldBe
      Right(ProviderExchangeLogging.Disabled)
    ProviderExchangeLoggingConfigLoader.load(
      ConfigSource.fromConfig(
        ConfigFactory.parseString(s"""llm4s.exchangeLogging { enabled = false, dir = "${hoconPath(dir)}" }""")
      )
    ) shouldBe Right(ProviderExchangeLogging.Disabled)
    Files.exists(dir) shouldBe false
  }

  it should "refuse `enabled = true` without a directory, naming the key" in {
    List("""llm4s.exchangeLogging { enabled = true }""", """llm4s.exchangeLogging { enabled = true, dir = "  " }""")
      .foreach { text =>
        val result = ProviderExchangeLoggingConfigLoader.load(ConfigSource.fromConfig(ConfigFactory.parseString(text)))

        result.left.toOption.map(_.message) shouldBe Some(
          "Provider exchange logging is enabled but llm4s.exchangeLogging.dir is missing"
        )
      }
  }

  "the shipped defaults" should "leave exchange logging off and bind the two documented variables" in {
    val shipped = ConfigFactory.parseResources(getClass.getClassLoader, "reference.conf")

    // unresolved, the substitutions name the variables
    val rendered = shipped.root().render()
    rendered should include("LLM4S_EXCHANGE_LOGGING_ENABLED")
    rendered should include("LLM4S_EXCHANGE_LOGGING_DIR")

    // resolved without the environment, only the defaults remain: off, and no directory
    val defaults = shipped.resolve(ConfigResolveOptions.defaults().setUseSystemEnvironment(false))
    defaults.getBoolean("llm4s.exchangeLogging.enabled") shouldBe false
    defaults.hasPath("llm4s.exchangeLogging.dir") shouldBe false
    ProviderExchangeLoggingConfigLoader.load(ConfigSource.fromConfig(defaults)) shouldBe
      Right(ProviderExchangeLogging.Disabled)
  }

  // ---- the file

  "the JSONL sink" should "name its file by the start time, and add a number when the name is taken" in {
    val dir = tempDir()
    val at  = Instant.parse("2026-10-07T10:20:30Z")

    val names = List.fill(3)(ProviderExchangeSink.createRunScopedJsonl(dir, at).map(_.path.getFileName.toString))

    names.map(_.getOrElse(fail("could not create the sink"))) shouldBe List(
      "provider-exchanges-2026-10-07T10-20-30Z.jsonl",
      "provider-exchanges-2026-10-07T10-20-30Z-2.jsonl",
      "provider-exchanges-2026-10-07T10-20-30Z-3.jsonl"
    )
  }

  it should "create missing directories, and refuse a path that is a file" in {
    val nested = tempDir().resolve("a").resolve("b")
    ProviderExchangeSink.createRunScopedJsonl(nested).isRight shouldBe true
    Files.isDirectory(nested) shouldBe true

    val file    = Files.createTempFile("exchange-core-guide", ".txt")
    val refused = ProviderExchangeSink.createRunScopedJsonl(file)
    refused.left.toOption.map(_.message).getOrElse("") should include("Path is not a directory")
  }

  it should "append one line per exchange, in order, without rewriting earlier lines" in {
    val sink = ProviderExchangeSink.createRunScopedJsonl(tempDir()).getOrElse(fail("could not create the sink"))
    sink.record(exchange(requestBody = "first"))
    sink.record(exchange(requestBody = "second"))
    sink.record(exchange(requestBody = "third"))

    Files.readAllLines(sink.path).asScala.toList.map(line => ujson.read(line)("request_body").str) shouldBe
      List("first", "second", "third")
  }

  it should "write the documented keys in order, an absent value as an empty string and the duration as a string" in {
    val row = written(exchange(responseBody = None, errorMessage = None))

    row.keys.toList shouldBe List(
      "exchange_id",
      "provider",
      "model",
      "request_id",
      "correlation_id",
      "started_at",
      "completed_at",
      "duration_ms",
      "outcome",
      "request_body",
      "response_body",
      "error_message"
    )
    row("exchange_id").str shouldBe "exchange-1"
    row("provider").str shouldBe "provider"
    row("model").str shouldBe "model"
    row("request_id").str shouldBe ""
    row("correlation_id").str shouldBe ""
    row("response_body").str shouldBe ""
    row("error_message").str shouldBe ""
    row("started_at").str shouldBe "2026-10-07T10:27:19Z"
    row("duration_ms") shouldBe ujson.Str("5")
    row("outcome").str shouldBe "Success"
  }

  // ---- truncation

  it should "cut each body and the error message to 1000 characters and say how many it omitted" in {
    val long = "x" * 3000
    val row  = written(exchange(requestBody = long, responseBody = Some(long), errorMessage = Some(long)))

    val expected = ("x" * 1000) + "... [truncated, 2000 chars omitted]"
    row("request_body").str shouldBe expected
    row("response_body").str shouldBe expected
    row("error_message").str shouldBe expected
  }

  it should "leave a body of exactly 1000 characters whole" in {
    val exact = "y" * 1000

    written(exchange(requestBody = exact))("request_body").str shouldBe exact
  }

  // ---- redaction

  it should "redact authorization headers, bearer tokens, API-key shapes, sensitive URL parameters and JSON fields" in {
    val cases = List(
      "Authorization: Bearer abc.def.ghi-123"           -> "Authorization: [REDACTED]",
      "my key is sk-abcdefghijklmnopqrstuvwx1234567890" -> "my key is [REDACTED]",
      "https://x.example/v1?api_key=TOPSECRET&q=1"      -> "https://x.example/v1?api_key=[REDACTED]&q=1",
      """{"api_key":"hunter2hunter2","user":"bob"}"""   -> """{"api_key":"[REDACTED]","user":"bob"}""",
      """{"password":"correct horse"}"""                -> """{"password":"[REDACTED]"}"""
    )

    cases.foreach { case (raw, redacted) =>
      withClue(s"for: $raw") {
        written(exchange(requestBody = raw))("request_body").str shouldBe redacted
      }
    }
  }

  it should "redact the further shapes the guide lists: embedded, single-quoted and truncated JSON, numbers, assignments and header lines" in {
    val cases = List(
      // JSON inside a prompt or response string, with escaped quotes
      """{"content": "{\"api_key\": \"hunter2secret\"}"}""" -> """{"content": "{\"api_key\": \"[REDACTED]\"}"}""",
      // a value containing an escaped quote is redacted whole
      """{"password": "ab\"cd12345"}""" -> """{"password": "[REDACTED]"}""",
      // cut off before its closing quote, as a truncated payload leaves it
      """{"user": "ann", "password": "hunter2va""" -> """{"user": "ann", "password": "[REDACTED]""",
      // single-quoted JSON
      """{'api_key': 'abc123456789'}""" -> """{'api_key': '[REDACTED]'}""",
      // a number, including an exponent form, written back as a string
      """{"password": 12345678}""" -> """{"password": "[REDACTED]"}""",
      """{"password": 1e10}"""     -> """{"password": "[REDACTED]"}""",
      // key=value, a dotted property name, and quoted assignments outside a query string
      "login password=hunter2secret ok"         -> "login password=[REDACTED] ok",
      "spring.datasource.password=hunter2"      -> "spring.datasource.password=[REDACTED]",
      """PASSWORD="hunter2value" NAME="keep"""" -> """PASSWORD="[REDACTED]" NAME="keep"""",
      "PASSWORD='hunter2value' NAME='keep'"     -> "PASSWORD='[REDACTED]' NAME='keep'",
      // a header-style line
      "x-api-key: abc123456789" -> "x-api-key: [REDACTED]",
      // an array or an object under a sensitive key: every string and number leaf, the keys and brackets kept
      """{"token": ["abc123456789", "def123456789"]}""" -> """{"token": ["[REDACTED]", "[REDACTED]"]}""",
      """{"credentials": {"user": "ann", "pass": "abc123456789", "port": 5432}}""" ->
        """{"credentials": {"user": "[REDACTED]", "pass": "[REDACTED]", "port": "[REDACTED]"}}""",
      """{"content": "{\"token\": [\"abc123456789\"]}"}""" -> """{"content": "{\"token\": [\"[REDACTED]\"]}"}""",
      // the key or the leaves single-quoted, as a Python dict is
      "{'token': ['abc123456789'], 'credentials': {'user': 'ann', 'pass': 'abc123456789'}}" ->
        "{'token': ['[REDACTED]'], 'credentials': {'user': '[REDACTED]', 'pass': '[REDACTED]'}}",
      """{"token": ['abc123456789']}""" -> """{"token": ['[REDACTED]']}""",
      // bare leaves are replaced too; the literals are kept
      """{"token": [abc123456789, 42, true, null]}""" -> """{"token": ["[REDACTED]", "[REDACTED]", true, null]}""",
      // inside a string a single-quoted container ends where the string does, and a `\"`-quoted leaf under a
      // single-quoted key there is left
      """{"content": "see 'token': [ for details", "api_key": "abc123456789"}""" ->
        """{"content": "see 'token': [ for details", "api_key": "[REDACTED]"}""",
      """{"content": "see 'token': [ for details, it's urgent"}""" ->
        """{"content": "see 'token': [ for details, it's urgent"}""",
      """{"content": "use 'password': ' carefully", "model": "gpt-4o"}""" ->
        """{"content": "use 'password': '[REDACTED]", "model": "gpt-4o"}""",
      "see 'token': [ for details" -> "see 'token': [ '[REDACTED]' '[REDACTED]'",
      """{"content": "{'token': [\"abc123456789\"]}", "n": 1}""" -> """{"content": "{'token': [\"abc123456789\"]}", "n": 1}""",
      """{"max_tokens": [1, 2], "messages": [{"role": "user", "content": "hi"}]}""" ->
        """{"max_tokens": [1, 2], "messages": [{"role": "user", "content": "hi"}]}""",
      // compound key names are sensitive by suffix; token-count fields are not
      """{"client_secret": "abc123456789", "refresh_token": "abc123456789"}""" ->
        """{"client_secret": "[REDACTED]", "refresh_token": "[REDACTED]"}""",
      """{"max_tokens": 256, "prompt_tokens": 12, "token_count": 3, "next_page_token": "abc123456789"}""" ->
        """{"max_tokens": 256, "prompt_tokens": 12, "token_count": 3, "next_page_token": "abc123456789"}"""
    )

    cases.foreach { case (raw, redacted) =>
      withClue(s"for: $raw") {
        written(exchange(requestBody = raw))("request_body").str shouldBe redacted
      }
    }
  }

  it should "redact the response body and the error message too" in {
    val row =
      written(exchange(responseBody = Some("""{"token":"abc123abc123"}"""), errorMessage = Some("Bearer abc123")))

    row("response_body").str shouldBe """{"token":"[REDACTED]"}"""
    row("error_message").str shouldBe "[REDACTED]"
  }

  it should "not look for personal data: an email address and a phone number are written as they are" in {
    val text = "contact alice@example.com or +1 415 555 0100"

    written(exchange(requestBody = text))("request_body").str shouldBe text
  }

  // ---- the recorder and its sink

  "a custom sink" should "receive the exchange as it is, with nothing redacted or truncated" in {
    val seen   = ListBuffer.empty[ProviderExchange]
    val sink   = new ProviderExchangeSink { def record(ex: ProviderExchange): Unit = seen += ex }
    val secret = "Authorization: Bearer abc123def456 " + ("z" * 2000)

    ProviderExchangeRecorder.record(
      ProviderExchangeLogging.enabled(sink),
      "provider",
      Some("model"),
      Instant.now(),
      secret,
      Some(secret),
      Right("ok")
    )

    seen.head.requestBody shouldBe secret
    seen.head.responseBody shouldBe Some(secret)
  }

  "the recorder" should "record a failed result as an Error carrying the error's message" in {
    val seen = ListBuffer.empty[ProviderExchange]
    val sink = new ProviderExchangeSink { def record(ex: ProviderExchange): Unit = seen += ex }

    ProviderExchangeRecorder.record(
      ProviderExchangeLogging.enabled(sink),
      "provider",
      None,
      Instant.now(),
      "{}",
      None,
      Left(org.llm4s.error.AuthenticationError("provider", "bad key"))
    )

    seen.head.outcome shouldBe ProviderExchangeOutcome.Error
    seen.head.errorMessage.getOrElse("") should include("bad key")
  }

  it should "record a cancelled call as an Error, never as Cancelled (the guide lists this as a limitation)" in {
    val seen = ListBuffer.empty[ProviderExchange]
    val sink = new ProviderExchangeSink { def record(ex: ProviderExchange): Unit = seen += ex }

    ProviderExchangeRecorder.record(
      ProviderExchangeLogging.enabled(sink),
      "provider",
      None,
      Instant.now(),
      "{}",
      None,
      Left(CancelledError("the call"))
    )

    seen.head.outcome shouldBe ProviderExchangeOutcome.Error
  }

  it should "swallow whatever the sink throws" in {
    val sink = new ProviderExchangeSink {
      def record(ex: ProviderExchange): Unit = throw new IllegalStateException("the sink is down")
    }

    noException should be thrownBy ProviderExchangeRecorder.record(
      ProviderExchangeLogging.enabled(sink),
      "provider",
      None,
      Instant.now(),
      "{}",
      None,
      Right("ok")
    )
  }
}
