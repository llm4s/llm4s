package org.llm4s.ox

import _root_.ox.supervised
import org.llm4s.agent.{ Agent, AgentResult, AgentRun }
import org.llm4s.agent.graph.{ StreamEvent, ThreadId }
import org.llm4s.effect.contract.{ Cancellable, EffectBridge, Raised, Streamed, StreamingBridgeContract, Turn }
import org.llm4s.error.{ CancelledError, LLMError }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ Completion, Conversation, StreamedChunk }
import org.llm4s.types.Result

import java.util.concurrent.{ CopyOnWriteArrayList, CountDownLatch, TimeUnit }
import java.util.concurrent.atomic.AtomicReference
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * `llm4s-ox` against the bridges' shared contract (#2334).
 *
 * Direct style needs no wrapper around a call that already returns a `Result` on the calling thread: `complete` and
 * the agent cases run core's own `complete`, `Agent.run` and `Agent.stream`, cancelled the direct-style way (by
 * interrupting the calling thread, and `AgentRun.cancel` for a turn, whose thread the runtime owns). The streaming
 * cases run `streamFlow` and stop it the way an Ox consumer does: a `take`, or interrupting the thread that owns the
 * run's scope. Ox has no compute pool: every fork is a virtual thread, so no thread is one where blocking is unsafe.
 */
class OxBridgeContractSpec extends StreamingBridgeContract("LLMClientOx") {
  def bridge: EffectBridge = OxBridgeContractSpec.Bridge
}

object OxBridgeContractSpec {

  private def raised(error: LLMError): Raised = Raised(error, error.message)

  /** An `LLMException` is the flow's carrier; anything else is rethrown and fails the case. */
  private def failure(thrown: Throwable): Raised = thrown match {
    case e: LLMException => Raised(e.error, e.getMessage)
    case other           => throw other
  }

  /**
   * Runs `body` on a virtual thread of its own; cancelling interrupts that thread, runs `alsoOnCancel`, and waits
   * for `body` to return.
   */
  private def background(body: => Unit, alsoOnCancel: () => Unit = () => ()): Cancellable = {
    val thread = Thread.ofVirtual().start(() => CancelledError.catchInterrupt(Try(body)): Unit)
    () => {
      thread.interrupt()
      alsoOnCancel()
      thread.join(TimeUnit.SECONDS.toMillis(StreamingBridgeContract.DeadlineSeconds))
    }
  }

  object Bridge extends EffectBridge {

    def complete(client: LLMClient, conversation: Conversation): Either[Raised, Completion] =
      client.complete(conversation).left.map(raised)

    def streamComplete(client: LLMClient, conversation: Conversation, take: Option[Int]): Streamed[StreamedChunk] = {
      val items = new CopyOnWriteArrayList[StreamedChunk]()
      val flow  = client.streamFlow(conversation)
      val ended = Try(take.fold(flow)(n => flow.take(n)).runForeach(c => items.add(c): Unit))
      Streamed(items.asScala.toVector, ended.failed.toOption.map(failure))
    }

    def startComplete(client: LLMClient, conversation: Conversation): Option[Cancellable] =
      Some(background(client.complete(conversation): Unit))

    def startStreamComplete(client: LLMClient, conversation: Conversation): Cancellable =
      background(supervised(client.streamFlow(conversation).runDrain()))

    def run(agent: Agent, query: String): Either[Raised, AgentResult] =
      agent.run(query).left.map(raised)

    def stream(agent: Agent, threadId: ThreadId, query: String): Turn = {
      val events  = new CopyOnWriteArrayList[StreamEvent]()
      val outcome = agent.stream(threadId, query)(e => events.add(e): Unit).flatMap(_.await())
      Turn(events.asScala.toVector, outcome.left.map(raised))
    }

    def startStream(agent: Agent, threadId: ThreadId, query: String): Cancellable = {
      val started = new CountDownLatch(1)
      val run     = new AtomicReference[Option[AgentRun]](None)
      background(
        {
          val begun: Result[AgentResult] = agent.stream(threadId, query)(_ => ()).flatMap { r =>
            run.set(Some(r))
            started.countDown()
            r.await()
          }
          started.countDown()
          begun: Unit
        },
        () => {
          started.await(StreamingBridgeContract.DeadlineSeconds, TimeUnit.SECONDS): Unit
          run.get().foreach(_.cancel())
        }
      )
    }

    def isComputeThread(thread: Thread): Boolean = false
  }
}
