package org.llm4s.samples.guides

import org.llm4s.agent.Agent
import org.llm4s.agent.graph.GraphError
import org.llm4s.error.{ LLMError, ProcessingError, RateLimitError }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.reliability.{ ReliabilityConfig, ReliableClient }
import org.llm4s.llmconnect.model._
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }
import org.llm4s.toolapi._
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default._

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path, Paths }
import scala.annotation.tailrec
import scala.collection.mutable.ArrayBuffer

/**
 * The code of `docs/guide/testing-with-the-testkit.md`, compiled and run.
 *
 * Every `scala` block of the page is a run of lines of THIS file, word for word (indentation aside): the last
 * test reads the page and fails if a block is not found here, so the page cannot show code that no longer
 * compiles. Change the page and this spec together.
 */
class TestingWithTestkitGuideSpec extends AnyFlatSpec with Matchers with EitherValues {
  import TestingWithTestkitGuideSpec.*

  private val conversation = Conversation(Seq(UserMessage("Capital of France?")))
  private val options      = CompletionOptions()

  "The first test of the guide" should "script an answer and check what was sent" in {
    val client = ScriptedLLMClient.sequence(Reply.text("Paris"))
    val result = client.complete(Conversation(Seq(UserMessage("Capital of France?"))), CompletionOptions())

    result.map(_.content) shouldBe Right("Paris")
    client.callCount shouldBe 1

    client.calls.head.lastUserText shouldBe Some("Capital of France?")
  }

  "The agent example of the guide" should "run a tool call and the final answer end to end" in {
    val client = ScriptedLLMClient.sequence(
      Reply.toolCall("get_temperature", ujson.Obj("city" -> "Oslo")),
      Reply.text("It is 21 degrees in Oslo: pack light layers.")
    )

    packingAdvice(client, "Oslo") shouldBe Right("It is 21 degrees in Oslo: pack light layers.")

    client.callCount shouldBe 2
    client.calls.head.options.tools.map(_.name) shouldBe Seq("get_temperature")
    client.calls(1).messages.last.content should include("21")
  }

  "The failure examples of the guide" should "return the provider's error to the code under test" in {
    val failing = ScriptedLLMClient.sequence(Reply.failure(RateLimitError("openai")))

    val outcome = packingAdvice(failing, "Oslo")

    outcome.left.map(providerError) shouldBe Left(RateLimitError("openai"))
  }

  it should "retry through ReliableClient and then succeed" in {
    val flaky    = ScriptedLLMClient.sequence(Reply.failure(RateLimitError("openai")), Reply.text("recovered"))
    val reliable = new ReliableClient(flaky, "openai", ReliabilityConfig.default, sleep = _ => ())

    reliable.complete(conversation, options).map(_.content) shouldBe Right("recovered")
    flaky.callCount shouldBe 2
  }

  it should "say which call had no reply when the code makes more calls than were scripted" in {
    val message = ScriptedLLMClient.sequence().complete(conversation, options).left.value.message

    message should include("no reply is scripted for call 1")
    message should include("Capital of France?")
  }

  "The respondingTo example of the guide" should "choose the answer from the conversation" in {
    val client = ScriptedLLMClient.respondingTo {
      case c if c.messages.lastOption.exists(_.content.contains("Oslo"))  => Reply.text("cold")
      case c if c.messages.lastOption.exists(_.content.contains("Cairo")) => Reply.text("hot")
    }

    client.complete(Conversation(Seq(UserMessage("Weather in Cairo?"))), options).map(_.content) shouldBe Right("hot")
    client.complete(Conversation(Seq(UserMessage("Weather in Oslo?"))), options).map(_.content) shouldBe Right("cold")
  }

  "The streaming example of the guide" should "replay the answer as chunks" in {
    val client = ScriptedLLMClient.sequence(Reply.text("Hello, world")).withChunkSize(5)

    val chunks = ArrayBuffer.empty[StreamedChunk]
    client.streamComplete(conversation, options, chunks += _)

    chunks.flatMap(_.content).mkString shouldBe "Hello, world"
    chunks.last.finishReason shouldBe Some("stop")
  }

  "The page" should "show only code that this spec contains" in {
    val root = repositoryRoot.getOrElse(fail("no docs/guide above the working directory"))
    val page = read(root.resolve("docs/guide/testing-with-the-testkit.md"))
    val spec = read(
      root.resolve("modules/samples/src/test/scala/org/llm4s/samples/guides/TestingWithTestkitGuideSpec.scala")
    )

    val blocks = scalaBlocks(page)
    blocks should not be empty
    val specLines = "\n" + normalised(spec) + "\n"
    blocks.foreach { block =>
      withClue(s"This block of the page is not in the spec, line for line:\n$block\n") {
        specLines.contains("\n" + normalised(block) + "\n") shouldBe true
      }
    }
  }
}

object TestingWithTestkitGuideSpec {

  final case class Temperature(city: String, celsius: Double) derives ReadWriter

  val temperatureTool: Result[ToolFunction[Map[String, Any], Temperature]] =
    ToolBuilder[Map[String, Any], Temperature](
      "get_temperature",
      "Current temperature in a city, in Celsius.",
      Schema
        .`object`[Map[String, Any]]("Parameters")
        .withProperty(Schema.property("city", Schema.string("City name")))
    ).withHandler(params => params.getString("city").map(city => Temperature(city, 21.0))).buildSafe()

  def packingAdvice(client: LLMClient, city: String): Result[String] =
    for {
      tool   <- temperatureTool
      agent  <- Agent.builder("packing-assistant", client).withTools(new ToolRegistry(Seq(tool))).build()
      run    <- agent.run(s"What should I pack for $city today?")
      answer <- run.answer.toRight(ProcessingError("packing-advice", s"the run did not complete: ${run.status}"))
    } yield answer

  def providerError(error: LLMError): LLMError = error match {
    case GraphError.NodeFailed(_, _, cause) => cause
    case other                              => other
  }

  /** The lines of `text` with their indentation and blank lines removed, joined: how blocks and the spec are compared. */
  def normalised(text: String): String =
    text.linesIterator.map(_.trim).filter(_.nonEmpty).mkString("\n")

  /** The `scala` blocks of a Markdown page. */
  def scalaBlocks(page: String): List[String] = {
    val Fence = "^```scala\\s*$".r
    val lines = page.linesIterator.toList
    def loop(rest: List[String], acc: List[String]): List[String] = rest match {
      case Nil => acc.reverse
      case line :: tail if Fence.findFirstIn(line).isDefined =>
        val (body, after) = tail.span(!_.startsWith("```"))
        loop(after.drop(1), body.mkString("\n") :: acc)
      case _ :: tail => loop(tail, acc)
    }
    loop(lines, Nil)
  }

  def read(path: Path): String = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  /** The checkout's root: the nearest parent of the working directory that holds `docs/guide`. */
  def repositoryRoot: Option[Path] = {
    @tailrec def up(dir: Path): Option[Path] =
      if (Files.isDirectory(dir.resolve("docs/guide"))) Some(dir)
      else
        Option(dir.getParent) match {
          case Some(parent) => up(parent)
          case None         => None
        }
    up(Paths.get("").toAbsolutePath)
  }
}
