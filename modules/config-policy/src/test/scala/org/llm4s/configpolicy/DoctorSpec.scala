package org.llm4s.configpolicy

import com.sun.net.httpserver.{ HttpExchange, HttpHandler, HttpServer }
import org.llm4s.error.*
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import pureconfig.ConfigSource

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*
import scala.util.Using

class DoctorSpec extends AnyWordSpec with Matchers with OptionValues {

  // A key that must never appear anywhere in a report.
  private val SecretKey = "sk-doctor-SECRET-0123456789abcdef"

  /** Replaces the outside world and counts how often each piece was used. */
  final private class Probe(
    java: Int = 21,
    models: Result[List[String]] = Right(List("llama3:latest")),
    live: Result[String] = Right("fixture-model")
  ) {
    val listCalls = new AtomicInteger(0)
    val liveCalls = new AtomicInteger(0)

    val ports: DoctorPorts = DoctorPorts(
      () => java,
      (_, _) => { listCalls.incrementAndGet(); models },
      _ => { liveCalls.incrementAndGet(); live }
    )
  }

  private def source(hocon: String): ConfigSource = ConfigSource.string(hocon)

  private val localOllama =
    """llm4s.providers {
      |  provider = "local"
      |  local { provider = "ollama", model = "llama3", baseUrl = "http://localhost:11434" }
      |}""".stripMargin

  private def openAi(lines: String*): String =
    s"""llm4s.providers {
       |  provider = "main"
       |  main {
       |    provider = "openai"
       |    model = "gpt-4o-mini"
       |    ${lines.mkString("\n    ")}
       |  }
       |}""".stripMargin

  private def run(
    hocon: String,
    probe: Probe = new Probe(),
    options: DoctorOptions = DoctorOptions()
  ): DoctorReport =
    Doctor.run(source(hocon), options, probe.ports)

  private def check(report: DoctorReport, name: String): DoctorCheck =
    report.checks.find(_.name == name).getOrElse(fail(s"no check called '$name' in ${report.checks.map(_.name)}"))

  private def statuses(report: DoctorReport): List[(String, CheckStatus)] =
    report.checks.map(c => c.name -> c.status)

  "Doctor on a correct local setup" should {

    "pass every step it runs, skip the live call, and exit 0" in {
      val report = run(localOllama)

      statuses(report) shouldBe List(
        "JDK"              -> CheckStatus.Pass,
        "Configuration"    -> CheckStatus.Pass,
        "Default provider" -> CheckStatus.Pass,
        "Provider module"  -> CheckStatus.Pass,
        "API key"          -> CheckStatus.Pass,
        "Provider config"  -> CheckStatus.Pass,
        "Local server"     -> CheckStatus.Pass,
        "Live call"        -> CheckStatus.Skipped
      )
      report.exitCode shouldBe 0
      report.verdict shouldBe "pass"
    }

    "accept a model the server lists with the :latest tag, and one written with it" in {
      run(localOllama, new Probe(models = Right(List("llama3:latest")))).exitCode shouldBe 0
      run(
        localOllama.replace("\"llama3\"", "\"llama3:latest\""),
        new Probe(models = Right(List("llama3")))
      ).exitCode shouldBe 0
    }
  }

  "Doctor on the five broken setups of the issue" should {

    "report no section at all, and skip what needs one" in {
      val report = run("""llm4s.providers.provider = "main"""")

      check(report, "Configuration").status shouldBe CheckStatus.Fail
      check(report, "Configuration").fix.value should include("llm4s.providers")
      report.checks.drop(2).map(_.status).distinct shouldBe List(CheckStatus.Skipped)
      report.exitCode shouldBe 2
    }

    "report a missing API key with the exact way to set it" in {
      val report = run(openAi())

      check(report, "API key").status shouldBe CheckStatus.Fail
      check(report, "API key").detail should include("'main'")
      check(report, "API key").fix.value should include("apiKey under llm4s.providers.main")
      check(report, "Provider config").status shouldBe CheckStatus.Skipped
      check(report, "Live call").status shouldBe CheckStatus.Skipped
      report.exitCode shouldBe 2
    }

    "report an unknown provider id, naming the ones that are registered" in {
      val report = run("""llm4s.providers { provider = "main", main { provider = "no-such-vendor", model = "m" } }""")

      check(report, "Provider module").status shouldBe CheckStatus.Fail
      check(report, "Provider module").detail should include("no-such-vendor")
      check(report, "Provider module").fix.value should include("ollama")
      check(report, "API key").status shouldBe CheckStatus.Skipped
      report.exitCode shouldBe 2
    }

    "report a provider whose module is not on the classpath, and say to add the dependency" in {
      val ollamaOnly = ProviderRegistry.of(ProviderRegistry.default.find(ProviderId("ollama")).value)
      val report =
        Doctor.run(source(openAi(s"""apiKey = "$SecretKey"""")), DoctorOptions(), new Probe().ports)(using ollamaOnly)

      check(report, "Provider module").status shouldBe CheckStatus.Fail
      check(report, "Provider module").detail should include("add the dependency")
      check(report, "Provider module").fix.value should include("ollama")
      report.exitCode shouldBe 2
    }

    "report a local model server that is down, with the command to start it" in {
      val down   = new Probe(models = Left(NetworkError("connection refused", None, "http://localhost:11434")))
      val report = run(localOllama, down)

      check(report, "Local server").status shouldBe CheckStatus.Fail
      check(report, "Local server").detail should include("http://localhost:11434")
      check(report, "Local server").fix.value should include("ollama serve")
      report.exitCode shouldBe 2
    }

    "report a model the server does not have, with the pull command" in {
      val report = run(localOllama, new Probe(models = Right(List("mistral:latest"))))

      check(report, "Local server").status shouldBe CheckStatus.Fail
      check(report, "Local server").detail should (include("llama3").and(include("mistral:latest")))
      check(report, "Local server").fix.value should include("ollama pull llama3")
    }
  }

  "Doctor on the configuration" should {

    "report a default provider that is not set, naming the sections" in {
      val report =
        run("""llm4s.providers.main { provider = "ollama", model = "m", baseUrl = "http://localhost:11434" }""")

      check(report, "Default provider").status shouldBe CheckStatus.Fail
      check(report, "Default provider").detail should include("main")
      check(report, "Default provider").fix.value should include("llm4s.providers.provider")
    }

    "report a default that names a section that does not exist" in {
      val report = run(
        """llm4s.providers {
          |  provider = "ghost"
          |  real { provider = "ollama", model = "m", baseUrl = "http://localhost:11434" }
          |}""".stripMargin
      )

      check(report, "Default provider").status shouldBe CheckStatus.Fail
      check(report, "Default provider").detail should include("'ghost'")
      check(report, "Default provider").fix.value should include("real")
      check(report, "Provider module").status shouldBe CheckStatus.Skipped
    }

    "report a section with no provider key" in {
      val report = run("""llm4s.providers { provider = "main", main { model = "m" } }""")

      check(report, "Provider module").status shouldBe CheckStatus.Fail
      check(report, "Provider module").detail should include("no provider key")
    }

    "report configuration that cannot be parsed" in {
      val report = run("llm4s { providers = ")

      check(report, "Configuration").status shouldBe CheckStatus.Fail
      check(report, "Configuration").detail should include("could not be read")
      report.checks.drop(2).map(_.status).distinct shouldBe List(CheckStatus.Skipped)
      report.exitCode shouldBe 2
    }

    "report a section that is invalid beyond its key" in {
      val report = run(openAi(s"""apiKey = "$SecretKey"""").replace("model = \"gpt-4o-mini\"", ""))

      check(report, "API key").status shouldBe CheckStatus.Pass
      check(report, "Provider config").status shouldBe CheckStatus.Fail
      check(report, "Local server").status shouldBe CheckStatus.Skipped
      check(report, "Live call").status shouldBe CheckStatus.Skipped
    }
  }

  "Doctor on the JDK" should {

    "fail on a JDK older than 21 and say what to do" in {
      val report = run(localOllama, new Probe(java = 17))

      check(report, "JDK").status shouldBe CheckStatus.Fail
      check(report, "JDK").detail should (include("17").and(include("21")))
      check(report, "JDK").fix.value should include("JAVA_HOME")
      report.exitCode shouldBe 2
    }

    "pass on 21 and on a newer JDK" in {
      run(localOllama, new Probe(java = 21)).checks.head.status shouldBe CheckStatus.Pass
      run(localOllama, new Probe(java = 25)).checks.head.status shouldBe CheckStatus.Pass
    }
  }

  "Doctor on credentials" should {

    "show the path of the section's own key, never the value" in {
      val report = run(openAi(s"""apiKey = "$SecretKey""""))

      check(report, "API key").status shouldBe CheckStatus.Pass
      check(report, "API key").detail should include("llm4s.providers.main.apiKey")
    }

    "show the shared credentials path when the section sets no key" in {
      val report = run(openAi() + s"""\nllm4s.credentials.openai.apiKey = "$SecretKey"""")

      check(report, "API key").status shouldBe CheckStatus.Pass
      check(report, "API key").detail should include("llm4s.credentials.openai.apiKey")
    }

    "let the section's key win over the shared one in what it reports" in {
      val report =
        run(openAi(s"""apiKey = "$SecretKey"""") + s"""\nllm4s.credentials.openai.apiKey = "other-key-123456789"""")

      check(report, "API key").detail should include("llm4s.providers.main.apiKey")
    }

    "need no key for a provider that takes none" in {
      check(run(localOllama), "API key").detail should include("needs no API key")
    }

    "never put a key in the text report, the JSON report, or a fix hint, whichever step fails" in {
      val reports = List(
        run(openAi(s"""apiKey = "$SecretKey"""")),
        run(openAi(s"""apiKey = "$SecretKey"""").replace("model = \"gpt-4o-mini\"", "")),
        run(openAi() + s"""\nllm4s.credentials.openai.apiKey = "$SecretKey""""),
        run(openAi(s"""apiKey = "$SecretKey""""), options = DoctorOptions().withLive(true)),
        run(
          openAi(s"""apiKey = "$SecretKey""""),
          new Probe(live = Left(AuthenticationError("openai", s"bad key $SecretKey"))),
          DoctorOptions().withLive(true)
        )
      )

      reports.foreach { report =>
        (report.renderText should not).include(SecretKey)
        (report.renderJson should not).include(SecretKey)
        report.checks.foreach { c =>
          (c.detail should not).include(SecretKey)
          (c.fix.getOrElse("") should not).include(SecretKey)
        }
      }
    }
  }

  "Doctor hiding keys by value" should {

    "replace a key that a provider error echoes in a shape no redaction pattern knows" in {
      val echoing = new Probe(live = Left(AuthenticationError("openai", s"Incorrect API key provided: $SecretKey")))
      val report  = run(openAi(s"""apiKey = "$SecretKey""""), echoing, DoctorOptions().withLive(true))
      val live    = check(report, "Live call")

      live.detail should include("***")
      (live.detail should not).include(SecretKey)
    }

    "hide the shared credentials too, and the keys of sections that are not the default" in {
      val config = openAi() +
        s"""\nllm4s.credentials.openai.apiKey = "$SecretKey"\nllm4s.providers.other { provider = "openai", model = "m", apiKey = "second-account-key-98765" }"""
      val echoing =
        new Probe(live = Left(ServiceError(401, "openai", "keys second-account-key-98765 and " + SecretKey)))
      val live = check(run(config, echoing, DoctorOptions().withLive(true)), "Live call")

      (live.detail should not).include(SecretKey)
      (live.detail should not).include("second-account-key-98765")
    }

    "leave short values alone, so ordinary words are not mangled" in {
      val shortKey = "abc"
      val report = run(
        openAi(s"""apiKey = "$shortKey""""),
        new Probe(live = Left(ServiceError(500, "openai", "abc failed"))),
        DoctorOptions().withLive(true)
      )

      check(report, "Live call").detail should include("abc failed")
    }
  }

  "Doctor on what it contacts" should {

    "make no call to a hosted provider and no live request by default" in {
      val probe  = new Probe()
      val report = run(openAi(s"""apiKey = "$SecretKey""""), probe)

      probe.liveCalls.get shouldBe 0
      probe.listCalls.get shouldBe 0
      check(report, "Local server").status shouldBe CheckStatus.Skipped
      check(report, "Local server").detail should include("not a local server")
      check(report, "Live call").status shouldBe CheckStatus.Skipped
      report.exitCode shouldBe 0
    }

    "leave a local server alone with --offline" in {
      val probe  = new Probe()
      val report = run(localOllama, probe, DoctorOptions().withOffline(true))

      probe.listCalls.get shouldBe 0
      check(report, "Local server").status shouldBe CheckStatus.Skipped
      check(report, "Local server").detail should include("--offline")
    }

    "ask a local server which models it has, once, by default" in {
      val probe = new Probe()
      run(localOllama, probe)

      probe.listCalls.get shouldBe 1
      probe.liveCalls.get shouldBe 0
    }
  }

  "Doctor with a live request" should {

    val live = DoctorOptions().withLive(true)

    "pass when the provider answers, and name the model that did" in {
      val probe  = new Probe()
      val report = run(localOllama, probe, live)

      check(report, "Live call").status shouldBe CheckStatus.Pass
      check(report, "Live call").detail should include("fixture-model")
      probe.liveCalls.get shouldBe 1
      report.exitCode shouldBe 0
    }

    "map each kind of failure to its own advice" in {
      val cases: List[(LLMError, CheckStatus, String)] = List(
        (AuthenticationError("openai", "bad key"), CheckStatus.Fail, "key"),
        (RateLimitError("openai"), CheckStatus.Warn, "wait and retry"),
        (NotFoundError("no such model", "model"), CheckStatus.Fail, ".model"),
        (TimeoutError("slow", 5.seconds, "complete"), CheckStatus.Fail, "proxy"),
        (NetworkError("unreachable", None, "http://x"), CheckStatus.Fail, "baseUrl"),
        (ServiceError(500, "openai", "boom"), CheckStatus.Fail, "status page"),
        (ConfigurationError("odd"), CheckStatus.Fail, "")
      )

      cases.foreach { case (error, expected, advice) =>
        val result = check(run(localOllama, new Probe(live = Left(error)), live), "Live call")
        withClue(s"for ${error.getClass.getSimpleName}: ") {
          result.status shouldBe expected
          result.fix.getOrElse("") should include(advice)
        }
      }
    }

    "make a rate limit a warning with exit code 1" in {
      val report = run(localOllama, new Probe(live = Left(RateLimitError("openai"))), live)

      report.exitCode shouldBe 1
      report.verdict shouldBe "warn"
    }

    "report a request that does not answer within the wait" in {
      val release = new CountDownLatch(1)
      val ports = new Probe().ports.withLive { _ =>
        release.await()
        Right("late")
      }
      val report = Doctor.run(source(localOllama), live.withLiveTimeout(150.millis), ports)
      release.countDown()

      check(report, "Live call").status shouldBe CheckStatus.Fail
      check(report, "Live call").detail should include("no answer within 150 ms")
      report.exitCode shouldBe 2
    }

    "report a request that throws" in {
      val ports  = new Probe().ports.withLive(_ => throw new IllegalStateException("boom"))
      val report = Doctor.run(source(localOllama), live, ports)

      check(report, "Live call").status shouldBe CheckStatus.Fail
      check(report, "Live call").detail should include("IllegalStateException")
    }

    "not make the request when the provider config is invalid" in {
      val probe  = new Probe()
      val report = run(openAi(), probe, live)

      probe.liveCalls.get shouldBe 0
      check(report, "Live call").status shouldBe CheckStatus.Skipped
    }
  }

  "DoctorReport" should {

    "exit 0 for passes and skips, 1 for warnings, 2 for any failure" in {
      def exit(statuses: CheckStatus*) = DoctorReport(statuses.toList.map(DoctorCheck("c", _, "d"))).exitCode

      exit() shouldBe 0
      exit(CheckStatus.Pass, CheckStatus.Skipped) shouldBe 0
      exit(CheckStatus.Pass, CheckStatus.Warn) shouldBe 1
      exit(CheckStatus.Warn, CheckStatus.Fail) shouldBe 2
      exit(CheckStatus.Fail, CheckStatus.Pass) shouldBe 2
    }

    "render one line per step with its fix underneath, and a summary" in {
      val text = run(openAi()).renderText

      text should startWith("llm4s doctor")
      text should include("  PASS  JDK")
      text should include("  FAIL  API key")
      text should include("fix: set")
      text should include("Result: ")
      text.linesIterator.count(_.trim.startsWith("fix:")) shouldBe 1
    }

    "render JSON with the verdict, exit code and every step" in {
      val json = ujson.read(run(openAi()).renderJson)

      json("verdict").str shouldBe "fail"
      json("exitCode").num.toInt shouldBe 2
      json("checks").arr.map(_("name").str).toList shouldBe
        List(
          "JDK",
          "Configuration",
          "Default provider",
          "Provider module",
          "API key",
          "Provider config",
          "Local server",
          "Live call"
        )
      json("checks").arr.map(_("status").str).toList should contain("fail")
      val failed = json("checks").arr.find(_("name").str == "API key").value
      failed("fix").str should include("llm4s.providers.main")
      json("checks")(0)("fix") shouldBe ujson.Null
    }

    "redact anything credential-shaped that reaches a check" in {
      val check = DoctorCheck
        .fail("x", "rejected with Authorization: Bearer abcdef1234567890abcdef")
        .withFix("use sk-ant-api03-abcdefghijklmnopqrstuvwx")

      (check.detail should not).include("abcdef1234567890abcdef")
      (check.fix.value should not).include("abcdefghijklmnopqrstuvwx")
    }
  }

  "The default model probe" should {

    def withStub[A](models: String)(body: String => A): A = {
      val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
      server.createContext(
        "/api/tags",
        new HttpHandler {
          override def handle(exchange: HttpExchange): Unit = {
            val bytes = s"""{"models":$models}""".getBytes(StandardCharsets.UTF_8)
            exchange.getResponseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.length.toLong)
            Using.resource(exchange.getResponseBody)(_.write(bytes))
          }
        }
      )
      server.start()
      Using.resource(new AutoCloseable { override def close(): Unit = server.stop(0) })(_ =>
        body(s"http://127.0.0.1:${server.getAddress.getPort}")
      )
    }

    def config(baseUrl: String): String =
      s"""llm4s.providers { provider = "local", local { provider = "ollama", model = "llama3", baseUrl = "$baseUrl" } }"""

    val systemPorts = DoctorPorts.system.withJavaFeature(() => 21)

    "find the model on a real loopback server" in {
      withStub("""[{"name":"llama3:latest"},{"name":"mistral:latest"}]""") { url =>
        val report = Doctor.run(source(config(url)), DoctorOptions(), systemPorts)

        check(report, "Local server").status shouldBe CheckStatus.Pass
        check(report, "Local server").detail should include(url)
        report.exitCode shouldBe 0
      }
    }

    "report the models a real server has when the wanted one is missing" in {
      withStub("""[{"name":"mistral:latest"}]""") { url =>
        val report = Doctor.run(source(config(url)), DoctorOptions(), systemPorts)

        check(report, "Local server").status shouldBe CheckStatus.Fail
        check(report, "Local server").detail should include("mistral:latest")
      }
    }

    "report a server that is not listening" in {
      val url    = withStub("[]")(identity)
      val report = Doctor.run(source(config(url)), DoctorOptions(), systemPorts)

      check(report, "Local server").status shouldBe CheckStatus.Fail
      check(report, "Local server").detail should include("nothing usable answered")
    }
  }

  "DoctorCli" should {

    "read the flags" in {
      val invocation = DoctorCli.parse(Array("--live", "--offline", "--json", "--config", "app.conf", "--timeout", "7"))

      invocation.options.live shouldBe true
      invocation.options.offline shouldBe true
      invocation.options.liveTimeout shouldBe 7.seconds
      invocation.json shouldBe true
      invocation.config shouldBe Some("app.conf")
    }

    "default to no live call, no offline, a 30 second wait, text output and the application's own config" in {
      val invocation = DoctorCli.parse(Array.empty)

      invocation.options.live shouldBe false
      invocation.options.offline shouldBe false
      invocation.options.liveTimeout shouldBe 30.seconds
      invocation.json shouldBe false
      invocation.config shouldBe None
    }

    "ignore a timeout that is not a positive number" in {
      DoctorCli.parse(Array("--timeout", "abc")).options.liveTimeout shouldBe 30.seconds
      DoctorCli.parse(Array("--timeout", "0")).options.liveTimeout shouldBe 30.seconds
    }

    "check a --config file and return the text and exit code" in {
      val file = Files.createTempFile("doctor", ".conf")
      Files.writeString(file, localOllama)
      val (output, exitCode) = DoctorCli.execute(Array("--config", file.toString), new Probe().ports)
      Files.delete(file)

      exitCode shouldBe 0
      output should include("PASS  Local server")
    }

    "return JSON with --json, and the failing exit code" in {
      // A failure that no environment variable can fix: --config layers the file over every module's
      // reference.conf, which binds vendor variables such as OPENAI_API_KEY.
      val file = Files.createTempFile("doctor", ".conf")
      Files.writeString(
        file,
        """llm4s.providers.main { provider = "ollama", model = "m", baseUrl = "http://localhost:11434" }"""
      )
      val (output, exitCode) = DoctorCli.execute(Array("--config", file.toString, "--json"), new Probe().ports)
      Files.delete(file)

      exitCode shouldBe 2
      ujson.read(output)("verdict").str shouldBe "fail"
    }
  }
}
