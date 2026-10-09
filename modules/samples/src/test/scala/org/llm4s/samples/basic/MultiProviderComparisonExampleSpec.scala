package org.llm4s.samples.basic

import com.sun.net.httpserver.{ HttpExchange, HttpServer }
import org.llm4s.config.Llm4sConfig
import org.llm4s.model.ModelRegistryService
import org.llm4s.samples.basic.MultiProviderComparisonExample.{ Entry, Reply }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.{ ConcurrentLinkedQueue, Executors }
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * The sample, run for real: the real client and configuration loading against fake OpenAI-format endpoints, one per
 * provider, so every answer, token count and failure below is known in advance.
 *
 *  - `alpha` answers "  Alpha says hi \n" and reports 12 tokens;
 *  - `beta` answers after 120 ms and reports 30 tokens;
 *  - `denied` answers 401;
 *  - `missing` is a name with no configuration section.
 */
class MultiProviderComparisonExampleSpec extends AnyFlatSpec with Matchers {

  private val Prompt   = "Say hi in one line."
  private val Delay    = 120.millis
  private val Registry = ModelRegistryService.fromModels(Nil)

  /** A fake provider: what it says, what it reports, how long it takes, and what it saw. */
  final private class Fake(text: String, totalTokens: Int, status: Int, delay: FiniteDuration) {
    val requests: ConcurrentLinkedQueue[ujson.Value] = new ConcurrentLinkedQueue[ujson.Value]()

    def seen: List[ujson.Value] = requests.asScala.toList

    def handle(exchange: HttpExchange): Unit = {
      requests.add(ujson.read(new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)))
      Thread.sleep(delay.toMillis)
      val reply: ujson.Value =
        if (status == 200)
          ujson.Obj(
            "id"      -> "chatcmpl-1",
            "object"  -> "chat.completion",
            "created" -> 1,
            "model"   -> "stub-model",
            "choices" -> ujson.Arr(
              ujson.Obj(
                "index"         -> 0,
                "message"       -> ujson.Obj("role" -> "assistant", "content" -> text),
                "finish_reason" -> "stop"
              )
            ),
            "usage" -> ujson.Obj(
              "prompt_tokens"     -> (totalTokens - 2),
              "completion_tokens" -> 2,
              "total_tokens"      -> totalTokens
            )
          )
        else
          ujson.Obj("error" -> ujson.Obj("message" -> "Incorrect API key provided", "type" -> "invalid_request_error"))
      val bytes = ujson.write(reply).getBytes(StandardCharsets.UTF_8)
      exchange.getResponseHeaders.add("Content-Type", "application/json")
      exchange.sendResponseHeaders(status, bytes.length.toLong)
      exchange.getResponseBody.write(bytes)
      exchange.getResponseBody.close()
    }
  }

  private def serve(fake: Fake, handlers: java.util.concurrent.ExecutorService): HttpServer = {
    val server = HttpServer.create(new InetSocketAddress("localhost", 0), 0)
    server.createContext("/", exchange => fake.handle(exchange))
    server.setExecutor(handlers)
    server.start()
    server
  }

  /** Runs `test` against the fake providers, with the configuration that points a section at each. */
  private def withProviders(
    test: (String => org.llm4s.types.Result[org.llm4s.llmconnect.config.ProviderConfig], Map[String, Fake]) => Any
  ): Unit = {
    val fakes = Map(
      "alpha"  -> new Fake("  Alpha says hi \n", 12, 200, Duration.Zero),
      "beta"   -> new Fake("Beta says hello", 30, 200, Delay),
      "denied" -> new Fake("", 0, 401, Duration.Zero)
    )
    val handlers = Executors.newCachedThreadPool()
    val servers  = fakes.map { case (name, fake) => name -> serve(fake, handlers) }

    val outcome = Try {
      def section(name: String): String =
        s"""  $name {
           |    provider = "openai-compatible"
           |    baseUrl  = "http://localhost:${servers(name).getAddress.getPort}/v1"
           |    model    = "stub-model"
           |    apiKey   = "test-key"
           |  }""".stripMargin
      val hocon = s"llm4s.providers {\n  provider = \"alpha\"\n${fakes.keys.map(section).mkString("\n")}\n}"
      val load  = (name: String) => Llm4sConfig.provider(ConfigSource.string(hocon), name)
      test(load, fakes)
    }
    servers.values.foreach(_.stop(0))
    handlers.shutdownNow(): Unit
    outcome.fold(error => throw error, _ => ())
  }

  private def compare(names: Seq[String])(
    load: String => org.llm4s.types.Result[org.llm4s.llmconnect.config.ProviderConfig]
  ) =
    MultiProviderComparisonExample.compare(names, Prompt)(load)(using Registry)

  private def reply(entry: Entry): Reply = entry.outcome.fold(error => fail(error.message), identity)

  "MultiProviderComparisonExample" should "ask every provider the same prompt and report each one's answer" in
    withProviders { (load, fakes) =>
      val entries = compare(Seq("alpha", "beta"))(load)

      entries.map(_.provider) shouldBe Seq("alpha", "beta")
      reply(entries.head).text shouldBe "Alpha says hi"
      reply(entries.head).tokens shouldBe Some(12)
      reply(entries(1)).text shouldBe "Beta says hello"
      reply(entries(1)).tokens shouldBe Some(30)

      // The same prompt reached both.
      Seq("alpha", "beta").foreach { name =>
        val messages = fakes(name).seen.map(_("messages").arr.map(_("content").str).toList)
        withClue(name)(messages shouldBe List(List(Prompt)))
      }
    }

  it should "keep the order of the names it was given" in withProviders { (load, _) =>
    compare(Seq("beta", "alpha"))(load).map(_.provider) shouldBe Seq("beta", "alpha")
  }

  it should "measure the call itself" in withProviders { (load, _) =>
    val entries = compare(Seq("alpha", "beta"))(load)

    // beta sleeps 120 ms before answering, so its call took at least that long. Only the lower bound is
    // asserted: a cold first call on a slow runner can take far longer than a sleeping one (430 ms against 120 ms
    // was seen on CI), so "alpha is faster than beta" says nothing about the code under test.
    reply(entries(1)).latency should be >= (Delay - 20.millis)
  }

  it should "time each call with its own start and end, not from the start of the comparison" in
    withProviders { (load, _) =>
      // Four readings: before and after alpha's call, before and after beta's.
      val readings = Iterator(1000L, 1000L + 7.millis.toNanos, 5000L, 5000L + 42.millis.toNanos)
      val entries = MultiProviderComparisonExample.compare(Seq("alpha", "beta"), Prompt, () => readings.next())(load)(
        using Registry
      )

      entries.map(reply(_).latency) shouldBe Seq(7.millis, 42.millis)
    }

  it should "report a provider that fails, and still run the others" in withProviders { (load, fakes) =>
    val entries = compare(Seq("denied", "alpha"))(load)

    entries.head.outcome.isLeft shouldBe true
    reply(entries(1)).text shouldBe "Alpha says hi"
    fakes("alpha").seen should have size 1
  }

  it should "say why a name could not be loaded instead of dropping it" in withProviders { (load, _) =>
    val entries = compare(Seq("missing", "alpha"))(load)

    entries.head.outcome.left.map(_.message.toLowerCase).left.getOrElse("") should (include("missing").and(
      include("not found")
    ))
    reply(entries(1)).text shouldBe "Alpha says hi"
  }

  it should "render the prompt, one block per provider, and the count that answered" in withProviders { (load, _) =>
    val entries = compare(Seq("alpha", "denied", "missing"))(load)
    val report  = MultiProviderComparisonExample.render(entries, Prompt)

    report should contain(s"Prompt: $Prompt")
    report should contain("-- alpha")
    report should contain("Alpha says hi")
    report.exists(_.startsWith("  tokens: 12, latency: ")) shouldBe true
    report should contain("-- denied")
    report should contain("-- missing")
    report.count(_.startsWith("  FAILED: ")) shouldBe 2
    report.last shouldBe "1 of 3 providers answered"
  }

  it should "render a provider that reports no usage" in {
    val report = MultiProviderComparisonExample.render(
      Seq(Entry("quiet", Right(Reply("Hello", None, 5.millis)))),
      Prompt
    )

    report should contain("  tokens: not reported, latency: 5ms")
  }

  it should "report an empty comparison without failing" in {
    MultiProviderComparisonExample.render(Nil, Prompt).last shouldBe "0 of 0 providers answered"
  }
}
