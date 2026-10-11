package org.llm4s.pekko

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.KillSwitches
import org.apache.pekko.stream.scaladsl.{ Keep, Sink }
import org.llm4s.agent.AgentResult
import org.llm4s.agent.events.AgentEvents
import org.llm4s.agent.graph.ThreadId
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ Completion, Conversation, StreamedChunk, UserMessage }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.File
import scala.concurrent.{ ExecutionContext, Future }
import scala.io.Source
import scala.util.Using

/**
 * Keeps `docs/guide/pekko.md` true. Every snippet of the page is compiled here exactly as the page shows it, each of
 * its lines must still be on the page, and the buffer sizes the page states must be the ones in the code. How the
 * stream behaves (order, backpressure, cancellation, errors) is the subject of the other specs of this module.
 */
class PekkoGuideSpec extends AnyFlatSpec with Matchers {

  // --- the snippets of the guide, compiled (they need a provider, so they are not run) ---------------------

  def wrap(client: LLMClient): LLMClientPekko = LLMClientPekko(client)

  def printAnswer(client: LLMClientPekko)(using system: ActorSystem) =
    client
      .streamComplete(Conversation(Seq(UserMessage("Explain monads in one sentence."))))
      .runForeach(chunk => print(chunk.content.getOrElse("")))

  def cancellable(client: LLMClientPekko, conversation: Conversation)(using system: ActorSystem) =
    client
      .streamComplete(conversation)
      .viaMat(KillSwitches.single[StreamedChunk])(Keep.right)
      .toMat(Sink.foreach(chunk => print(chunk.content.getOrElse(""))))(Keep.both)
      .run()

  def complete(client: LLMClientPekko, conversation: Conversation)(using ec: ExecutionContext): Future[Completion] =
    client.complete(conversation)

  def streamAnswer(agent: AgentPekko)(using system: ActorSystem) =
    agent
      .stream(ThreadId("chat-1"), "What is the capital of France?")
      .runForeach {
        case AgentStreamItem.Event(AgentEvents.TextDelta(delta)) => print(delta.text)
        case AgentStreamItem.Done(result)                        => println(s"\nDone: ${result.answer}")
        case _                                                   => ()
      }

  def ask(agent: AgentPekko, question: String)(using ec: ExecutionContext): Future[AgentResult] =
    agent.run(question)

  def answerOrNothing(client: LLMClientPekko, conversation: Conversation)(using
    system: ActorSystem,
    ec: ExecutionContext
  ) =
    client
      .streamComplete(conversation)
      .runWith(Sink.seq)
      .map(chunks => chunks.flatMap(_.content).mkString)
      .recover { case e: LLMException => s"failed: ${e.error.message}" }

  /** The lines of the snippets as the page shows them; the page must contain each. */
  private val SnippetLines = Seq(
    "import org.llm4s.llmconnect.LLMClient",
    "import org.llm4s.pekko.LLMClientPekko",
    "def wrap(client: LLMClient): LLMClientPekko = LLMClientPekko(client)",
    "import org.apache.pekko.actor.ActorSystem",
    "import org.llm4s.llmconnect.model.{ Conversation, UserMessage }",
    "def printAnswer(client: LLMClientPekko)(using system: ActorSystem) =",
    ".streamComplete(Conversation(Seq(UserMessage(\"Explain monads in one sentence.\"))))",
    ".runForeach(chunk => print(chunk.content.getOrElse(\"\")))",
    "import org.apache.pekko.stream.KillSwitches",
    "import org.apache.pekko.stream.scaladsl.{ Keep, Sink }",
    "import org.llm4s.llmconnect.model.StreamedChunk",
    "def cancellable(client: LLMClientPekko, conversation: Conversation)(using system: ActorSystem) =",
    ".viaMat(KillSwitches.single[StreamedChunk])(Keep.right)",
    ".toMat(Sink.foreach(chunk => print(chunk.content.getOrElse(\"\"))))(Keep.both)",
    "import org.llm4s.llmconnect.model.Completion",
    "import scala.concurrent.{ ExecutionContext, Future }",
    "def complete(client: LLMClientPekko, conversation: Conversation)(using ec: ExecutionContext): Future[Completion] =",
    "import org.llm4s.agent.events.AgentEvents",
    "import org.llm4s.agent.graph.ThreadId",
    "import org.llm4s.pekko.{ AgentPekko, AgentStreamItem }",
    "def streamAnswer(agent: AgentPekko)(using system: ActorSystem) =",
    ".stream(ThreadId(\"chat-1\"), \"What is the capital of France?\")",
    "case AgentStreamItem.Event(AgentEvents.TextDelta(delta)) => print(delta.text)",
    "case AgentStreamItem.Done(result)                        => println(s\"\\nDone: ${result.answer}\")",
    "import org.llm4s.agent.AgentResult",
    "def ask(agent: AgentPekko, question: String)(using ec: ExecutionContext): Future[AgentResult] =",
    "import org.apache.pekko.stream.scaladsl.Sink",
    "import org.llm4s.pekko.LLMException",
    "import scala.concurrent.ExecutionContext",
    "def answerOrNothing(client: LLMClientPekko, conversation: Conversation)(using system: ActorSystem, ec: ExecutionContext) =",
    ".map(chunks => chunks.flatMap(_.content).mkString)",
    ".recover { case e: LLMException => s\"failed: ${e.error.message}\" }"
  )

  private val GuidePath = "docs/guide/pekko.md"

  /** The guide, found by walking up from the working directory (sbt runs tests from the build root, or the module). */
  private lazy val guide: String = {
    val start = new File(".").getAbsoluteFile
    val file = Iterator
      .iterate(Option(start))(_.flatMap(f => Option(f.getParentFile)))
      .takeWhile(_.isDefined)
      .flatten
      .map(dir => new File(dir, GuidePath))
      .find(_.isFile)
      .getOrElse(fail(s"cannot find $GuidePath above ${start.getPath}"))
    Using.resource(Source.fromFile(file, "UTF-8"))(_.mkString)
  }

  "The Pekko guide" should "contain every line of the snippets this spec compiles" in {
    val lines = guide.linesIterator.map(_.trim).toSet
    SnippetLines.filterNot(lines.contains) shouldBe empty
  }

  it should "state the default buffer sizes the code uses" in {
    guide should include(s"while `bufferSize` chunks (${LLMClientPekko.DefaultBufferSize} unless you pass another)")
    guide should include(s"`bufferSize` (${AgentPekko.DefaultBufferSize}\n   unless you pass another)")
  }

  it should "name the module's dependencies as the build declares them" in {
    guide should include("`llm4s-core`, `llm4s-agent` and `pekko-stream` 1.x (Apache-2.0)")
  }

  it should "have the front matter of a User Guide page" in {
    guide should startWith("---\nlayout: page\ntitle: Apache Pekko Integration\nparent: User Guide\n")
  }

  it should "link only without the .md extension, which the site does not serve" in {
    val withoutCode = "(?s)```.*?```".r.replaceAllIn(guide, "")
    "\\]\\([^)#]*\\.md[^)]*\\)".r.findAllIn(withoutCode).toList shouldBe empty
  }
}
