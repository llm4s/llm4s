package org.llm4s.effect.contract

import java.util.concurrent.{ CopyOnWriteArrayList, CountDownLatch, TimeUnit }
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger, AtomicReference }

import org.llm4s.agent.{ Agent, AgentResult }
import org.llm4s.agent.graph.{ GraphError, StreamEvent, ThreadId }
import org.llm4s.error.{ CancelledError, LLMError, SimpleError }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ Completion, CompletionOptions, Conversation, StreamedChunk, UserMessage }
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }
import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolRegistry }
import org.llm4s.types.Result
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.{ Signaler, ThreadSignaler, TimeLimitedTests }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Minutes, Span }

import scala.jdk.CollectionConverters.*

/**
 * The behaviour every effect bridge over the `Result`-returning core promises alike (#2334): `llm4s-effect`
 * (cats-effect, fs2), `llm4s-zio` (ZIO, ZStream) and `llm4s-pekko` (Pekko Streams) each run this suite against
 * an [[EffectBridge]] of their own, so the three cannot drift apart. A new bridge runs it too
 * (`docs/guide/effect-systems.md`).
 *
 * Every case is driven by a [[org.llm4s.testkit.ScriptedLLMClient ScriptedLLMClient]], with no network. The
 * cancellation cases wait on latches, never on sleeps: a provider call parks until it is interrupted, and the
 * suite cancels only once the call has started. A bounded wait (`DeadlineSeconds`) only turns a hang into a
 * failure.
 *
 * `name` is the bridge's name in the test names, such as `LLMClientIO / AgentIO`. A bridge whose runtime must be
 * shut down (an `ActorSystem`) does it in `afterAll`.
 *
 * The shared source set lives in `llm4s-effect`'s `src/test/contract`; `llm4s-zio` and `llm4s-pekko` compile it
 * into their own test sources (`build.sbt`).
 */
abstract class StreamingBridgeContract(name: String)
    extends AnyFlatSpec
    with Matchers
    with TimeLimitedTests
    with BeforeAndAfterAll {
  import StreamingBridgeContract.*

  override def timeLimit: Span               = Span(2, Minutes)
  override val defaultTestSignaler: Signaler = ThreadSignaler

  /** The bridge under test; a `def`, as the suite's cases are registered before a subclass's fields are set. */
  def bridge: EffectBridge

  // ---- errors -------------------------------------------------------------------------------------------------

  s"$name complete" should "surface a provider Left as the same LLMError, with its message" in {
    val error  = SimpleError("provider refused")
    val raised = bridge.complete(ScriptedLLMClient.sequence(Reply.failure(error)), conversation)
    raised.left.map(r => (r.error, r.message)) shouldBe Left((error, error.message))
    raised.left.toOption.map(_.error).exists(_ eq error) shouldBe true
  }

  it should "return the scripted completion" in {
    bridge.complete(ScriptedLLMClient.sequence(Reply.text("hello")), conversation).map(_.content) shouldBe Right(
      "hello"
    )
  }

  s"$name streamComplete" should "surface a provider Left as the same LLMError, with its message" in {
    val error    = SimpleError("stream refused")
    val streamed = bridge.streamComplete(ScriptedLLMClient.sequence(Reply.failure(error)), conversation, None)
    streamed.items shouldBe empty
    streamed.failure.map(r => (r.error, r.message)) shouldBe Some((error, error.message))
    streamed.failure.exists(_.error eq error) shouldBe true
  }

  it should "deliver the provider's chunks in order, then complete" in {
    val client   = ScriptedLLMClient.sequence(Reply.text("abcdef")).withChunkSize(2)
    val expected = directChunks(ScriptedLLMClient.sequence(Reply.text("abcdef")).withChunkSize(2))
    val streamed = bridge.streamComplete(client, conversation, None)
    streamed.failure shouldBe None
    streamed.items.flatMap(_.content) shouldBe expected.flatMap(_.content)
  }

  it should "deliver the chunks received before a mid-stream failure, then the error" in {
    val error    = SimpleError("connection reset mid-stream")
    val chunks   = directChunks(ScriptedLLMClient.sequence(Reply.text("abc")).withChunkSize(1))
    val streamed = bridge.streamComplete(new FailingMidStream(chunks, error), conversation, None)
    streamed.items.flatMap(_.content) shouldBe Vector("a", "b", "c")
    streamed.failure.map(_.error) shouldBe Some(error)
    streamed.failure.map(_.message) shouldBe Some(error.message)
  }

  it should "stop consuming the provider's chunks when the consumer stops early" in {
    val total    = 5000
    val counting = new Counting(ScriptedLLMClient.sequence(Reply.text("x" * total)).withChunkSize(1))
    val streamed = bridge.streamComplete(counting, conversation, Some(2))
    streamed.items.size shouldBe 2
    streamed.failure shouldBe None
    withClue("the provider call never returned after the consumer stopped:") {
      counting.ended.await(DeadlineSeconds, TimeUnit.SECONDS) shouldBe true
    }
    counting.emitted.get() should be < total
  }

  // ---- cancellation -------------------------------------------------------------------------------------------

  s"$name complete" should "interrupt the provider call when cancelled, and free its thread" in {
    val parked = new Parked(ScriptedLLMClient.always(Reply.text("never")))
    bridge.startComplete(parked, conversation) match {
      case None =>
        // The bridge's complete cannot be cancelled (a Pekko `Future`); its streamComplete case covers it.
        succeed
      case Some(running) =>
        cancelOnceStarted(parked, running)
    }
  }

  s"$name streamComplete" should "interrupt the provider call when cancelled, and free its thread" in {
    val parked = new Parked(ScriptedLLMClient.always(Reply.text("never")))
    cancelOnceStarted(parked, bridge.startStreamComplete(parked, conversation))
  }

  s"$name agent stream" should "interrupt the model call when cancelled, and free its thread" in {
    val parked = new Parked(ScriptedLLMClient.always(Reply.text("never")))
    cancelOnceStarted(parked, bridge.startStream(agentOf(parked), ThreadId("cancelled"), "go"))
  }

  private def cancelOnceStarted(parked: Parked, running: Cancellable) = {
    withClue("the provider call never started:") {
      parked.started.await(DeadlineSeconds, TimeUnit.SECONDS) shouldBe true
    }
    running.cancel()
    withClue("the provider call did not return after the cancel:") {
      parked.ended.await(DeadlineSeconds, TimeUnit.SECONDS) shouldBe true
    }
    parked.sawInterrupt.get() shouldBe true
  }

  // ---- agents -------------------------------------------------------------------------------------------------

  s"$name agent run" should "return the answer core's Agent.run returns for the same script" in {
    val direct    = agentOf(toolScript()).run("What should I pack for Oslo?")
    val viaBridge = bridge.run(agentOf(toolScript()), "What should I pack for Oslo?")
    direct.map(_.answer) shouldBe Right(Some("It is 21 degrees in Oslo: pack light layers."))
    viaBridge.isRight shouldBe true
    viaBridge.map(r => (r.status, r.answer)).toOption shouldBe direct.map(r => (r.status, r.answer)).toOption
  }

  it should "surface a provider Left as the error core's Agent.run returns" in {
    val error    = SimpleError("model unavailable")
    val direct   = agentOf(ScriptedLLMClient.sequence(Reply.failure(error))).run("q")
    val raised   = bridge.run(agentOf(ScriptedLLMClient.sequence(Reply.failure(error))), "q")
    val expected = direct.left.toOption.getOrElse(fail(s"core's Agent.run did not fail: $direct"))
    val actual   = raised.left.toOption.getOrElse(fail(s"the bridge swallowed the error: $raised"))
    actual.error.getClass shouldBe expected.getClass
    actual.error.message shouldBe expected.message
    actual.message shouldBe expected.message
    (providerError(actual.error) should be).theSameInstanceAs(error)
  }

  s"$name agent stream" should "emit the event sequence core's Agent.stream emits for the same script, then the result" in {
    val threadId = ThreadId("same-events")
    val expected = directStream(agentOf(toolScript()), threadId, "What should I pack for Oslo?")
    val turn     = bridge.stream(agentOf(toolScript()), threadId, "What should I pack for Oslo?")
    turn.events.map(shape) shouldBe expected.events.map(shape)
    turn.outcome.map(r => (r.status, r.answer)) shouldBe expected.outcome.map(r => (r.status, r.answer))
  }

  it should "surface a provider Left as the error core's Agent.stream run ends with" in {
    val error    = SimpleError("model unavailable")
    val expected = directStream(agentOf(ScriptedLLMClient.sequence(Reply.failure(error))), ThreadId("t"), "q")
    val turn     = bridge.stream(agentOf(ScriptedLLMClient.sequence(Reply.failure(error))), ThreadId("t"), "q")
    val failure  = turn.outcome.left.toOption.getOrElse(fail(s"the bridge swallowed the error: ${turn.outcome}"))
    expected.outcome.left.map(r => (r.error.getClass, r.message)) shouldBe Left(
      (failure.error.getClass, failure.message)
    )
    (providerError(failure.error) should be).theSameInstanceAs(error)
  }

  // ---- tools --------------------------------------------------------------------------------------------------

  s"$name agent run" should "run tools off the effect runtime's compute pool" in {
    val seen = new AtomicReference[Option[Thread]](None)
    bridge.run(agentOf(toolScript(), seen), "What should I pack for Oslo?").isRight shouldBe true
    val thread = seen.get().getOrElse(fail("the tool never ran"))
    withClue(s"the tool ran on ${thread.getName}:")(bridge.isComputeThread(thread) shouldBe false)
  }

  s"$name agent stream" should "run tools off the effect runtime's compute pool" in {
    val seen = new AtomicReference[Option[Thread]](None)
    bridge
      .stream(agentOf(toolScript(), seen), ThreadId("tools"), "What should I pack for Oslo?")
      .outcome
      .isRight
      .shouldBe(true)
    val thread = seen.get().getOrElse(fail("the tool never ran"))
    withClue(s"the tool ran on ${thread.getName}:")(bridge.isComputeThread(thread) shouldBe false)
  }
}

object StreamingBridgeContract {

  /** How long a latch is awaited before a hang counts as a failure. */
  val DeadlineSeconds: Long = 60L

  /** How long a parked provider call waits for its interrupt; longer than every deadline that observes it. */
  val ParkSeconds: Long = 120L

  val conversation: Conversation = Conversation(Seq(UserMessage("hello")))

  /** A tool call, then the answer. */
  def toolScript(): ScriptedLLMClient =
    ScriptedLLMClient.sequence(
      Reply.toolCall("get_temperature", """{"city": "Oslo"}"""),
      Reply.text("It is 21 degrees in Oslo: pack light layers.")
    )

  /** An agent with one tool, `get_temperature`, which records the thread it runs on in `seen`. */
  def agentOf(client: LLMClient, seen: AtomicReference[Option[Thread]] = new AtomicReference(None)): Agent = {
    val built = for {
      tool <- ToolBuilder[Map[String, Any], Double](
        "get_temperature",
        "Current temperature in a city, in Celsius.",
        Schema
          .`object`[Map[String, Any]]("Parameters")
          .withProperty(Schema.property("city", Schema.string("City name")))
      ).withHandler { params =>
        seen.set(Some(Thread.currentThread()))
        params.getString("city").map(_ => 21.0)
      }.buildSafe()
      agent <- Agent.builder("contract", client).withTools(new ToolRegistry(Seq(tool))).build()
    } yield agent
    built.fold(e => throw new IllegalStateException(s"could not build the contract's agent: $e"), identity)
  }

  /** The provider's error inside the `NodeFailed` an agent wraps it in. */
  def providerError(error: LLMError): LLMError = error match {
    case GraphError.NodeFailed(_, _, cause) => providerError(cause)
    case other                              => other
  }

  /** What `client` streams when called directly. */
  def directChunks(client: LLMClient): Vector[StreamedChunk] = {
    val chunks = new CopyOnWriteArrayList[StreamedChunk]()
    client.streamComplete(conversation, CompletionOptions(), c => chunks.add(c): Unit)
    chunks.asScala.toVector
  }

  /** Core's own `Agent.stream`: the reference every bridge's event sequence is compared with. */
  def directStream(agent: Agent, threadId: ThreadId, query: String): Turn = {
    val events = new CopyOnWriteArrayList[StreamEvent]()
    val outcome = agent
      .stream(threadId, query)(e => events.add(e): Unit)
      .flatMap(_.await())
      .left
      .map(e => Raised(e, e.message))
    Turn(events.asScala.toVector, outcome)
  }

  /**
   * An event with what differs between two runs of one script left out (ids, timestamps): a durable event's
   * sequence number, node and kind; a live event's node and name.
   */
  def shape(event: StreamEvent): String = event match {
    case StreamEvent.Durable(record) =>
      s"durable ${record.seq} ${record.nodeId.getOrElse("-")} ${record.event.productPrefix}"
    case live: StreamEvent.Live => s"live ${live.nodeId} ${live.name}"
    case other                  => other.productPrefix
  }

  /** Streams `chunks`, then fails with `error`. */
  final class FailingMidStream(chunks: Vector[StreamedChunk], error: LLMError) extends LLMClient {
    def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = Left(error)
    def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = {
      chunks.foreach(onChunk)
      Left(error)
    }
    def getContextWindow(): Int     = 8192
    def getReserveCompletion(): Int = 512
  }

  /**
   * Counts the chunks `delegate` hands the bridge, and stops - as a provider does - once its thread is
   * interrupted or the bridge refuses a chunk. `ended` opens when the call has returned.
   */
  final class Counting(delegate: LLMClient) extends LLMClient {
    val emitted: AtomicInteger = new AtomicInteger(0)
    val ended: CountDownLatch  = new CountDownLatch(1)
    private val stopped        = new AtomicBoolean(false)

    def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
      delegate.complete(conversation, options)

    def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = {
      val result = delegate.streamComplete(
        conversation,
        options,
        chunk =>
          if (!stopped.get()) {
            val delivered = !Thread.currentThread().isInterrupted &&
              CancelledError.catchInterrupt(scala.util.Try(onChunk(chunk))).exists(_.isSuccess)
            if (delivered) emitted.incrementAndGet(): Unit else stopped.set(true)
          }
      )
      ended.countDown()
      if (stopped.get()) Left(CancelledError("counting stream")) else result
    }
    def getContextWindow(): Int     = delegate.getContextWindow()
    def getReserveCompletion(): Int = delegate.getReserveCompletion()
  }

  /**
   * A provider call that parks until its thread is interrupted, as llm4s providers are cancelled, and then
   * returns `Left(CancelledError)`. `started` opens when the call begins, `ended` when it has returned.
   */
  final class Parked(delegate: LLMClient) extends LLMClient {
    val started: CountDownLatch     = new CountDownLatch(1)
    val ended: CountDownLatch       = new CountDownLatch(1)
    val sawInterrupt: AtomicBoolean = new AtomicBoolean(false)

    private def parked(call: => Result[Completion]): Result[Completion] = {
      started.countDown()
      val interrupted =
        CancelledError.catchInterrupt(new CountDownLatch(1).await(ParkSeconds, TimeUnit.SECONDS)).isLeft
      sawInterrupt.set(interrupted)
      ended.countDown()
      if (interrupted) Left(CancelledError("parked provider call")) else call
    }

    def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
      parked(delegate.complete(conversation, options))
    def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] =
      parked(delegate.streamComplete(conversation, options, onChunk))
    def getContextWindow(): Int     = delegate.getContextWindow()
    def getReserveCompletion(): Int = delegate.getReserveCompletion()
  }
}

/** What a bridge raised: the `LLMError` it carries and the message of its carrier (`LLMException`, or the error). */
final case class Raised(error: LLMError, message: String)

/** The elements a stream delivered, and the error it then failed with, if any. */
final case class Streamed[A](items: Vector[A], failure: Option[Raised])

/** A turn streamed through a bridge: its events, then its result or the error the stream failed with. */
final case class Turn(events: Vector[StreamEvent], outcome: Either[Raised, AgentResult])

/** A call started in the background. */
trait Cancellable {

  /** Cancels the call; returns once the bridge reports the cancellation done. */
  def cancel(): Unit
}

/**
 * One effect bridge, driven synchronously: each method runs the bridge's own effect or stream to its end and
 * reports what it produced. A failure that is not the bridge's `LLMError` carrier is thrown, failing the case.
 */
trait EffectBridge {

  def complete(client: LLMClient, conversation: Conversation): Either[Raised, Completion]

  /** `streamComplete`, consumed to its end, or for its first `take` chunks only. */
  def streamComplete(client: LLMClient, conversation: Conversation, take: Option[Int]): Streamed[StreamedChunk]

  /** `complete` started in the background; `None` when the bridge's `complete` cannot be cancelled. */
  def startComplete(client: LLMClient, conversation: Conversation): Option[Cancellable]

  /** `streamComplete` consumed in the background. */
  def startStreamComplete(client: LLMClient, conversation: Conversation): Cancellable

  /** The agent's `run` through the bridge. */
  def run(agent: Agent, query: String): Either[Raised, AgentResult]

  /** The agent's `stream` through the bridge, consumed to its end. */
  def stream(agent: Agent, threadId: ThreadId, query: String): Turn

  /** The agent's `stream` consumed in the background. */
  def startStream(agent: Agent, threadId: ThreadId, query: String): Cancellable

  /** Whether `thread` belongs to the effect runtime's compute pool, where blocking work must never run. */
  def isComputeThread(thread: Thread): Boolean
}
