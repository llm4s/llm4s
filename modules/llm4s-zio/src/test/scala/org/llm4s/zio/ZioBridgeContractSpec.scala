package org.llm4s.zio

import org.llm4s.agent.{ Agent, AgentResult }
import org.llm4s.agent.graph.{ StreamEvent, ThreadId }
import org.llm4s.effect.contract.{ Cancellable, EffectBridge, Raised, Streamed, StreamingBridgeContract, Turn }
import org.llm4s.error.LLMError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ Completion, Conversation, StreamedChunk }
import zio.{ Runtime, Unsafe, ZIO }

import java.util.concurrent.CopyOnWriteArrayList
import scala.jdk.CollectionConverters.*

/** `LLMClientZ` and `AgentZ` against the bridges' shared contract (#2334). */
class ZioBridgeContractSpec extends StreamingBridgeContract("LLMClientZ / AgentZ") {

  def bridge: EffectBridge = ZioBridgeContractSpec.Bridge
}

object ZioBridgeContractSpec {

  /** Runs `effect`; a defect or an interruption is thrown and fails the case. */
  private def unsafeRun[E, A](effect: ZIO[Any, E, A]): Either[E, A] =
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(effect.either).getOrThrowFiberFailure())

  /** ZIO's error channel is the `LLMError` itself. */
  private def raised(error: LLMError): Raised = Raised(error, error.message)

  private def fiber(program: ZIO[Any, LLMError, Unit]): Cancellable = {
    val started = Unsafe.unsafe(implicit u => Runtime.default.unsafe.fork(program))
    () => Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(started.interrupt).getOrThrowFiberFailure()): Unit
  }

  object Bridge extends EffectBridge {

    def complete(client: LLMClient, conversation: Conversation): Either[Raised, Completion] =
      unsafeRun(LLMClientZ(client).complete(conversation)).left.map(raised)

    def streamComplete(client: LLMClient, conversation: Conversation, take: Option[Int]): Streamed[StreamedChunk] = {
      val items  = new CopyOnWriteArrayList[StreamedChunk]()
      val stream = LLMClientZ(client).streamComplete(conversation)
      val ended  = unsafeRun(take.fold(stream)(n => stream.take(n.toLong)).runForeach(c => ZIO.succeed(items.add(c))))
      Streamed(items.asScala.toVector, ended.left.toOption.map(raised))
    }

    def startComplete(client: LLMClient, conversation: Conversation): Option[Cancellable] =
      Some(fiber(LLMClientZ(client).complete(conversation).unit))

    def startStreamComplete(client: LLMClient, conversation: Conversation): Cancellable =
      fiber(LLMClientZ(client).streamComplete(conversation).runDrain)

    def run(agent: Agent, query: String): Either[Raised, AgentResult] =
      unsafeRun(AgentZ(agent).run(query)).left.map(raised)

    def stream(agent: Agent, threadId: ThreadId, query: String): Turn = {
      val events = new CopyOnWriteArrayList[StreamEvent]()
      val result = new CopyOnWriteArrayList[AgentResult]()
      val ended = unsafeRun(AgentZ(agent).stream(threadId, query).runForeach {
        case AgentStreamItem.Event(e) => ZIO.succeed(events.add(e))
        case AgentStreamItem.Done(r)  => ZIO.succeed(result.add(r))
      })
      val outcome = ended.left.map(raised).flatMap { _ =>
        result.asScala.headOption.toRight(throw new IllegalStateException("the stream ended without Done"))
      }
      Turn(events.asScala.toVector, outcome)
    }

    def startStream(agent: Agent, threadId: ThreadId, query: String): Cancellable =
      fiber(AgentZ(agent).stream(threadId, query).runDrain)

    // ZIO 2's default executor names its threads `ZScheduler-Worker-N`; blocking work runs on `zio-default-blocking-N`.
    def isComputeThread(thread: Thread): Boolean = thread.getName.startsWith("ZScheduler")
  }
}
