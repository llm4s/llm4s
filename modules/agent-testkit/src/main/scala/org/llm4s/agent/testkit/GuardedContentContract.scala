package org.llm4s.agent.testkit

import org.llm4s.agent.{ Agent, AgentBuilder }
import org.llm4s.agent.encryption.{ EncryptingCheckpointer, EncryptionKey, KeyId, StaticKeySource }
import org.llm4s.agent.graph.*
import org.llm4s.agent.graph.middleware.GuardrailMiddleware
import org.llm4s.agent.guardrails.{ InputGuardrail, OutputGuardrail }
import org.llm4s.annotation.Experimental
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.*
import org.llm4s.trace.{ TraceEvent, Tracing }
import org.llm4s.types.Result
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.*

/**
 * What a guarded agent may leave in a store (#2853, #2854), as a ScalaTest suite: the text a guardrail refused, and
 * the reason it gave, are in nothing the store was given and nothing it gives back.
 *
 * Each case runs an agent over the store under test and looks for a marker - a string no id, name or count can hold -
 * in two places: everything the store was handed (each `Commit`, recorded on its way in, so a row written and then
 * retracted is seen too) and everything it returns afterwards (checkpoint history, the latest checkpoint and its
 * pending writes, the event log, the thread's children and its queue entries).
 *
 * Mix it into a spec and give it a fresh store per case:
 * {{{
 * class MyStoreGuardedContentSpec extends AnyFlatSpec with GuardedContentContract:
 *   protected def newStores(): RunQueueContract.Stores =
 *     val store = MyStore.open(...)
 *     RunQueueContract.Stores(store, store.runQueue)
 * }}}
 */
@Experimental
trait GuardedContentContract extends AnyFlatSpecLike with Matchers with EitherValues with OptionValues:
  import GuardedContentContract.*

  /** A fresh, empty store for one case: its checkpointer, and the run queue in the same database. */
  protected def newStores(): RunQueueContract.Stores

  private def builder(client: LLMClient, store: Checkpointer, tracing: Tracing): AgentBuilder =
    Agent.builder("guarded", client).withRuntime(new GraphRuntime(store)).withTracing(tracing)

  /** Every string the store under `recorded` holds for `thread`, read back through it. */
  private def readBack(recorded: Recorded, thread: ThreadId): Vector[String] =
    val history = recorded.history(thread, None, 1000).value.map(_.toString)
    val latest  = recorded.latest(thread).value.map(_.toString).toVector
    val events  = recorded.eventsAfter(thread, 0L, 1000).value.map(_.toString)
    val below   = recorded.children(thread, None, 100).value.map(_.toString)
    history ++ latest ++ events ++ below

  /** Fails if `marker` is in anything the store was given, gives back, or the trace was sent. */
  private def assertAbsent(
    marker: String,
    recorded: Recorded,
    thread: ThreadId,
    tracing: RecordingTracing
  ): org.scalatest.Assertion =
    withClue(s"in a commit the store was given: ")(recorded.commits.filter(_.contains(marker)) shouldBe empty)
    withClue(s"in what the store returns: ")(readBack(recorded, thread).filter(_.contains(marker)) shouldBe empty)
    withClue(s"in the trace: ")(tracing.rendered.filter(_.contains(marker)) shouldBe empty)

  "GuardedContentContract: an input guardrail's reason" should "reach the caller and be stored nowhere" in {
    val recorded = Recorded(newStores().checkpointer)
    val tracing  = RecordingTracing()
    val thread   = ThreadId(s"reason-in-${unique()}")
    val agent = builder(Scripted(text("never sent")), recorded, tracing)
      .withInputGuardrails(refusing("ReasonIn", ReasonMarker))
      .build()
      .value

    val result = agent.run(thread, "an ordinary question").value
    result.status.toString should (include("ReasonIn").and(include(ReasonMarker)))
    assertAbsent(ReasonMarker, recorded, thread, tracing)
  }

  "GuardedContentContract: an output guardrail's reason" should "reach the caller and be stored nowhere" in {
    val recorded = Recorded(newStores().checkpointer)
    val tracing  = RecordingTracing()
    val thread   = ThreadId(s"reason-out-${unique()}")
    val agent = builder(Scripted(text("an ordinary answer")), recorded, tracing)
      .withOutputGuardrails(refusingOutput("ReasonOut", ReasonMarker))
      .build()
      .value

    val result = agent.run(thread, "an ordinary question").value
    result.status.toString should (include("ReasonOut").and(include(ReasonMarker)))
    assertAbsent(ReasonMarker, recorded, thread, tracing)
  }

  "GuardedContentContract: a blocking middleware's reason" should "reach the caller and be stored nowhere" in {
    val recorded = Recorded(newStores().checkpointer)
    val tracing  = RecordingTracing()
    val thread   = ThreadId(s"reason-mw-${unique()}")
    val agent = builder(Scripted(text("never sent")), recorded, tracing)
      .withMiddleware(new GuardrailMiddleware(Seq(refusing("ReasonMiddle", ReasonMarker)), Nil))
      .build()
      .value

    agent.run(thread, "an ordinary question").value.status.toString should include(ReasonMarker)
    assertAbsent(ReasonMarker, recorded, thread, tracing)
  }

  "GuardedContentContract: an encrypted store" should "be given no text of a failed run's outcome" in {
    val recorded = Recorded(newStores().checkpointer)
    val store    = EncryptingCheckpointer(recorded, StaticKeySource(EncryptionKey.generate(KeyId("contract"))))
    val thread   = ThreadId(s"sealed-${unique()}")
    val token    = store.claim(thread, ClaimRequest(RunId("contract"), None, FiniteDuration(30, "s"))).value.token
    def closing(id: String, parent: Option[String], outcome: RunOutcome): Checkpoint =
      Checkpoint(
        Checkpoint.CurrentFormat,
        id,
        parent,
        thread.value,
        s"run-$id",
        CheckpointStatus.Failed,
        Instant.EPOCH,
        GraphSnapshot("g", "v1", "f", 0, Map.empty, Vector.empty, Vector.empty, Vector.empty, Vector.empty, false)
      ).withFencingToken(token).withOutcome(outcome)

    val outcomes = Vector(
      RunOutcome.Failed("ProcessingError", s"the tool said $FailureMarker"),
      RunOutcome.OutputInvalid(Vector(s"answer was $FailureMarker", s"and again $FailureMarker"))
    )
    outcomes.zipWithIndex.foldLeft(Option.empty[String]) { case (parent, (outcome, i)) =>
      store.commit(thread, Commit(token, Some(closing(s"c$i", parent, outcome)))).value
      Some(s"c$i")
    }

    withClue("in a commit the store was given: ")(recorded.commits.filter(_.contains(FailureMarker)) shouldBe empty)
    withClue("in what the store returns: ") {
      recorded.history(thread, None, 10).value.map(_.toString).filter(_.contains(FailureMarker)) shouldBe empty
    }
    // and the outcome a runtime reads through the encrypting store is the one it wrote
    store.history(thread, None, 10).value.flatMap(_.outcome) should contain theSameElementsAs outcomes
  }

@Experimental
object GuardedContentContract:

  /** In a guardrail's reason, and nowhere else a run puts text. */
  val ReasonMarker: String = "REASON-7f3a91"

  /** In a failed run's outcome text. */
  val FailureMarker: String = "FAILURE-5c2e08"

  private val counter = new AtomicInteger(0)

  /** A suffix that makes a thread id unique in a store the cases of a suite share. */
  private def unique(): String = s"${counter.incrementAndGet()}-${java.util.UUID.randomUUID().toString.take(8)}"

  /** An input guardrail that refuses everything, its reason holding `marker`. */
  def refusing(guardrail: String, marker: String): InputGuardrail = new InputGuardrail:
    val name: String                            = guardrail
    def validate(value: String): Result[String] = Left(ValidationError.invalid("input", s"refused: $marker"))

  /** An output guardrail that refuses everything, its reason holding `marker`. */
  def refusingOutput(guardrail: String, marker: String): OutputGuardrail = new OutputGuardrail:
    val name: String                            = guardrail
    def validate(value: String): Result[String] = Left(ValidationError.invalid("output", s"refused: $marker"))

  private def text(content: String): Result[Completion] = Right(completion(AssistantMessage(content, Seq.empty)))

  private def completion(message: AssistantMessage): Completion =
    Completion(
      id = "guarded-content-contract",
      created = Instant.EPOCH,
      content = message.content,
      model = "contract-model",
      message = message,
      toolCalls = message.toolCalls.toList,
      usage = Some(TokenUsage(promptTokens = 10, completionTokens = 5, totalTokens = 15)),
      finishReason = if message.toolCalls.nonEmpty then FinishReason.ToolCalls else FinishReason.Stop
    )

  /** Answers call N with `responses(N)`, and the last one from then on; thread-safe. */
  final private class Scripted(responses: Result[Completion]*) extends LLMClient:
    private val calls = new AtomicInteger(0)

    /** Every conversation sent, in order. */
    val sent = new ConcurrentLinkedQueue[Vector[Message]]()

    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
      sent.add(conversation.messages.toVector)
      responses(math.min(calls.getAndIncrement(), responses.size - 1))

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)

    override def getContextWindow(): Int     = 128000
    override def getReserveCompletion(): Int = 4096

  /** Every trace event sent, rendered. */
  final private class RecordingTracing extends Tracing:
    private val events = new ConcurrentLinkedQueue[String]()
    def traceEvent(event: TraceEvent): Result[Unit] =
      events.add(event.toJson.render())
      Right(())
    def rendered: Vector[String] = events.asScala.toVector

  /** `to`, keeping the text of every commit it is given: what a store was handed, whatever it kept of it. */
  final private class Recorded(to: Checkpointer) extends Checkpointer:
    private val handed = new ConcurrentLinkedQueue[String]()

    /** Every commit this store was given, rendered. */
    def commits: Vector[String] = handed.asScala.toVector

    def commit(threadId: ThreadId, commit: Commit): Result[Vector[EventRecord]] =
      handed.add(commit.toString)
      to.commit(threadId, commit)

    def claim(threadId: ThreadId, request: ClaimRequest): Result[RunClaim] = to.claim(threadId, request)
    def renew(threadId: ThreadId, token: FencingToken, ttl: FiniteDuration): Result[RunClaim] =
      to.renew(threadId, token, ttl)
    def release(threadId: ThreadId, token: FencingToken): Result[Unit]           = to.release(threadId, token)
    def latest(threadId: ThreadId): Result[Option[StoredCheckpoint]]             = to.latest(threadId)
    override def owner(threadId: ThreadId): Result[Option[ThreadOwner]]          = to.owner(threadId)
    def effect(threadId: ThreadId, key: EffectKey): Result[Option[EffectRecord]] = to.effect(threadId, key)
    def history(threadId: ThreadId, before: Option[String], limit: Int): Result[Vector[Checkpoint]] =
      to.history(threadId, before, limit)
    def checkpoint(threadId: ThreadId, checkpointId: String): Result[Option[Checkpoint]] =
      to.checkpoint(threadId, checkpointId)
    def prune(threadId: ThreadId, policy: RetentionPolicy): Result[Unit] = to.prune(threadId, policy)
    def eventsAfter(threadId: ThreadId, afterSeq: Long, limit: Int): Result[Vector[EventRecord]] =
      to.eventsAfter(threadId, afterSeq, limit)
    override def awaitEventsAfter(
      threadId: ThreadId,
      afterSeq: Long,
      limit: Int,
      timeout: FiniteDuration
    ): Result[Vector[EventRecord]] = to.awaitEventsAfter(threadId, afterSeq, limit, timeout)
    def children(parent: ThreadId, after: Option[ThreadId], limit: Int): Result[Vector[ChildThread]] =
      to.children(parent, after, limit)
    def threads(query: ThreadQuery): Result[ThreadPage] = to.threads(query)
    def incompleteThreads(tenantId: Option[TenantId], after: Option[ThreadCursor], limit: Int): Result[ThreadPage] =
      to.incompleteThreads(tenantId, after, limit)
    def latestOfRuns(threadId: ThreadId, runIds: Set[RunId]): Result[Option[Checkpoint]] =
      to.latestOfRuns(threadId, runIds)
    def sweep(policy: SweepPolicy, after: Option[ThreadId], guard: SweepGuard): Result[SweepPage] =
      to.sweep(policy, after, guard)
    def compactEvents(threadId: ThreadId, beforeSeq: Long): Result[Unit] = to.compactEvents(threadId, beforeSeq)
    def deleteThread(threadId: ThreadId, token: FencingToken, fence: Option[(ThreadId, FencingToken)]): Result[Unit] =
      to.deleteThread(threadId, token, fence)
