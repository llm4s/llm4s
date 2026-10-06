package org.llm4s.agent.graph

import org.llm4s.agent.Agent
import org.llm4s.error.ProcessingError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.*
import org.llm4s.types.Result
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Millis, Seconds, Span }

import java.util.concurrent.CopyOnWriteArrayList
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * A stream or a late subscription to a run that ends without a terminal durable event still ends:
 * its subscription leaves the thread's live set.
 */
class RunScopeEndSpec extends AnyFlatSpec with Matchers with Eventually:

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(5, Seconds), interval = Span(20, Millis))

  private object Answering extends LLMClient:
    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
      Right(Completion("answer", 0L, "hi", "test-model", AssistantMessage("hi")))
    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)
    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 1024

  /** A store that refuses (`Left`) or throws on every commit carrying a `RunCompleted`. */
  final private class NoTerminal(throws: Boolean) extends Checkpointer:
    private val underlying = InMemoryCheckpointer()
    def commit(threadId: ThreadId, commit: Commit): Result[Vector[EventRecord]] =
      if !commit.events.exists(_.event == RunEvent.RunCompleted) then underlying.commit(threadId, commit)
      else if throws then throw new IllegalStateException("store down")
      else Left(ProcessingError("store", "store down"))
    def latest(threadId: ThreadId): Result[Option[StoredCheckpoint]] = underlying.latest(threadId)
    def eventsAfter(threadId: ThreadId, afterSeq: Long, limit: Int): Result[Vector[EventRecord]] =
      underlying.eventsAfter(threadId, afterSeq, limit)
    def compactEvents(threadId: ThreadId, beforeSeq: Long): Result[Unit] =
      underlying.compactEvents(threadId, beforeSeq)
    def deleteThread(threadId: ThreadId): Result[Unit] = underlying.deleteThread(threadId)

  private def agentOn(runtime: GraphRuntime): Agent =
    Agent.builder("assistant", Answering).withRuntime(runtime).build().fold(e => fail(e.message), identity)

  for (throws, how) <- Seq(false -> "refuses", true -> "throws on") do
    s"agent.stream, when the store $how the run's terminal commit" should
      "end its subscription, though no terminal event comes" in {
        val runtime  = GraphRuntime(NoTerminal(throws))
        val threadId = ThreadId(s"crash-$throws")
        val events   = new CopyOnWriteArrayList[StreamEvent]()
        val run =
          agentOn(runtime).stream(threadId, "hello")(e => events.add(e): Unit).fold(e => fail(e.message), identity)
        run.await().isLeft shouldBe true
        eventually(runtime.liveSubscriptions(threadId) shouldBe 0)
        events.asScala.collect { case StreamEvent.Durable(r) => r.event }.exists(RunScopeEndSpec.terminal) shouldBe
          false
        events.asScala.collectFirst { case StreamEvent.Durable(r) => r.event } shouldBe
          Some(RunEvent.RunStarted(None, None))
      }

    s"AgentRun.subscribe, when the store $how the run's terminal commit" should
      "end its subscription, though no terminal event comes" in {
        val runtime  = GraphRuntime(NoTerminal(throws))
        val threadId = ThreadId(s"crash-sub-$throws")
        val run      = agentOn(runtime).start(threadId, "hello").fold(e => fail(e.message), identity)
        run.await().isLeft shouldBe true
        val events = new CopyOnWriteArrayList[StreamEvent]()
        run.subscribe()(e => events.add(e): Unit).isRight shouldBe true
        eventually(
          events.asScala.collectFirst { case StreamEvent.Durable(r) => r.event } shouldBe
            Some(RunEvent.RunStarted(None, None))
        )
        // a late subscription joins the live set only after its replay, so 0 means something only
        // once it has had time to join: past the scope's quiet period it is cancelled, not yet to join
        Thread.sleep((org.llm4s.agent.RunScope.Quiet + 500.millis).toMillis)
        runtime.liveSubscriptions(threadId) shouldBe 0
      }

  "agent.stream" should "leave the live set after the run's terminal event" in {
    val runtime  = GraphRuntime.inMemory()
    val threadId = ThreadId("normal")
    val run      = agentOn(runtime).stream(threadId, "hello")(_ => ()).fold(e => fail(e.message), identity)
    run.await().isRight shouldBe true
    eventually(runtime.liveSubscriptions(threadId) shouldBe 0)
  }

private object RunScopeEndSpec:
  def terminal(event: RunEvent): Boolean = org.llm4s.agent.RunScope.terminal(event)
