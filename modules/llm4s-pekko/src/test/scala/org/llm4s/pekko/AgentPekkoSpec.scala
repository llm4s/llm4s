package org.llm4s.pekko

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.Sink
import org.apache.pekko.stream.testkit.scaladsl.TestSink
import org.llm4s.agent.{ AgentBuilder, AgentStatus }
import org.llm4s.agent.graph.{
  Checkpointer,
  Commit,
  EventRecord,
  GraphRuntime,
  InMemoryCheckpointer,
  RunEvent,
  StoredCheckpoint,
  StreamEvent,
  ThreadId
}
import org.llm4s.agent.graph.middleware.ApprovalMiddleware
import org.llm4s.error.{ CancelledError, NetworkError, ProcessingError, SimpleError, ValidationError }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.*
import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolRegistry }
import org.llm4s.types.Result
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Millis, Seconds, Span }
import upickle.default.*

import java.util.concurrent.{ CopyOnWriteArrayList, CountDownLatch, TimeUnit }
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{ Await, ExecutionContext }
import scala.concurrent.duration.*

class AgentPekkoSpec extends AnyFlatSpec with Matchers with Eventually with BeforeAndAfterAll {

  implicit private val system: ActorSystem  = ActorSystem("agent-pekko-spec")
  implicit private val ec: ExecutionContext = ExecutionContext.global

  override def afterAll(): Unit = {
    Await.result(system.terminate(), 30.seconds): Unit
    super.afterAll()
  }

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(10, Seconds), interval = Span(20, Millis))

  private val Patience = 60.seconds

  private def completion(text: String): Completion =
    Completion(id = "id", created = 0L, content = text, model = "m", message = AssistantMessage(Some(text)))

  private def answering(text: String): LLMClient =
    new Fixtures.Scripted(_ => Right(completion(text)), () => Right(completion(text)))

  private def pekko(client: LLMClient)(configure: AgentBuilder => AgentBuilder = identity): AgentPekko =
    AgentPekko(Fixtures.agentOf(client)(configure))

  private def items(source: org.apache.pekko.stream.scaladsl.Source[AgentStreamItem, ?]): Vector[AgentStreamItem] =
    Await.result(source.runWith(Sink.seq), Patience).toVector

  private def failureOf(source: org.apache.pekko.stream.scaladsl.Source[AgentStreamItem, ?]): LLMException =
    Await.result(source.runWith(Sink.seq).failed, Patience) match {
      case e: LLMException => e
      case other           => fail(s"expected an LLMException, got $other")
    }

  private def durableEvents(all: Vector[AgentStreamItem]): Vector[RunEvent] =
    all.collect { case AgentStreamItem.Event(StreamEvent.Durable(r)) => r.event }

  /** A store that refuses every commit carrying a `RunCompleted`: the run ends with no terminal event. */
  final private class NoTerminal extends Checkpointer {
    private val underlying = InMemoryCheckpointer()
    def commit(threadId: ThreadId, commit: Commit): Result[Vector[EventRecord]] =
      if (!commit.events.exists(_.event == RunEvent.RunCompleted)) underlying.commit(threadId, commit)
      else Left(ProcessingError("store", "store down"))
    def latest(threadId: ThreadId): Result[Option[StoredCheckpoint]] = underlying.latest(threadId)
    def eventsAfter(threadId: ThreadId, afterSeq: Long, limit: Int): Result[Vector[EventRecord]] =
      underlying.eventsAfter(threadId, afterSeq, limit)
    def compactEvents(threadId: ThreadId, beforeSeq: Long): Result[Unit] =
      underlying.compactEvents(threadId, beforeSeq)
    def deleteThread(threadId: ThreadId): Result[Unit] = underlying.deleteThread(threadId)
  }

  /** An in-memory store that opens `completed` once a commit carrying `RunCompleted` is stored. */
  final private class SignalsCompletion extends Checkpointer {
    private val underlying = InMemoryCheckpointer()
    val completed          = new CountDownLatch(1)
    def commit(threadId: ThreadId, commit: Commit): Result[Vector[EventRecord]] = {
      val stored = underlying.commit(threadId, commit)
      if (commit.events.exists(_.event == RunEvent.RunCompleted)) completed.countDown()
      stored
    }
    def latest(threadId: ThreadId): Result[Option[StoredCheckpoint]] = underlying.latest(threadId)
    def eventsAfter(threadId: ThreadId, afterSeq: Long, limit: Int): Result[Vector[EventRecord]] =
      underlying.eventsAfter(threadId, afterSeq, limit)
    def compactEvents(threadId: ThreadId, beforeSeq: Long): Result[Unit] =
      underlying.compactEvents(threadId, beforeSeq)
    def deleteThread(threadId: ThreadId): Result[Unit] = underlying.deleteThread(threadId)
  }

  final case class EchoResult(value: String)
  object EchoResult { implicit val rw: ReadWriter[EchoResult] = macroRW }

  private val echoTool = ToolBuilder[Map[String, Any], EchoResult](
    "echo",
    "echoes",
    Schema.`object`[Map[String, Any]]("params").withRequiredField("v", Schema.string("value"))
  ).withHandler(ex => ex.getString("v").map(EchoResult(_))).buildSafe() match {
    case Right(t) => t
    case Left(e)  => fail(s"could not build tool: $e")
  }

  private def tools: ToolRegistry = new ToolRegistry(Seq(echoTool))

  private def toolCall(i: Int): Completion = {
    val calls = Seq(ToolCall(id = s"call-$i", name = "echo", arguments = ujson.Obj("v" -> "x")))
    Completion(
      id = s"t$i",
      created = 0L,
      content = "",
      model = "m",
      message = AssistantMessage("", calls),
      toolCalls = calls.toList
    )
  }

  /** Records every request; answers with `reply(callIndex)`. */
  final private class Recording(reply: Int => Completion) extends LLMClient {
    val conversations = new CopyOnWriteArrayList[Conversation]()
    val calls         = new AtomicInteger(0)
    def complete(c: Conversation, o: CompletionOptions): Result[Completion] = {
      val i = calls.getAndIncrement()
      conversations.add(c)
      Right(reply(i))
    }
    def streamComplete(c: Conversation, o: CompletionOptions, onChunk: StreamedChunk => Unit): Result[Completion] =
      complete(c, o)
    def getContextWindow(): Int     = 8192
    def getReserveCompletion(): Int = 512
  }

  // ---- stream ------------------------------------------------------------------------------------------------

  "AgentPekko.stream" should "emit the run's events, then Done with its result" in {
    val all = items(pekko(answering("hello"))().stream(ThreadId("p1"), "hi"))
    all.last match {
      case AgentStreamItem.Done(r) => r.answer shouldBe Some("hello")
      case other                   => fail(s"last item was $other")
    }
    all.init.forall { case AgentStreamItem.Event(_) => true; case _ => false } shouldBe true
    durableEvents(all).head shouldBe RunEvent.RunStarted(None, None)
    durableEvents(all).last shouldBe RunEvent.RunCompleted
  }

  it should "end with exactly one Done" in {
    val all = items(pekko(answering("hello"))().stream(ThreadId("p1b"), "hi"))
    all.count { case AgentStreamItem.Done(_) => true; case _ => false } shouldBe 1
  }

  it should "fail with the run's error" in {
    val client = new Fixtures.Scripted(
      _ => Left(NetworkError("down", None, "http://x")),
      () => Left(NetworkError("down", None, "http://x"))
    )
    Fixtures.causeOf(failureOf(pekko(client)().stream(ThreadId("p2"), "hi")).error) shouldBe a[NetworkError]
  }

  it should "fail at once for a refused start" in {
    failureOf(pekko(answering("x"))().stream(ThreadId("p3"), "  ")).error shouldBe a[ValidationError]
  }

  it should "end, rather than hang, when the run ends without a terminal event" in {
    val agent = pekko(answering("hello"))(_.withRuntime(GraphRuntime(new NoTerminal)))
    failureOf(agent.stream(ThreadId("p4"), "hi")).error.message should include("store down")
  }

  it should "give a slow consumer a LiveGap for the deltas it missed, not cancel the run" in {
    // Far more live text than the buffers hold, sent while the consumer is held on its first item until the run
    // has committed RunCompleted - so most deltas are dropped, whatever the scheduling. The run's durable events
    // must still all arrive: dropped live events never disconnect the subscription.
    val deltas = 3000
    val client = new Fixtures.Scripted(
      onChunk => {
        (0 until deltas).foreach(i => onChunk(Fixtures.chunk(i)))
        Right(completion("done"))
      },
      () => Right(completion("done"))
    )
    val store = new SignalsCompletion
    val agent = pekko(client)(_.withRuntime(GraphRuntime(store)).withStreaming())
    val probe = agent.stream(ThreadId("p5"), "hi").runWith(TestSink[AgentStreamItem]())
    probe.request(1)
    val first = probe.within(Patience)(probe.expectNext())
    store.completed.await(Fixtures.DeadlineSeconds, TimeUnit.SECONDS) shouldBe true
    probe.request(Long.MaxValue)
    val rest = probe.within(Patience)(probe.receiveWithin(Patience, 100000))
    val all  = (first +: rest.collect { case i: AgentStreamItem => i }).toVector

    val events = all.collect { case AgentStreamItem.Event(e) => e }
    val gaps   = events.collect { case StreamEvent.LiveGap(n) => n }.sum
    gaps should be > 0
    // every delta delivered or counted; ModelCallStarted is live too
    (events.count(_.isInstanceOf[StreamEvent.Live]) + gaps) should be >= deltas
    all.last match {
      case AgentStreamItem.Done(r) => r.answer shouldBe Some("done")
      case other                   => fail(s"last item was $other")
    }
    durableEvents(all).last shouldBe RunEvent.RunCompleted
  }

  it should "cancel the run when the consumer stops early, releasing its subscription" in {
    val calls    = new AtomicInteger(0)
    val sent     = new CountDownLatch(1)
    val unparked = new CountDownLatch(1)
    // the first call floods the subscription with more live text than the buffer holds, then parks until
    // interrupted; the consumer stops after the first item
    val client = new Fixtures.Scripted(
      onChunk =>
        if (calls.getAndIncrement() == 0) {
          (0 until 3000).foreach(i => onChunk(Fixtures.chunk(i)))
          sent.countDown()
          if (Fixtures.parkUntilInterrupted()) unparked.countDown()
          Left(CancelledError("test"))
        } else Right(completion("recovered")),
      () => Right(completion("recovered"))
    )
    val runtime  = GraphRuntime.inMemory()
    val agent    = pekko(client)(_.withRuntime(runtime).withStreaming())
    val threadId = ThreadId("p6")
    val first = Await.result(
      agent
        .stream(threadId, "hi")
        .map { item =>
          // the model call has started and flooded the subscription before the consumer stops
          sent.await(Fixtures.DeadlineSeconds, TimeUnit.SECONDS): Unit
          item
        }
        .take(1)
        .runWith(Sink.seq),
      Patience
    )
    first should have size 1
    unparked.await(Fixtures.PromptSeconds, TimeUnit.SECONDS) shouldBe true
    // the run's subscription has ended
    eventually(runtime.liveSubscriptions(threadId) shouldBe 0)
    Await.result(agent.recover(threadId), Patience).answer shouldBe Some("recovered")
  }

  it should "cancel the run when the stream is cancelled while the consumer waits for the next event" in {
    val calls    = new AtomicInteger(0)
    val parked   = new CountDownLatch(1)
    val unparked = new CountDownLatch(1)
    // the first call sends one delta, then parks mid-call until interrupted; later calls answer
    val client = new Fixtures.Scripted(
      onChunk =>
        if (calls.getAndIncrement() == 0) {
          onChunk(Fixtures.chunk(0))
          parked.countDown()
          if (Fixtures.parkUntilInterrupted()) unparked.countDown()
          Left(CancelledError("test"))
        } else Right(completion("recovered")),
      () => Right(completion("recovered"))
    )
    val runtime  = GraphRuntime.inMemory()
    val agent    = pekko(client)(_.withRuntime(runtime).withStreaming())
    val threadId = ThreadId("p7")
    val probe    = agent.stream(threadId, "hi").runWith(TestSink[AgentStreamItem]())
    probe.request(1000)
    // the model is parked mid-call, so the consumer is waiting for the next event
    parked.await(Fixtures.DeadlineSeconds, TimeUnit.SECONDS) shouldBe true
    probe.within(Patience)(probe.expectNext())
    probe.cancel()
    unparked.await(Fixtures.PromptSeconds, TimeUnit.SECONDS) shouldBe true // the model call was interrupted
    eventually(runtime.liveSubscriptions(threadId) shouldBe 0)
    Await.result(agent.recover(threadId), Patience).answer shouldBe Some("recovered")
  }

  it should "start the turn once per materialization, and only when materialized" in {
    val client = new Recording(_ => completion("hi"))
    val source = pekko(client)().stream(ThreadId("p8"), "hello")
    client.calls.get() shouldBe 0
    items(source).last shouldBe a[AgentStreamItem.Done]
    items(source).last shouldBe a[AgentStreamItem.Done]
    client.calls.get() shouldBe 2
  }

  it should "fail at once for a buffer below 1" in {
    val client = new Recording(_ => completion("hi"))
    failureOf(pekko(client)().stream(ThreadId("p9"), "hello", bufferSize = 0)).error shouldBe a[ValidationError]
    client.calls.get() shouldBe 0
  }

  "AgentPekko.streamRecover" should "stream a recovered run to Done" in {
    val calls = new AtomicInteger(0)
    val client = new Fixtures.Scripted(
      _ => if (calls.getAndIncrement() == 0) Left(NetworkError("down", None, "x")) else Right(completion("back")),
      () => if (calls.getAndIncrement() == 0) Left(NetworkError("down", None, "x")) else Right(completion("back"))
    )
    val agent    = pekko(client)()
    val threadId = ThreadId("p10")
    failureOf(agent.stream(threadId, "hi"))
    val all = items(agent.streamRecover(threadId))
    all.last match {
      case AgentStreamItem.Done(r) => r.answer shouldBe Some("back")
      case other                   => fail(s"last item was $other")
    }
    durableEvents(all).last shouldBe RunEvent.RunCompleted
  }

  "AgentPekko.streamResume" should "stream a turn parked on an approval to Done once it is approved" in {
    val client = new Recording(i => if (i == 0) toolCall(0) else completion("shipped"))
    val agent =
      pekko(client)(_.withTools(tools).withMiddleware(new ApprovalMiddleware(r => Some(s"review ${r.call.id}"))))
    val parked = Await.result(agent.run("go"), Patience)
    val ids = parked.status match {
      case AgentStatus.Suspended(approvals, _) => approvals.map(_._1)
      case other                               => fail(s"expected Suspended, got $other")
    }
    ids should have size 1
    val all = items(agent.streamResume(parked.threadId, Map(parked.approve(ids.head))))
    all.last match {
      case AgentStreamItem.Done(r) => r.status shouldBe AgentStatus.Completed("shipped")
      case other                   => fail(s"last item was $other")
    }
  }

  // ---- Futures -----------------------------------------------------------------------------------------------

  "AgentPekko.run" should "return an AgentResult with Completed status when the agent finishes" in {
    Await.result(pekko(answering("4"))().run("What is 2+2?"), Patience).status shouldBe AgentStatus.Completed("4")
  }

  it should "include the query in the conversation" in {
    val result = Await.result(pekko(answering("answer"))().run("my query"), Patience)
    result.messages.map(_.content) should contain("my query")
  }

  it should "fail with an LLMException carrying the run's error when the LLM call fails" in {
    val client  = new Fixtures.Scripted(_ => Left(SimpleError("agent-fail")), () => Left(SimpleError("agent-fail")))
    val failure = Await.result(pekko(client)().run("q").failed, Patience)
    failure match {
      case e: LLMException => Fixtures.causeOf(e.error) shouldBe SimpleError("agent-fail")
      case other           => fail(s"expected an LLMException, got $other")
    }
  }

  it should "fail with a ValidationError for a blank query" in {
    Await.result(pekko(answering("x"))().run("  ").failed, Patience) match {
      case e: LLMException => e.error shouldBe a[ValidationError]
      case other           => fail(s"expected an LLMException, got $other")
    }
  }

  "AgentPekko.continueConversation" should "answer a follow-up on the same thread" in {
    val agent = pekko(answering("6"))()
    val s1    = Await.result(agent.run("What is 2+2?"), Patience)
    val s2    = Await.result(agent.continueConversation(s1, "And 3+3?"), Patience)
    s2.status shouldBe AgentStatus.Completed("6")
    s2.threadId shouldBe s1.threadId
    s2.messages.map(_.content) should contain("And 3+3?")
  }

  it should "fail with an LLMException when the continuation fails" in {
    val calls = new AtomicInteger(0)
    val client = new Fixtures.Scripted(
      _ => if (calls.getAndIncrement() == 0) Right(completion("4")) else Left(SimpleError("agent-fail")),
      () => if (calls.getAndIncrement() == 0) Right(completion("4")) else Left(SimpleError("agent-fail"))
    )
    val agent = pekko(client)()
    val s1    = Await.result(agent.run("First"), Patience)
    Await.result(agent.continueConversation(s1, "Follow-up").failed, Patience) shouldBe a[LLMException]
  }

  "AgentPekko.recover" should "complete a run that failed with a provider error" in {
    val calls = new AtomicInteger(0)
    val client = new LLMClient {
      def complete(c: Conversation, o: CompletionOptions): Result[Completion] =
        calls.getAndIncrement() match {
          case 0 => Right(toolCall(0))
          case 1 => Left(SimpleError("provider down"))
          case _ => Right(completion("recovered"))
        }
      def streamComplete(c: Conversation, o: CompletionOptions, onChunk: StreamedChunk => Unit): Result[Completion] =
        complete(c, o)
      def getContextWindow(): Int     = 8192
      def getReserveCompletion(): Int = 512
    }
    val (capture, thread) = Fixtures.threadCapture()
    val agent             = pekko(client)(_.withTools(tools).withMiddleware(capture))
    val failed            = Await.result(agent.run("go").failed, Patience)
    Fixtures.causeOf(failed.asInstanceOf[LLMException].error) shouldBe SimpleError("provider down")
    Await.result(agent.recover(thread.get().get), Patience).status shouldBe AgentStatus.Completed("recovered")
  }

  "AgentPekko.resume" should "complete a turn parked on an approval once it is approved" in {
    val client = new Recording(i => if (i == 0) toolCall(0) else completion("shipped"))
    val agent =
      pekko(client)(_.withTools(tools).withMiddleware(new ApprovalMiddleware(r => Some(s"review ${r.call.id}"))))
    val parked = Await.result(agent.run("go"), Patience)
    val ids = parked.status match {
      case AgentStatus.Suspended(approvals, _) => approvals.map(_._1)
      case other                               => fail(s"expected Suspended, got $other")
    }
    val done = Await.result(agent.resume(parked.threadId, Map(parked.approve(ids.head))), Patience)
    done.status shouldBe AgentStatus.Completed("shipped")
  }

  "LLMClientPekko.agent" should "apply the configuration to the agent" in {
    val client = new Recording(_ => completion("x"))
    val agent  = LLMClientPekko(client).agent("assistant")(_.withSystemPrompt("CONFIGURED")).toOption.get
    Await.result(agent.run("q"), Patience).status shouldBe AgentStatus.Completed("x")
    client.conversations.get(0).messages.collect { case m: SystemMessage => m.content }.head should include(
      "CONFIGURED"
    )
  }
}
