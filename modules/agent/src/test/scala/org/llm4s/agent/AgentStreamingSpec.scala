package org.llm4s.agent

import org.llm4s.agent.events.AgentEvents
import org.llm4s.agent.graph.*
import org.llm4s.agent.graph.middleware.{ AgentMiddleware, MiddlewareId, ModelRequest }
import org.llm4s.error.NetworkError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.*
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.{ CopyOnWriteArrayList, CountDownLatch, TimeUnit }
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*

class AgentStreamingSpec extends AnyFlatSpec with Matchers:

  private val usage = Some(TokenUsage(promptTokens = 5, completionTokens = 3, totalTokens = 8))

  /** Streams `chunks` of text, then returns the completion of their concatenation. */
  final private class Streaming(chunks: Seq[String], thinking: Seq[String] = Nil, toolCalls: List[ToolCall] = Nil)
      extends LLMClient:
    val streamed  = new AtomicInteger(0)
    val completed = new AtomicInteger(0)
    private def completion =
      val text = chunks.mkString
      Completion(
        "c",
        0L,
        text,
        "test-model",
        AssistantMessage(Option(text).filter(_.nonEmpty), toolCalls),
        toolCalls,
        usage
      )
    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
      completed.incrementAndGet()
      Right(completion)
    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] =
      streamed.incrementAndGet()
      thinking.foreach(t => onChunk(StreamedChunk("c", None, thinkingDelta = Some(t))))
      chunks.foreach(c => onChunk(StreamedChunk("c", Some(c))))
      toolCalls.foreach(tc => onChunk(StreamedChunk("c", None, toolCall = Some(tc))))
      Right(completion)
    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 1024

  final private class Seen:
    val events = new CopyOnWriteArrayList[StreamEvent]()
    val ended  = new CountDownLatch(1)
    val listener: StreamEvent => Unit = e =>
      events.add(e)
      e match
        case StreamEvent.Durable(record) =>
          record.event match
            case RunEvent.RunCompleted | RunEvent.RunFailed(_) | RunEvent.RunSuspended(_) | RunEvent.RunCancelled |
                RunEvent.RunTimedOut =>
              ended.countDown()
            case _ => ()
        case _ => ()
    def all: Vector[StreamEvent] = events.asScala.toVector

  /**
   * Runs `query` on a new thread of `builder`'s agent, collecting the thread's events until the run's
   * terminal durable event. Task 5 replaces this with `agent.stream`.
   */
  private def streamed(builder: AgentBuilder, query: String): (Result[AgentResult], Seen) =
    val runtime  = GraphRuntime.inMemory()
    val agent    = builder.withRuntime(runtime).build().fold(e => fail(e.message), identity)
    val threadId = ThreadId(java.util.UUID.randomUUID().toString)
    val c        = Seen()
    val sub      = runtime.subscribe(threadId)(c.listener).fold(e => fail(e.message), identity)
    // the dispatcher joins the live set after its (empty) replay
    Thread.sleep(200)
    val result = agent.run(threadId, query)
    c.ended.await(5, TimeUnit.SECONDS) shouldBe true
    sub.cancel()
    (result, c)

  "A streaming agent" should "send the answer's text as TextDelta events, in order" in {
    val client      = Streaming(Seq("Hel", "lo", " world"))
    val (result, c) = streamed(Agent.builder("assistant", client).withStreaming(), "hi")
    result.map(_.answer) shouldBe Right(Some("Hello world"))
    c.all.collect { case AgentEvents.TextDelta(d) => d.text } shouldBe Vector("Hel", "lo", " world")
    c.all.collect { case AgentEvents.TextDelta(d) => d.attempt }.distinct shouldBe Vector(1)
    client.streamed.get shouldBe 1
    client.completed.get shouldBe 0
  }

  it should "send thinking as ThinkingDelta" in {
    val (_, c) = streamed(Agent.builder("assistant", Streaming(Seq("ok"), thinking = Seq("hmm"))).withStreaming(), "hi")
    c.all.collect { case AgentEvents.ThinkingDelta(d) => d.text } shouldBe Vector("hmm")
  }

  it should "send ModelCallStarted before the first delta and ModelCallCompleted after the call" in {
    val (_, c) = streamed(Agent.builder("assistant", Streaming(Seq("a", "b"))).withStreaming(), "hi")
    val kinds = c.all.collect {
      case AgentEvents.ModelCallStarted(s)   => s"started:${s.agent}:${s.attempt}"
      case AgentEvents.TextDelta(_)          => "delta"
      case AgentEvents.ModelCallCompleted(m) => s"completed:${m.model}:${m.attempts}:${m.usage.map(_.totalTokens)}"
    }
    kinds shouldBe Vector("started:assistant:1", "delta", "delta", "completed:test-model:1:Some(8)")
  }

  it should "number attempts when a model wrapper retries" in {
    val failures = new AtomicInteger(0)
    final class FlakyOnce extends LLMClient:
      private val inner                                                                = Streaming(Seq("x"))
      override def complete(c: Conversation, o: CompletionOptions): Result[Completion] = inner.complete(c, o)
      override def streamComplete(c: Conversation, o: CompletionOptions, f: StreamedChunk => Unit): Result[Completion] =
        f(StreamedChunk("c", Some("partial")))
        if failures.getAndIncrement() == 0 then Left(NetworkError("dropped", None, "test"))
        else inner.streamComplete(c, o, f)
      override def getContextWindow(): Int     = 4096
      override def getReserveCompletion(): Int = 1024
    val retry = new AgentMiddleware:
      val id = MiddlewareId("retry")
      override def wrapModelCall(request: ModelRequest, context: RunContext)(
        next: ModelRequest => Result[Completion]
      ): Result[Completion] = next(request).orElse(next(request))
    val (result, c) = streamed(Agent.builder("assistant", FlakyOnce()).withStreaming().withMiddleware(retry), "hi")
    result.map(_.answer) shouldBe Right(Some("x"))
    c.all.collect { case AgentEvents.ModelCallStarted(s) => s.attempt } shouldBe Vector(1, 2)
    c.all.collect { case AgentEvents.TextDelta(d) => d.attempt -> d.text } shouldBe
      Vector(1 -> "partial", 2 -> "partial", 2 -> "x")
    c.all.collect { case AgentEvents.ModelCallCompleted(m) => m.attempts } shouldBe Vector(2)
  }

  it should "send no TextDelta for a tool-call-only stream, and complete as without streaming" in {
    val call = ToolCall("call-1", "handoff_to_nobody", ujson.Obj())
    // a tool call the agent does not know: the loop records an error result and asks again;
    // the second answer is text
    final class ToolThenText extends LLMClient:
      private val n = new AtomicInteger(0)
      private def answer(i: Int): Completion =
        if i == 0 then Completion("c", 0L, "", "m", AssistantMessage(None, Seq(call)), List(call), usage)
        else Completion("c", 0L, "done", "m", AssistantMessage("done"), Nil, usage)
      override def complete(c: Conversation, o: CompletionOptions): Result[Completion] =
        Right(answer(n.getAndIncrement()))
      override def streamComplete(c: Conversation, o: CompletionOptions, f: StreamedChunk => Unit): Result[Completion] =
        val i = n.getAndIncrement()
        if i == 0 then f(StreamedChunk("c", None, toolCall = Some(call)))
        else f(StreamedChunk("c", Some("done")))
        Right(answer(i))
      override def getContextWindow(): Int     = 4096
      override def getReserveCompletion(): Int = 1024
    val (result, c) = streamed(Agent.builder("assistant", ToolThenText()).withStreaming(), "hi")
    result.map(_.answer) shouldBe Right(Some("done"))
    c.all.collect { case AgentEvents.TextDelta(d) => d.text } shouldBe Vector("done")
    val (plain, p) = streamed(Agent.builder("assistant", ToolThenText()), "hi")
    plain.map(_.answer) shouldBe Right(Some("done"))
    plain.map(_.messages.map(_.content)) shouldBe result.map(_.messages.map(_.content))
    p.all.collect { case AgentEvents.ModelCallCompleted(m) => m } shouldBe
      c.all.collect { case AgentEvents.ModelCallCompleted(m) => m }
  }

  "A non-streaming agent" should "call complete and send no deltas" in {
    val client      = Streaming(Seq("Hel", "lo"))
    val (result, c) = streamed(Agent.builder("assistant", client), "hi")
    result.map(_.answer) shouldBe Right(Some("Hello"))
    client.completed.get shouldBe 1
    client.streamed.get shouldBe 0
    c.all.collect { case AgentEvents.TextDelta(d) => d } shouldBe empty
    c.all.collect { case AgentEvents.ModelCallCompleted(m) => m.attempts } shouldBe Vector(1)
  }

  "withStreaming" should "not change the agent's graph version" in {
    val client = Streaming(Seq("x"))
    val plain  = Agent.builder("assistant", client)
    AgentBuilder.fingerprint(Vector(plain.withStreaming())) shouldBe AgentBuilder.fingerprint(Vector(plain))
  }

  "A handoff" should "be reported as a durable HandedOff event" in {
    val target = Agent.builder("physics", Streaming(Seq("E=mc^2")))
    val call   = ToolCall("call-1", "handoff_to_physics", ujson.Obj())
    val root = new LLMClient:
      override def complete(c: Conversation, o: CompletionOptions): Result[Completion] =
        Right(Completion("c", 0L, "", "m", AssistantMessage(None, Seq(call)), List(call), usage))
      override def streamComplete(c: Conversation, o: CompletionOptions, f: StreamedChunk => Unit): Result[Completion] =
        complete(c, o)
      override def getContextWindow(): Int     = 4096
      override def getReserveCompletion(): Int = 1024
    val (_, c)    = streamed(Agent.builder("triage", root).withHandoffs(Handoff.to("physics", target, "physics")), "q")
    val handedOff = c.all.collect { case AgentEvents.HandedOff(h) => h }
    handedOff shouldBe Vector(org.llm4s.agent.events.HandedOff("triage", "physics"))
    c.all.collect { case e @ AgentEvents.HandedOff(_) => e }.forall {
      case StreamEvent.Durable(_) => true
      case _                      => false
    } shouldBe true
  }
