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
import scala.jdk.CollectionConverters._
import scala.util.Try

/**
 * Keeps `modules/samples/scala-cli` and `docs/getting-started/scala-cli.md` honest.
 *
 * `hello.scala` is a standalone Scala CLI script, so sbt does not compile it. What this spec can check is what the
 * script depends on, against the API on this branch:
 *  - the committed `resources/application.conf` loads with the OpenAI section as its default, and
 *    `resources/ollama.conf`, the documented `-Dconfig.resource` alternative, with the Ollama one;
 *  - the same calls the script makes (`defaultProvider` section, model registry, `getClient`, `complete`), with the
 *    prompt read out of the script, work end to end against a fake Ollama server, so no key and no network is needed;
 *  - the code blocks on the docs page, rendered for the script's release, are the committed files, so the page cannot
 *    drift from what was run; rendered for 0.5.0 they add the provider modules and a logging backend.
 */
class ScalaCliQuickstartSpec extends AnyFlatSpec with Matchers {

  private val root: Path =
    Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .find(dir => Files.exists(dir.resolve("build.sbt")))
      .getOrElse(throw new IllegalStateException("build.sbt not found above the working directory"))

  private val sampleDir  = root.resolve("modules").resolve("samples").resolve("scala-cli")
  private val pageFile   = root.resolve("docs").resolve("getting-started").resolve("scala-cli.md")
  private val confFile   = sampleDir.resolve("resources").resolve("application.conf")
  private val ollamaFile = sampleDir.resolve("resources").resolve("ollama.conf")

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
  private def committedConfig: ConfigObjectSource = configOf(confFile)

  private def configOf(file: Path): ConfigObjectSource =
    ConfigSource.fromConfig(ConfigFactory.parseFile(file.toFile).resolve())

  /** The script's four steps, with the provider config chosen by the caller instead of `application.conf`. */
  private def runScript(config: ProviderConfig): Result[Completion] =
    for {
      registry <- Llm4sConfig.modelRegistryService()
      given ModelRegistryService = registry
      client     <- LLMConnect.getClient(config)
      completion <- client.complete(Conversation(Seq(UserMessage(prompt))))
    } yield completion

  // --- the files ---------------------------------------------------------------------------------------------

  "The Scala CLI sample" should "ship the files the docs page lists, and name them in the script" in {
    Files.isRegularFile(sampleDir.resolve("hello.scala")) shouldBe true
    Files.isRegularFile(confFile) shouldBe true
    Files.isRegularFile(sampleDir.resolve("logback.xml")) shouldBe true
    Files.isRegularFile(ollamaFile) shouldBe true

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

  it should "load ollama.conf, the -Dconfig.resource alternative, with the Ollama section as its default" in {
    val config = Llm4sConfig.providerFrom(configOf(ollamaFile)).fold(error => fail(error.formatted), identity)

    config.providerId.asString shouldBe "ollama"
    config.model shouldBe "llama3"
  }

  it should "keep one section per file, so a missing OpenAI key cannot stop the Ollama run" in {
    // 0.4.x validates every section of `llm4s.providers` when it loads the default one: an OpenAI section without a
    // key next to the Ollama one failed the no-key run with "missing required fields: apiKey".
    def sections(file: Path): Set[String] =
      ConfigFactory.parseFile(file.toFile).getConfig("llm4s.providers").root().keySet().asScala.toSet - "provider"

    sections(confFile) shouldBe Set("openai-main")
    sections(ollamaFile) shouldBe Set("ollama-local")
  }

  // --- the run ---------------------------------------------------------------------------------------------------

  it should "complete end to end against a fake Ollama, sending the script's prompt, with no API key" in {
    withFakeOllama { (port, seen) =>
      val local = ConfigSource.string(s"""llm4s.providers.ollama-local.baseUrl = "http://localhost:$port"""")
      val config =
        Llm4sConfig
          .providerFrom(local.withFallback(configOf(ollamaFile)))
          .fold(error => fail(error.formatted), identity)

      val completion = runScript(config).fold(error => fail(error.formatted), identity)

      completion.content shouldBe answer
      seen.get() shouldBe Some(Seen("/api/chat", "llama3", prompt))
    }
  }

  // --- the page ----------------------------------------------------------------------------------------------------

  /** The fenced blocks of a language on a page, in order. */
  private def blocks(language: String, text: String = page): Seq[String] =
    ("(?s)```" + language + "\n(.*?)```").r.findAllMatchIn(text).map(_.group(1)).toSeq

  /** The first fenced block of a language on the page. */
  private def block(language: String): String =
    blocks(language).headOption.getOrElse(fail(s"the page has no ```$language block"))

  private lazy val releaseTag = "{{ site.data.project.latest_release }}"
  private lazy val from050    = """{%- unless site.data.project.latest_release contains "0.4." %}"""
  private lazy val endFrom050 = "{%- endunless %}"

  /**
   * What Jekyll renders for a release: the version placeholder, and the lines kept only from 0.5.0 on. The `-` in
   * `{%-` removes the line break before each tag, as Liquid's whitespace control does.
   */
  private def render(text: String, release: String): String = {
    val pattern = ("(?s)\n" + java.util.regex.Pattern.quote(from050) + "(.*?)\n" +
      java.util.regex.Pattern.quote(endFrom050)).r
    val kept = pattern.replaceAllIn(
      text,
      m => java.util.regex.Matcher.quoteReplacement(if (release.contains("0.4.")) "" else m.group(1))
    )
    kept.replace(releaseTag, release)
  }

  private def usingDeps(text: String): Seq[String] =
    text.linesIterator.map(_.trim).filter(_.startsWith("//> using dep ")).map(_.stripPrefix("//> using dep ")).toSeq

  private lazy val logbackVersion: String =
    """val logback\s*=\s*"([^"]+)"""".r
      .findFirstMatchIn(read(root.resolve("project").resolve("Dependencies.scala")))
      .map(_.group(1))
      .getOrElse(fail("no `val logback` in project/Dependencies.scala"))

  /** The code of a file or block: no blank lines, and no comment-only lines (the files carry explanatory headers). */
  private def code(text: String, isComment: String => Boolean): Seq[String] =
    text.linesIterator.map(_.stripTrailing()).filterNot(line => line.isBlank || isComment(line.trim)).toSeq

  it should "match the page's hello.scala block, rendered for the script's release" in {
    latestRelease should startWith("0.4.")
    code(render(block("scala"), latestRelease), scalaComment) shouldBe code(script, scalaComment)
  }

  it should "add the provider modules and a logging backend to the page's script once 0.5.0 is the latest release" in {
    val deps = usingDeps(render(block("scala"), "0.5.0"))

    deps shouldBe Seq(
      "org.llm4s::llm4s-core:0.5.0",
      "org.llm4s::llm4s-openai:0.5.0",
      "org.llm4s::llm4s-ollama:0.5.0",
      s"ch.qos.logback:logback-classic:$logbackVersion"
    )
    // Rendered for 0.4.x, the one dependency is core: the provider modules are not published for it.
    usingDeps(render(block("scala"), "0.4.1")) shouldBe Seq("org.llm4s::llm4s-core:0.4.1")
  }

  it should "add a provider module to the installation page's Scala CLI lines from 0.5.0 on" in {
    val installation = read(root.resolve("docs").resolve("getting-started").resolve("installation.md"))
    val cli = blocks("scala", installation)
      .find(_.contains("//> using dep org.llm4s::llm4s-core"))
      .getOrElse(fail("installation.md has no Scala CLI block"))

    usingDeps(render(cli, "0.4.1")) shouldBe Seq("org.llm4s::llm4s-core:0.4.1")
    usingDeps(render(cli, "0.5.0")) shouldBe Seq("org.llm4s::llm4s-core:0.5.0", "org.llm4s::llm4s-openai:0.5.0")
  }

  it should "match the page's application.conf and ollama.conf blocks" in {
    val hocon = blocks("hocon")
    hocon should have size 2
    code(hocon(0), hoconComment) shouldBe code(read(confFile), hoconComment)
    code(hocon(1), hoconComment) shouldBe code(read(ollamaFile), hoconComment)
  }

  it should "document the run commands the script's header gives" in {
    page should include("scala-cli run . --java-opt -Dconfig.resource=ollama.conf")
    script should include("scala-cli run . --java-opt -Dconfig.resource=ollama.conf")
  }

  it should "match the page's logback.xml block" in {
    code(block("xml"), xmlComment) shouldBe code(read(sampleDir.resolve("logback.xml")), xmlComment)
  }
}
