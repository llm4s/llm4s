package org.llm4s.pekko

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.dispatch.Dispatchers
import org.apache.pekko.stream.KillSwitches
import org.apache.pekko.stream.scaladsl.{ Keep, Sink, Source }
import org.llm4s.agent.{ Agent, AgentResult }
import org.llm4s.agent.graph.{ StreamEvent, ThreadId }
import org.llm4s.effect.contract.{ Cancellable, EffectBridge, Raised, Streamed, StreamingBridgeContract, Turn }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ Completion, Conversation, StreamedChunk }

import java.util.concurrent.CopyOnWriteArrayList
import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * `LLMClientPekko` and `AgentPekko` against the bridges' shared contract (#2334). A `Future` cannot be cancelled,
 * so `complete`'s cancellation case is covered by `streamComplete`'s, as the module's guide says.
 */
class PekkoBridgeContractSpec extends StreamingBridgeContract("LLMClientPekko / AgentPekko") {

  def bridge: EffectBridge = PekkoBridgeContractSpec.Bridge

  override def afterAll(): Unit = {
    Await.result(PekkoBridgeContractSpec.system.terminate(), 30.seconds): Unit
    super.afterAll()
  }
}

object PekkoBridgeContractSpec {

  /** Lazy, and in the companion: the suite's cases are registered before the spec's own fields are set. */
  implicit lazy val system: ActorSystem = ActorSystem("pekko-bridge-contract")

  private val Patience = 60.seconds

  /** The blocking dispatcher, where `complete` and `run` are documented to run. */
  private given blocking: ExecutionContext = system.dispatchers.lookup(Dispatchers.DefaultBlockingDispatcherId)

  /** An `LLMException` is the bridge's carrier; anything else is rethrown and fails the case. */
  private def raised(t: Throwable): Raised = t match {
    case e: LLMException => Raised(e.error, e.getMessage)
    case other           => throw other
  }

  private def awaited[A](future: Future[A]): Either[Raised, A] =
    Try(Await.result(future, Patience)).toEither.left.map(raised)

  /** Runs `source` with a kill switch; cancelling shuts it down and waits for its materialized `Future[Done]`. */
  private def killable[A](source: Source[A, Future[org.apache.pekko.Done]]): Cancellable = {
    val (done, switch) = source.viaMat(KillSwitches.single)(Keep.both).toMat(Sink.ignore)(Keep.left).run()
    () => {
      switch.shutdown()
      Await.result(done, Patience): Unit
    }
  }

  object Bridge extends EffectBridge {

    def complete(client: LLMClient, conversation: Conversation): Either[Raised, Completion] =
      awaited(LLMClientPekko(client).complete(conversation))

    def streamComplete(client: LLMClient, conversation: Conversation, take: Option[Int]): Streamed[StreamedChunk] = {
      val items  = new CopyOnWriteArrayList[StreamedChunk]()
      val source = LLMClientPekko(client).streamComplete(conversation)
      val ended  = awaited(take.fold(source)(n => source.take(n.toLong)).runWith(Sink.foreach(c => items.add(c): Unit)))
      Streamed(items.asScala.toVector, ended.left.toOption)
    }

    def startComplete(client: LLMClient, conversation: Conversation): Option[Cancellable] = None

    def startStreamComplete(client: LLMClient, conversation: Conversation): Cancellable =
      killable(LLMClientPekko(client).streamComplete(conversation))

    def run(agent: Agent, query: String): Either[Raised, AgentResult] =
      awaited(AgentPekko(agent).run(query))

    def stream(agent: Agent, threadId: ThreadId, query: String): Turn = {
      val events = new CopyOnWriteArrayList[StreamEvent]()
      val result = new CopyOnWriteArrayList[AgentResult]()
      val ended = awaited(
        AgentPekko(agent)
          .stream(threadId, query)
          .runWith(Sink.foreach {
            case AgentStreamItem.Event(e) => events.add(e): Unit
            case AgentStreamItem.Done(r)  => result.add(r): Unit
          })
      )
      val outcome = ended.flatMap { _ =>
        result.asScala.headOption.toRight(throw new IllegalStateException("the stream ended without Done"))
      }
      Turn(events.asScala.toVector, outcome)
    }

    def startStream(agent: Agent, threadId: ThreadId, query: String): Cancellable =
      killable(AgentPekko(agent).stream(threadId, query))

    // Pekko's default dispatcher, where stream stages and actors run, names its threads `<system>-pekko.actor.default-dispatcher-N`.
    def isComputeThread(thread: Thread): Boolean = thread.getName.contains("default-dispatcher")
  }
}
