package org.llm4s.pekko

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.Sink
import org.llm4s.agent.Agent
import org.llm4s.agent.delegation.TypedAgent
import org.llm4s.agent.events.{ AgentEvents, SubagentEvents }
import org.llm4s.agent.graph.{ EventHandlers, ThreadId }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.*
import org.llm4s.types.Result
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.{ CountDownLatch, TimeUnit }
import scala.concurrent.Await
import scala.concurrent.duration.*

/** `AgentPekko.nestedEvents`: a delegating run's children's events, through `EventHandlers.onChild` (design 4.47). */
class AgentPekkoNestedEventsSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  import AgentPekkoNestedEventsSpec.*

  implicit private val system: ActorSystem = ActorSystem("agent-pekko-nested-spec")

  override def afterAll(): Unit = {
    Await.result(system.terminate(), 30.seconds): Unit
    super.afterAll()
  }

  private def ok[A](result: Result[A]): A = result.fold(e => fail(e.message), identity)

  "AgentPekko.nestedEvents" should "stream a child's events, matched by onChild, before the parent's TaskEnded" in {
    val subscribed = new CountDownLatch(1)
    val agent      = delegating(subscribed)
    val run        = ok(agent.start(ThreadId("pn1"), "go"))
    val handlers = EventHandlers
      .onChild(AgentEvents.TextDelta)((child, d) => s"${child.agent.value}:${d.text}")
      .on(SubagentEvents.TaskEnded)(e => s"ended:${e.status}")
      .otherwise { _ =>
        subscribed.countDown()
        "other"
      }
    val seen =
      Await.result(AgentPekko(agent).nestedEvents(run, handlers).filter(_ != "other").runWith(Sink.seq), 30.seconds)

    seen.last shouldBe "ended:completed"
    seen.init.map(_.stripPrefix("general:")).mkString shouldBe "hello"
    ok(run.await()).answer shouldBe Some("composed")
  }
}

object AgentPekkoNestedEventsSpec {

  private def completion(text: String, calls: Seq[ToolCall] = Nil): Completion = {
    val message = if (calls.isEmpty) AssistantMessage(text, Seq.empty) else AssistantMessage(None, calls)
    Completion("n", 0L, message.content, "m", message, calls.toList, Some(TokenUsage(1, 1, 2)))
  }

  /** The lead delegates once to a streaming text subagent; its first call waits for `subscribed`. */
  def delegating(subscribed: CountDownLatch): Agent = {
    val client = new LLMClient {
      def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
        val messages = conversation.messages.toVector
        val prompt   = messages.headOption.map(_.content).getOrElse("")
        val answered = messages.exists { case _: ToolMessage => true; case _ => false }
        if (prompt.startsWith("CHILD")) Right(completion("hello"))
        else if (answered) Right(completion("composed"))
        else {
          subscribed.await(10, TimeUnit.SECONDS): Unit
          Right(
            completion(
              "",
              Seq(ToolCall("c1", "task", ujson.Obj("subagent_type" -> "general", "description" -> "say hello")))
            )
          )
        }
      }
      def streamComplete(
        conversation: Conversation,
        options: CompletionOptions,
        onChunk: StreamedChunk => Unit
      ): Result[Completion] =
        complete(conversation, options).map { c =>
          c.content.grouped(3).foreach(piece => onChunk(StreamedChunk("c", Some(piece))))
          c
        }
      def getContextWindow(): Int     = 128000
      def getReserveCompletion(): Int = 4096
    }
    val general = TypedAgent.text("general", "Says hello").withSystemPrompt("CHILD").withStreaming()
    Agent
      .builder("lead", client)
      .withSystemPrompt("LEAD")
      .withSubagents(general)
      .build()
      .fold(e => throw new IllegalStateException(e.message), identity)
  }
}
