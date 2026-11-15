package org.llm4s.samples.guides

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.llm4s.agent.Agent
import org.llm4s.config.Llm4sConfig
import org.llm4s.effect.cats.LLMClientIO
import org.llm4s.llmconnect.{ LLMClient, LLMConnect }
import org.llm4s.llmconnect.model._
import org.llm4s.model.ModelRegistryService
import org.llm4s.schema.*
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }
import org.llm4s.toolapi.{ ToolFunction, ToolRegistry }
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.ReadWriter

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path, Paths }

/**
 * Compiles and runs the llm4s snippets of `docs/guide/migrating-from-scala-llm-clients.md` (#2343).
 *
 * Each snippet of the guide sits between a `// snippet: <name>` and an `// end snippet` comment, and the last tests
 * compare those regions with the page's ```` ```scala ```` blocks, word for word, so neither copy can change without
 * the other. The code the page quotes from sttp-ai and openai-scala-client is fenced with `~~~scala`, which this spec
 * does not read: it is those libraries' code, not llm4s's. The model is `llm4s-testkit`'s `ScriptedLLMClient`, so
 * the checks are on what llm4s sends and returns, never on what a model says. The two configuration snippets need a
 * provider, so they are compiled (as the bodies of `def`s) but not run.
 */
class ScalaClientMigrationGuideSpec extends AnyFlatSpec with Matchers with EitherValues {

  // ---------------------------------------------------------------------------------------------------------------
  // 1. One completion
  // ---------------------------------------------------------------------------------------------------------------

  // snippet: connect
  def connect(): Result[LLMClient] = for {
    providerConfig  <- Llm4sConfig.defaultProvider()
    registryService <- Llm4sConfig.modelRegistryService()
    given ModelRegistryService = registryService
    client <- LLMConnect.getClient(providerConfig)
  } yield client
  // end snippet
  // (compiled, not run: it needs a configured provider.)

  "The completion snippet" should "send a system and a user message and return the reply text" in {
    val client = ScriptedLLMClient.sequence(Reply.text("Scala is a JVM language."))

    // snippet: chat
    val reply: Result[String] = for {
      conversation <- Conversation.fromPrompts("You answer in one sentence.", "What is Scala?")
      completion   <- client.complete(conversation)
    } yield completion.content
    // end snippet

    reply.value shouldBe "Scala is a JVM language."
    client.callCount shouldBe 1
    client.calls.head.messages.map(m => (m.role, m.content)) shouldBe Seq(
      (MessageRole.System, "You answer in one sentence."),
      (MessageRole.User, "What is Scala?")
    )
  }

  // ---------------------------------------------------------------------------------------------------------------
  // 2. Typed output
  // ---------------------------------------------------------------------------------------------------------------

  // snippet: capital-type
  case class Capital(country: String, capital: String) derives SchemaOf, ReadWriter
  // end snippet

  "The typed output snippet" should "return a case class and send its derived schema as the response format" in {
    val client = ScriptedLLMClient.sequence(Reply.text("""{"country":"Norway","capital":"Oslo"}"""))

    // snippet: structured
    val capital: Result[Capital] = for {
      conversation <- Conversation.fromPrompts("Answer with the country and its capital.", "Capital of Norway?")
      parsed       <- client.completeStructuredOf[Capital](conversation)
    } yield parsed
    // end snippet

    capital.value shouldBe Capital("Norway", "Oslo")
    client.calls.head.options.responseFormat match {
      case Some(ResponseFormat.JsonSchema(schema, _, strict)) =>
        strict shouldBe true
        schema.obj("properties").obj.keySet shouldBe Set("country", "capital")
        schema.obj("required").arr.map(_.str).toSet shouldBe Set("country", "capital")
      case other => fail(s"expected a JSON-Schema response format, got $other")
    }
  }

  it should "fail with a Left, not an exception, when the reply is not the requested shape" in {
    val client = ScriptedLLMClient.sequence(Reply.text("I do not know."))

    val capital = for {
      conversation <- Conversation.fromPrompts("Answer with the country and its capital.", "Capital of Norway?")
      parsed       <- client.completeStructuredOf[Capital](conversation)
    } yield parsed

    capital.isLeft shouldBe true
  }

  // ---------------------------------------------------------------------------------------------------------------
  // 3. A tool, run by an agent
  // ---------------------------------------------------------------------------------------------------------------

  // snippet: tool
  case class WeatherInput(location: String) derives SchemaOf, ReadWriter
  case class Weather(location: String, report: String) derives ReadWriter

  lazy val weatherTool: Result[ToolFunction[WeatherInput, Weather]] =
    Tool.of[WeatherInput, Weather]("get_weather", "Get the current weather for a location") { input =>
      Right(Weather(input.location, "22°C, sunny"))
    }
  // end snippet

  "The agent snippet" should "offer the tool, run it, and feed its result back to the model" in {
    // snippet: scripted-client
    val client = ScriptedLLMClient.sequence(
      Reply.toolCall("get_weather", """{"location": "Paris"}"""),
      Reply.text("It is 22°C and sunny in Paris.")
    )
    // end snippet

    // snippet: agent-run
    val answer: Result[Option[String]] = for {
      tool   <- weatherTool
      agent  <- Agent.builder("weather-agent", client).withTools(new ToolRegistry(Seq(tool))).build()
      result <- agent.run("What's the weather in Paris?")
    } yield result.answer
    // end snippet

    answer.value shouldBe Some("It is 22°C and sunny in Paris.")
    client.callCount shouldBe 2
    // The model was offered the tool with the first request ...
    client.calls.head.options.tools.map(_.name) should contain("get_weather")
    // ... and the second request carries what the tool returned.
    val toolResults = client.calls(1).messages.filter(_.role == MessageRole.Tool).map(_.content)
    toolResults should have size 1
    toolResults.head should include("22°C, sunny")
    toolResults.head should include("Paris")
  }

  // ---------------------------------------------------------------------------------------------------------------
  // 4. Streaming
  // ---------------------------------------------------------------------------------------------------------------

  "The streaming snippet" should "hand each chunk to the callback and return the whole completion" in {
    val client = ScriptedLLMClient.sequence(Reply.text("Scala is a JVM language."))

    // snippet: stream
    val shown = new StringBuilder
    val streamed: Result[String] = for {
      conversation <- Conversation.fromPrompts("You answer in one sentence.", "What is Scala?")
      completion   <- client.streamComplete(conversation, CompletionOptions(), _.content.foreach(shown.append))
    } yield completion.content
    // end snippet

    streamed.value shouldBe "Scala is a JVM language."
    shown.toString shouldBe "Scala is a JVM language."
    client.calls.head.streamed shouldBe true
  }

  "The fs2 snippet" should "stream the chunks as an fs2 Stream" in {
    val client       = ScriptedLLMClient.sequence(Reply.text("Scala is a JVM language."))
    val conversation = Conversation.fromPrompts("You answer in one sentence.", "What is Scala?").value

    // snippet: stream-fs2
    val text: IO[String] =
      LLMClientIO[IO](client).streamComplete(conversation).map(_.content.getOrElse("")).compile.string
    // end snippet

    text.unsafeRunSync() shouldBe "Scala is a JVM language."
    client.calls.head.streamed shouldBe true
  }

  // ---------------------------------------------------------------------------------------------------------------
  // 5. Switching provider
  // ---------------------------------------------------------------------------------------------------------------

  // snippet: named-provider
  def connectTo(section: String): Result[LLMClient] = for {
    providerConfig  <- Llm4sConfig.provider(section)
    registryService <- Llm4sConfig.modelRegistryService()
    given ModelRegistryService = registryService
    client <- LLMConnect.getClient(providerConfig)
  } yield client
  // end snippet
  // (compiled, not run: it needs a configured provider.)

  "The configuration snippets" should "compile against the public API" in {
    // `connect` and `connectTo` are not called: they would need a configured provider. That they type-check is the
    // point of this test, and the references keep the compiler from treating them as unused.
    val connectors: Seq[() => Result[LLMClient]] = Seq(() => connect(), () => connectTo("claude"))
    connectors should have size 2
  }

  // ---------------------------------------------------------------------------------------------------------------
  // The page (lazy: Scala's initialisation check refuses a test registered before a later plain val)
  // ---------------------------------------------------------------------------------------------------------------

  private lazy val root: Path =
    Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .find(dir => Files.exists(dir.resolve("build.sbt")))
      .getOrElse(throw new IllegalStateException("build.sbt not found above the working directory"))

  private def read(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n")

  private lazy val specSource = read(
    root.resolve("modules/samples/src/test/scala/org/llm4s/samples/guides/ScalaClientMigrationGuideSpec.scala")
  )
  private lazy val page = read(root.resolve("docs/guide/migrating-from-scala-llm-clients.md"))

  /**
   * Removes the common indentation and the blank lines around a block, and reads `lazy val` as `val`: the spec needs
   * `lazy` on class-level vals for Scala 3's initialisation check, while the page shows ordinary code.
   */
  private def normalise(block: String): String = {
    val lines  = block.split("\n", -1).toList.dropWhile(_.trim.isEmpty).reverse.dropWhile(_.trim.isEmpty).reverse
    val indent = lines.filter(_.trim.nonEmpty).map(l => l.length - l.stripLeading().length).minOption.getOrElse(0)
    lines
      .map(l => if (l.length >= indent) l.substring(indent) else l.trim)
      .map(_.replace("lazy val ", "val "))
      .mkString("\n")
  }

  private lazy val regions: List[String] =
    """(?ms)^[ ]*// snippet: ([a-z0-9-]+)\n(.*?)^[ ]*// end snippet$""".r
      .findAllMatchIn(specSource)
      .map(m => normalise(m.group(2)))
      .toList

  private lazy val pageBlocks: List[String] =
    """(?ms)^```scala\n(.*?)^```$""".r.findAllMatchIn(page).map(m => normalise(m.group(1))).toList

  private def isImportBlock(block: String): Boolean =
    block.linesIterator.forall(l => l.trim.isEmpty || l.startsWith("import "))

  "The migration guide" should "show every snippet of this spec, in the same order, word for word" in {
    regions should have size 10
    pageBlocks.filterNot(isImportBlock) shouldBe regions
  }

  it should "list only imports that this spec, which compiles the snippets, also uses" in {
    val pageImports = pageBlocks.filter(isImportBlock).flatMap(_.linesIterator.filter(_.nonEmpty))
    pageImports should not be empty
    val specImports = specSource.linesIterator.filter(_.startsWith("import ")).toSet
    pageImports.filterNot(specImports.contains) shouldBe empty
  }

  it should "fence the other libraries' code with ~~~scala, so that it is never mistaken for compiled llm4s code" in {
    val quoted = """(?ms)^~~~scala\n(.*?)^~~~$""".r.findAllMatchIn(page).map(_.group(1)).toList
    quoted should have size 5
    // Nothing that llm4s compiles is quoted as another library's code, and every quote is followed by its source.
    quoted.foreach(block => regions.foreach(region => (block should not).include(region)))
    """(?ms)^~~~\n\n\*\((From|Abridged from) \[""".r.findAllMatchIn(page).size shouldBe quoted.size
  }
}
