package org.llm4s.samples.guides

import org.llm4s.error.RateLimitError
import org.llm4s.llmconnect.{ EmbeddingClient, LLMClient }
import org.llm4s.llmconnect.config.EmbeddingModelConfig
import org.llm4s.llmconnect.model.{ EmbeddingRequest, EmbeddingResponse, MessageRole, ResponseFormat }
import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.llm4s.model.ModelRegistryService
import org.llm4s.rag.{ RAG, RAGConfig }
import org.llm4s.samples.guides.PythonDevelopersGuide._
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.types.Result
import org.scalatest.{ Args, EitherValues, Reporter }
import org.scalatest.events.{ Event, TestFailed, TestSucceeded }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path, Paths }
import scala.collection.mutable.ListBuffer
import scala.util.Using

/**
 * Runs the Scala snippets of `docs/guide/for-python-developers.md` (#2344), kept in [[PythonDevelopersGuide]], against
 * `llm4s-testkit`'s `ScriptedLLMClient`, with no provider and no network, and checks that the page shows exactly those
 * snippets, word for word. The Python on the page is fenced ```` ```python ```` and is never run: it is quoted from
 * each library's documentation, which the line under each quote links. `connect` needs a configured provider, so it
 * is compiled, not run.
 */
class PythonDevelopersGuideSpec extends AnyFlatSpec with Matchers with EitherValues {

  "1. A first call" should "send the question and return the answer as a Right" in {
    val client = ScriptedLLMClient.sequence(Reply.text("Scala is a JVM language."))

    FirstCall.ask(client, "What is Scala?").value shouldBe "Scala is a JVM language."
    client.calls.head.messages.map(m => (m.role, m.content)) shouldBe Seq(
      (MessageRole.System, "You answer in one sentence."),
      (MessageRole.User, "What is Scala?")
    )
  }

  it should "return a provider failure as a Left, not an exception" in {
    val error  = RateLimitError("openai")
    val client = ScriptedLLMClient.sequence(Reply.failure(error))

    FirstCall.ask(client, "What is Scala?").left.value shouldBe error
  }

  "2. Typed output" should "return the case class and send its derived schema" in {
    val client = ScriptedLLMClient.sequence(Reply.text("""{"country":"Norway","capital":"Oslo"}"""))

    TypedOutput.capitalOf(client, "Norway").value shouldBe TypedOutput.Capital("Norway", "Oslo")
    client.calls.head.messages.last.content shouldBe "Capital of Norway?"
    client.calls.head.options.responseFormat match {
      case Some(ResponseFormat.JsonSchema(schema, _, _)) =>
        schema.obj("properties").obj.keySet shouldBe Set("country", "capital")
      case other => fail(s"expected a JSON-Schema response format, got $other")
    }
  }

  it should "fail with a Left when the reply does not fit the case class" in {
    val client = ScriptedLLMClient.sequence(Reply.text("I do not know."))

    TypedOutput.capitalOf(client, "Norway").isLeft shouldBe true
  }

  "3. A tool" should "use the default when the optional argument is absent, and the value when it is given" in {
    val tool = ATool.searchDatabase.value

    tool.name shouldBe "search_database"
    tool.execute(ujson.Obj("query" -> "Ada")).value shouldBe ujson.Str("Found 10 results for 'Ada'")
    tool.execute(ujson.Obj("query" -> "Ada", "limit" -> 2)).value shouldBe ujson.Str("Found 2 results for 'Ada'")
    tool.execute(ujson.Obj("limit" -> 2)).isLeft shouldBe true
  }

  "4. An agent loop" should "run the tool, answer, and continue the same conversation" in {
    val client = ScriptedLLMClient.sequence(
      Reply.toolCall("search_database", """{"query": "Ada"}"""),
      Reply.text("Ada Lovelace and nine others."),
      Reply.toolCall("search_database", """{"query": "Ada", "limit": 2}"""),
      Reply.text("Ada Lovelace and Ada Byron.")
    )
    val tools = new ToolRegistry(Seq(ATool.searchDatabase.value))

    AgentLoop.twoTurns(client, tools).value shouldBe Seq("Ada Lovelace and nine others.", "Ada Lovelace and Ada Byron.")
    client.callCount shouldBe 4
    def toolResults(call: Int): Seq[String] =
      client.calls(call).messages.filter(_.role == MessageRole.Tool).map(_.content)
    toolResults(1).mkString should include("Found 10 results for 'Ada'")
    toolResults(3).mkString should include("Found 2 results for 'Ada'")
    // The second turn's request still carries the first turn: continueConversation keeps the thread.
    client.calls(2).messages.map(_.content) should contain("Find customers named Ada")
    client.calls(2).messages.last.content shouldBe "Only the first two, please"
  }

  "5. RAG" should "answer from the ingested text and list the chunks it retrieved" in {
    given ModelRegistryService = ModelRegistryService.default().value
    // A fixed embedding per text: retrieval ranking is not what this page shows.
    val embeddings = new EmbeddingProvider {
      def embed(request: EmbeddingRequest): Result[EmbeddingResponse] =
        Right(EmbeddingResponse(request.input.map(text => Seq(text.length.toDouble, 1.0))))
    }
    val client = ScriptedLLMClient.sequence(Reply.text("Within 14 days."))
    val rag = RAG
      .buildWithEmbeddings(
        RAGConfig.default.withLLM(client),
        new EmbeddingClient(embeddings),
        EmbeddingModelConfig("fixed", 2)
      )
      .value
    Using.resource(rag) { rag =>
      Rag.askTheDocs(rag, "When are refunds paid?").value shouldBe "Within 14 days."
      client.calls.head.messages.map(_.content).mkString("\n") should include("Refunds are paid within 14 days")
      (Rag.sources(rag, "When are refunds paid?").value should contain).allOf(
        "Refunds are paid within 14 days of a return.",
        "Orders over 50 EUR ship for free."
      )
    }
  }

  "6. Streaming" should "hand each piece of text to the callback and return the whole answer" in {
    val client = ScriptedLLMClient.sequence(Reply.text("Scala is a JVM language."))
    val pieces = ListBuffer.empty[String]

    Streaming.streamAnswer(client, "What is Scala?", text => pieces += text).value shouldBe "Scala is a JVM language."
    pieces.mkString shouldBe "Scala is a JVM language."
    client.calls.head.streamed shouldBe true
  }

  "7. Tests" should "pass when the page's ScalaTest class is run" in {
    val events   = ListBuffer.empty[Event]
    val reporter = new Reporter { def apply(event: Event): Unit = events += event }

    new Testing.AskSpec().run(None, Args(reporter)).succeeds() shouldBe true
    events.collect { case e: TestSucceeded => e.testName } shouldBe Seq("ask should return the model's answer")
    events.collect { case e: TestFailed => e } shouldBe empty
  }

  "8. Tooling" should "compile the connect snippet against the public API" in {
    // `connect` is not called: it needs a configured provider. That it type-checks is the point of this test.
    val connectors: Seq[() => Result[LLMClient]] = Seq(() => Tooling.connect())
    connectors should have size 1
  }

  // ---------------------------------------------------------------------------------------------------------------
  // The page (lazy: Scala's initialisation check refuses a test registered before a later plain val)
  // ---------------------------------------------------------------------------------------------------------------

  private lazy val root: Path =
    Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .find(dir => Files.exists(dir.resolve("build.sbt")) && Files.isDirectory(dir.resolve("docs")))
      .getOrElse(throw new IllegalStateException("the repository root was not found above the working directory"))

  private def read(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n")

  private lazy val snippetSource =
    read(root.resolve("modules/samples/src/test/scala/org/llm4s/samples/guides/PythonDevelopersGuide.scala"))
  private lazy val page = read(root.resolve("docs/guide/for-python-developers.md"))

  /** Removes the common indentation and the blank lines around a block. */
  private def normalise(block: String): String = {
    val lines  = block.split("\n", -1).toList.dropWhile(_.trim.isEmpty).reverse.dropWhile(_.trim.isEmpty).reverse
    val indent = lines.filter(_.trim.nonEmpty).map(l => l.length - l.stripLeading().length).minOption.getOrElse(0)
    lines.map(l => if (l.length >= indent) l.substring(indent) else l.trim).mkString("\n")
  }

  private def fenced(language: String): List[String] =
    s"(?ms)^```$language\n(.*?)^```$$".r.findAllMatchIn(page).map(m => normalise(m.group(1))).toList

  private lazy val regions: List[String] =
    """(?ms)^[ ]*// snippet: ([a-z0-9-]+)\n(.*?)^[ ]*// end snippet$""".r
      .findAllMatchIn(snippetSource)
      .map(m => normalise(m.group(2)))
      .toList

  "The page" should "show every snippet that this spec runs, in the same order, word for word" in {
    regions should have size 8
    fenced("scala") shouldBe regions
  }

  it should "quote a named, versioned Python library under every Python block, with a link to its source" in {
    val python = fenced("python")
    python should have size 7
    // Each Python block is followed by an attribution line: "*(From|Abridged from|Written with) [library version](url)".
    """(?ms)^```python\n.*?^```\n\n\*\((From|Abridged from|Written with) \[[^\]]+ \d+\.\d+[^\]]*\]\(https://""".r
      .findAllMatchIn(page)
      .size shouldBe python.size
  }

  it should "link a Scala 3 book page for each of the eight sections' Scala concept" in {
    val sections = page.split("\n## ").toList.filter(s => s.headOption.exists(_.isDigit))
    sections should have size 8
    sections.foreach { section =>
      withClue(section.linesIterator.next()) {
        """\*\*New in Scala:\*\*""".r.findAllMatchIn(section).size shouldBe 1
        section should include("https://docs.scala-lang.org/scala3/book/")
      }
    }
  }
}
