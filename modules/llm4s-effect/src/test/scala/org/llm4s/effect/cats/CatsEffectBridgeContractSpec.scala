package org.llm4s.effect.cats

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.llm4s.agent.{ Agent, AgentResult }
import org.llm4s.agent.graph.{ StreamEvent, ThreadId }
import org.llm4s.effect.contract.{ Cancellable, EffectBridge, Raised, Streamed, StreamingBridgeContract, Turn }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ Completion, Conversation, StreamedChunk }

import java.util.concurrent.CopyOnWriteArrayList
import scala.jdk.CollectionConverters.*

/** `LLMClientIO` and `AgentIO` against the bridges' shared contract (#2334). */
class CatsEffectBridgeContractSpec extends StreamingBridgeContract("LLMClientIO / AgentIO") {

  def bridge: EffectBridge = CatsEffectBridgeContractSpec.Bridge
}

object CatsEffectBridgeContractSpec {

  /** An `LLMException` is the bridge's carrier; anything else is rethrown and fails the case. */
  private def raised(t: Throwable): Raised = t match {
    case e: LLMException => Raised(e.error, e.getMessage)
    case other           => throw other
  }

  private def fiber(program: IO[Unit]): Cancellable = {
    val started = program.start.unsafeRunSync()
    () => started.cancel.unsafeRunSync()
  }

  object Bridge extends EffectBridge {

    def complete(client: LLMClient, conversation: Conversation): Either[Raised, Completion] =
      LLMClientIO[IO](client).complete(conversation).attempt.unsafeRunSync().left.map(raised)

    def streamComplete(client: LLMClient, conversation: Conversation, take: Option[Int]): Streamed[StreamedChunk] = {
      val items   = new CopyOnWriteArrayList[StreamedChunk]()
      val stream  = LLMClientIO[IO](client).streamComplete(conversation)
      val ended   = take.fold(stream)(n => stream.take(n.toLong)).evalMap(c => IO(items.add(c))).compile.drain
      val failure = ended.attempt.unsafeRunSync().left.toOption.map(raised)
      Streamed(items.asScala.toVector, failure)
    }

    def startComplete(client: LLMClient, conversation: Conversation): Option[Cancellable] =
      Some(fiber(LLMClientIO[IO](client).complete(conversation).void))

    def startStreamComplete(client: LLMClient, conversation: Conversation): Cancellable =
      fiber(LLMClientIO[IO](client).streamComplete(conversation).compile.drain)

    def run(agent: Agent, query: String): Either[Raised, AgentResult] =
      AgentIO[IO](agent).run(query).attempt.unsafeRunSync().left.map(raised)

    def stream(agent: Agent, threadId: ThreadId, query: String): Turn = {
      val events = new CopyOnWriteArrayList[StreamEvent]()
      val result = new CopyOnWriteArrayList[AgentResult]()
      val ended = AgentIO[IO](agent)
        .stream(threadId, query)
        .evalMap {
          case AgentStreamItem.Event(e) => IO(events.add(e)).void
          case AgentStreamItem.Done(r)  => IO(result.add(r)).void
        }
        .compile
        .drain
        .attempt
        .unsafeRunSync()
      val outcome = ended.left.map(raised).flatMap { _ =>
        result.asScala.headOption.toRight(throw new IllegalStateException("the stream ended without Done"))
      }
      Turn(events.asScala.toVector, outcome)
    }

    def startStream(agent: Agent, threadId: ThreadId, query: String): Cancellable =
      fiber(AgentIO[IO](agent).stream(threadId, query).compile.drain)

    // cats-effect's work-stealing compute pool names its threads `io-compute-N`.
    def isComputeThread(thread: Thread): Boolean = thread.getName.startsWith("io-compute")
  }
}
