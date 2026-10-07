package org.llm4s.samples.scalacli

import com.sun.net.httpserver.{ HttpExchange, HttpServer }
import com.typesafe.config.ConfigFactory
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.config.ProviderConfig
import org.llm4s.llmconnect.model.{ Completion, Conversation, UserMessage }
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.{ ConfigObjectSource, ConfigSource }

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path, Paths }
import java.util.concurrent.atomic.AtomicReference
import scala.util.Try

/**
 * Keeps `modules/samples/scala-cli` and `docs/getting-started/scala-cli.md` honest.
 *
 * `hello.scala` is a standalone Scala CLI script, so sbt does not compile it. What this spec can check is what the
 * script depends on, against the API on this branch:
 *  - the committed `resources/application.conf` loads, its default section is the OpenAI one, and the
 *    documented override (`llm4s.providers.provider`) selects the Ollama one;
 *  - the same calls the script makes (`defaultProvider` section, model registry, `getClient`, `complete`), with the
 *    prompt read out of the script, work end to end against a fake Ollama server, so no key and no network is needed;
 *  - the three code blocks on the docs page are the committed files, so the page cannot drift from what was run.
 */
class ScalaCliQuickstartSpec extends AnyFlatSpec with Matchers {

  private val root: Path =
    Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .find(dir => Files.exists(dir.resolve("build.sbt")))
      .getOrElse(throw new IllegalStateException("build.sbt not found above the working directory"))

  private val sampleDir = root.resolve("modules").resolve("samples").resolve("scala-cli")
  private val pageFile  = root.resolve("docs").resolve("getting-started").resolve("scala-cli.md")
  private val confFile  = sampleDir.resolve("resources").resolve("application.conf")

  // A checkout with CRLF line endings must not make the comparisons below fail.
  private def read(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n")

  private val script = read(sampleDir.resolve("hello.scala"))
  private val page   = read(pageFile)

  private val answer = "Scala answers from the fake provider."

  /** The line the script sends: read from the script, so changing it there is what the fake server sees. */
  private val prompt: String =
    """UserMessage\("([^"]+)"\)""".r
      .findFirstMatchIn(script)
      .map(_.group(1))
      .getOrElse(fail("hello.scala has no UserMessage(\"...\") prompt"))

  private val latestRelease: String =
    """llm4s-core:(\S+)""".r
      .findFirstMatchIn(script)
      .map(_.group(1))
      .getOrElse(fail("hello.scala has no `llm4s-core:<version>` dependency"))

  // Which lines of a file or block are explanatory comments, not code (used by the page comparisons below).
  private val scalaComment: String => Boolean = line => line.startsWith("//") && !line.startsWith("//>")
  private val hoconComment: String => Boolean = _.startsWith("#")
  private val xmlComment: String => Boolean   = _.startsWith("<!--")

  // --- what the fake Ollama saw -------------------------------------------------------------------------------

  final private case class Seen(path: String, model: String, lastUser: String)

  private def withFakeOllama(test: (Int, AtomicReference[Option[Seen]]) => Any): Unit = {
    val server = HttpServer.create(new InetSocketAddress("localhost", 0), 0)
    val seen   = new AtomicReference[Option[Seen]](None)
    server.createContext(
      "/",
      (exchange: HttpExchange) => {
        val body     = ujson.read(new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8))
        val messages = body("messages").arr.toSeq
        val lastUser = messages.reverse.find(_("role").str == "user").map(_("content").str).getOrElse("")
        seen.set(Some(Seen(exchange.getRequestURI.getPath, body("model").str, lastUser)))
        val reply = ujson.Obj(
          "model"             -> body("model").str,
          "created_at"        -> "2026-10-08T00:00:00Z",
          "message"           -> ujson.Obj("role" -> "assistant", "content" -> answer),
          "done"              -> true,
          "prompt_eval_count" -> 9,
          "eval_count"        -> 14
        )
        val bytes = ujson.write(reply).getBytes(StandardCharsets.UTF_8)
        exchange.getResponseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.length.toLong)
        exchange.getResponseBody.write(bytes)
        exchange.close()
      }
    )
    server.start()
    val outcome = Try(test(server.getAddress.getPort, seen))
    server.stop(0)
    outcome.fold(error => throw error, _ => ())
  }

  // `${?OPENAI_API_KEY}` resolves to nothing when the variable is unset, as in a real run.
  private def committedConfig: ConfigObjectSource =
    ConfigSource.fromConfig(ConfigFactory.parseFile(confFile.toFile).resolve())

  /** The script's four steps, with the provider config chosen by the caller instead of `application.conf`. */
  private def runScript(config: ProviderConfig): Result[Completion] =
    for {
      registry <- Llm4sConfig.modelRegistryService()
      given ModelRegistryService = registry
      client     <- LLMConnect.getClient(config)
      completion <- client.complete(Conversation(Seq(UserMessage(prompt))))
    } yield completion

  // --- the files ---------------------------------------------------------------------------------------------

  "The Scala CLI sample" should "ship the three files the docs page lists, and name them in the script" in {
    Files.isRegularFile(sampleDir.resolve("hello.scala")) shouldBe true
    Files.isRegularFile(confFile) shouldBe true
    Files.isRegularFile(sampleDir.resolve("logback.xml")) shouldBe true

    script should include("//> using resourceDir resources")
    script should include("-Dlogback.configurationFile=logback.xml")
    script should include("//> using dep org.llm4s::llm4s-core:")
  }

  it should "make the calls the page describes, in order" in {
    val calls = Seq(
      "Llm4sConfig.defaultProvider()",
      "Llm4sConfig.modelRegistryService()",
      "LLMConnect.getClient(",
      "client.complete("
    )
    val positions = calls.map(script.indexOf)
    all(positions) should be >= 0
    positions shouldBe positions.sorted
  }

  // --- the configuration ---------------------------------------------------------------------------------------

  it should "load the committed application.conf with OpenAI as the default section" in {
    val withKey = ConfigSource.string("llm4s.providers.openai-main.apiKey = \"test-key\"").withFallback(committedConfig)

    val config = Llm4sConfig.providerFrom(withKey).fold(error => fail(error.formatted), identity)

    config.providerId.asString shouldBe "openai"
    config.model shouldBe "gpt-4o-mini"
  }

  it should "pick the Ollama section when llm4s.providers.provider is overridden, as the page documents" in {
    val overridden = ConfigSource.string("llm4s.providers.provider = \"ollama-local\"").withFallback(committedConfig)

    val config = Llm4sConfig.providerFrom(overridden).fold(error => fail(error.formatted), identity)

    config.providerId.asString shouldBe "ollama"
    config.model shouldBe "llama3"
  }

  // --- the run ---------------------------------------------------------------------------------------------------

  it should "complete end to end against a fake Ollama, sending the script's prompt, with no API key" in {
    withFakeOllama { (port, seen) =>
      val local = ConfigSource.string(s"""llm4s.providers.ollama-local.baseUrl = "http://localhost:$port"""")
      val config =
        Llm4sConfig
          .provider(local.withFallback(committedConfig), "ollama-local")
          .fold(error => fail(error.formatted), identity)

      val completion = runScript(config).fold(error => fail(error.formatted), identity)

      completion.content shouldBe answer
      seen.get() shouldBe Some(Seen("/api/chat", "llama3", prompt))
    }
  }

  // --- the page ----------------------------------------------------------------------------------------------------

  /** The first fenced block of a language on the page. */
  private def block(language: String): String =
    ("(?s)```" + language + "\n(.*?)```").r
      .findFirstMatchIn(page)
      .map(_.group(1))
      .getOrElse(fail(s"the page has no ```$language block"))

  /** The code of a file or block: no blank lines, and no comment-only lines (the files carry explanatory headers). */
  private def code(text: String, isComment: String => Boolean): Seq[String] =
    text.linesIterator.map(_.stripTrailing()).filterNot(line => line.isBlank || isComment(line.trim)).toSeq

  it should "match the page's hello.scala block, apart from the version placeholder" in {
    val onPage = block("scala").replace("{{ site.data.project.latest_release }}", latestRelease)

    code(onPage, scalaComment) shouldBe code(script, scalaComment)
  }

  it should "match the page's application.conf block" in {
    code(block("hocon"), hoconComment) shouldBe code(read(confFile), hoconComment)
  }

  it should "match the page's logback.xml block" in {
    code(block("xml"), xmlComment) shouldBe code(read(sampleDir.resolve("logback.xml")), xmlComment)
  }
}
