package org.llm4s.agent.delegation

import org.llm4s.agent.{ Agent, CompletionFixture, ScriptedLLMClient, SpecTools }
import org.llm4s.agent.events.{ AgentEvents, SubagentEvents }
import org.llm4s.agent.graph.*
import org.llm4s.agent.graph.middleware.{ AgentMiddleware, MiddlewareId, ToolCallRequest }
import org.llm4s.agent.graph.tool.{ AgentTool, ToolContent, ToolContext, ToolOutcome, ToolSet }
import org.llm4s.agent.graph.toolloop.AgentInput
import org.llm4s.error.ProcessingError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.*
import org.llm4s.trace.{ TraceEvent, Tracing }
import org.llm4s.types.Result
import org.scalatest.{ EitherValues, LoneElement, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import java.util.concurrent.{ ConcurrentLinkedQueue, CopyOnWriteArrayList, CountDownLatch, TimeUnit }
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * Nested streams, parent and child ids in events and tracing, and child-task metrics (#2051, design 4.47): a parent's
 * merged stream of its children's events, `subscribeChild`, `TaskStarted` / `TaskEnded`, and `AgentRunEnded`'s
 * parent ids.
 */
class NestedStreamSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues with LoneElement {
  import DelegationSpec.*
  import NestedStreamSpec.*

  private def delegatingClient(childAnswer: String, chunks: Int = 4, childPause: FiniteDuration = Duration.Zero) =
    Streaming(
      RoutingClient {
        case Lead(messages) if toolResults(messages).isEmpty =>
          Right(calling(call("c1", "task", ujson.Obj("subagent_type" -> "general", "description" -> "summarise X"))))
        case Lead(_) => Right(CompletionFixture.simple("composed"))
        case Child("general", _) =>
          if childPause > Duration.Zero then Thread.sleep(childPause.toMillis)
          Right(CompletionFixture.simple(childAnswer))
      },
      chunks
    )

  private def streamed(agent: Agent, nested: Boolean = true): Vector[StreamEvent] =
    val seen = new CopyOnWriteArrayList[StreamEvent]()
    agent.stream(ThreadId("t1"), "go", nested = nested)(seen.add(_): Unit).value.await().value
    seen.asScala.toVector

  "a merged stream" should "give TaskStarted, the child's events nested, then TaskEnded, in that order" in {
    val agent = lead(delegatingClient("hello world")).withSubagents(general.withStreaming()).build().value
    val seen  = streamed(agent)

    val started = seen.indexWhere(SubagentEvents.TaskStarted.unapply(_).isDefined)
    val ended   = seen.indexWhere(SubagentEvents.TaskEnded.unapply(_).isDefined)
    val nested  = seen.zipWithIndex.collect { case (n: StreamEvent.Nested, i) => n -> i }
    val deltas  = nested.collect { case (StreamEvent.Nested(_, AgentEvents.TextDelta(d)), i) => d.text -> i }
    val childEnd = nested.collectFirst {
      case (StreamEvent.Nested(_, StreamEvent.Durable(r)), i) if r.event == RunEvent.RunCompleted => i
    }.value

    started should be >= 0
    deltas.map(_._1).mkString shouldBe "hello world"
    started should be < nested.head._2
    nested.head._1.event match
      case StreamEvent.Durable(r) => r.event shouldBe a[RunEvent.RunStarted]
      case other                  => fail(s"the child's first event is its claim, not $other")
    deltas.last._2 should be < childEnd
    childEnd should be < ended

    // one child, named alike by the three
    val ref      = SubagentEvents.TaskStarted.unapply(seen(started)).value.child
    val endedRef = SubagentEvents.TaskEnded.unapply(seen(ended)).value.child
    endedRef shouldBe ref
    nested.map(_._1.child).distinct.loneElement shouldBe ref
    ref.threadId shouldBe agent.children(ThreadId("t1")).value.loneElement.threadId
    ref.agent.value shouldBe "general"
    ref.toolCallId.map(_.value) shouldBe Some("c1")
    ref.depth shouldBe 1
  }

  it should "match the child's events with onChild, and never with on" in {
    val agent = lead(delegatingClient("hello world")).withSubagents(general.withStreaming()).build().value
    val seen  = streamed(agent)
    val handlers = EventHandlers
      .on(AgentEvents.TextDelta)(d => s"parent:${d.text}")
      .onChild(AgentEvents.TextDelta)((child, d) => s"${child.agent.value}:${d.text}")

    val texts = seen.flatMap(handlers.dispatch)
    texts.forall(_.startsWith("general:")) shouldBe true
    texts.map(_.stripPrefix("general:")).mkString shouldBe "hello world"
  }

  it should "nest a grandchild's events twice, matched by onChild with the innermost child" in {
    val client = Streaming(
      RoutingClient {
        case Lead(messages) if toolResults(messages).isEmpty =>
          Right(calling(call("c1", "task", ujson.Obj("subagent_type" -> "middle", "description" -> "delegate on"))))
        case Lead(_) => Right(CompletionFixture.simple("composed"))
        case Child("middle", messages) if toolResults(messages).isEmpty =>
          Right(calling(call("m1", "task", ujson.Obj("subagent_type" -> "general", "description" -> "the leaf"))))
        case Child("middle", _)  => Right(CompletionFixture.simple("middle done"))
        case Child("general", _) => Right(CompletionFixture.simple("leaf text"))
      },
      chunks = 2
    )
    // the grandchild lives in the parent's runtime, as every descendant does: the middle agent's subagent is bound there
    val runtime = GraphRuntime.inMemory()
    val middle = TypedAgent
      .text("middle", "Delegates again")
      .withSystemPrompt("CHILD:middle")
      .withMiddleware(
        new DelegationMiddleware(
          Vector(general.withStreaming().bind(client, runtime).value),
          DelegationConfig().withMaxDepth(2),
          depth = 1
        )
      )
    val agent = lead(client).withRuntime(runtime).withSubagents(middle.withStreaming()).build().value
    val seen  = streamed(agent)

    val twice = seen.collect { case StreamEvent.Nested(outer, StreamEvent.Nested(inner, event)) =>
      (outer, inner, event)
    }
    twice should not be empty
    twice.map(_._1.agent.value).distinct shouldBe Vector("middle")
    twice.map(_._2.agent.value).distinct shouldBe Vector("general")
    twice.map(_._2.depth).distinct shouldBe Vector(2)
    val leaf =
      seen.flatMap(EventHandlers.onChild(AgentEvents.TextDelta)((child, d) => child.agent.value -> d.text).dispatch)
    leaf.filter(_._1 == "general").map(_._2).mkString shouldBe "leaf text"
  }

  "a stream without nested" should "get TaskStarted and TaskEnded but none of the child's events" in {
    val agent = lead(delegatingClient("hello world")).withSubagents(general.withStreaming()).build().value
    val seen  = streamed(agent, nested = false)

    seen.collect { case n: StreamEvent.Nested => n } shouldBe empty
    seen.flatMap(SubagentEvents.TaskStarted.unapply) should have size 1
    seen.flatMap(SubagentEvents.TaskEnded.unapply) should have size 1
  }

  "TaskEnded" should "be committed once, carry the child's status, roll-up and duration, and no content (rule 9)" in {
    val runtime = GraphRuntime.inMemory()
    val agent = lead(delegatingClient("SECRET-REPORT", childPause = 60.millis))
      .withRuntime(runtime)
      .withSubagents(general)
      .build()
      .value
    agent.run(ThreadId("t1"), "go").value
    val parentLog = runtime.storedEvents(ThreadId("t1"), 0L, 1000).value
    val ended     = parentLog.flatMap(r => SubagentEvents.TaskEnded.unapply(StreamEvent.Durable(r))).loneElement
    val childId   = agent.children(ThreadId("t1")).value.loneElement.threadId
    val childLog  = runtime.storedEvents(childId, 0L, 1000).value

    ended.status shouldBe "completed"
    ended.child.threadId shouldBe childId
    ended.usage.inputTokens shouldBe 10L
    ended.usage.outputTokens shouldBe 20L
    ended.duration should be >= 60.millis
    upickle.default.writeJs(ended).obj.keySet shouldBe Set("child", "status", "usage", "duration")
    // no durable event of the parent or the child carries the request or the report
    (parentLog ++ childLog).map(r => upickle.default.write(r)).foreach { json =>
      (json should not).include("SECRET-REPORT")
      (json should not).include("summarise X")
    }
  }

  it should "name a refusal by its label, never its reason" in {
    val client = RoutingClient {
      case Lead(messages) if toolResults(messages).isEmpty =>
        Right(calling(call("c1", "task", ujson.Obj("subagent_type" -> "general", "description" -> "x"))))
      case Lead(_)             => Right(CompletionFixture.simple("composed"))
      case Child("general", _) => Left(ProcessingError("provider", "SECRET-REASON"))
    }
    val runtime   = GraphRuntime.inMemory()
    val agent     = lead(client).withRuntime(runtime).withSubagents(general).build().value
    val seen      = streamed(agent)
    val parentLog = runtime.storedEvents(ThreadId("t1"), 0L, 1000).value
    val ended     = parentLog.flatMap(r => SubagentEvents.TaskEnded.unapply(StreamEvent.Durable(r))).loneElement
    ended.status shouldBe "failed"
    (upickle.default.write(ended) should not).include("SECRET-REASON")
    // it names a run the child's own log holds: the one that recorded its failure
    val childLog = runtime.storedEvents(ended.child.threadId, 0L, 1000).value
    childLog.map(_.runId) should contain(ended.child.runId.value)
    childLog.last.runId shouldBe ended.child.runId.value
    // and the merged stream announced that run - its settlement, whose events were not forwarded - with a gap
    val announced = seen.flatMap(SubagentEvents.TaskStarted.unapply).map(_.child.runId)
    announced should contain(ended.child.runId)
    seen.collect { case StreamEvent.Nested(c, StreamEvent.LiveGap(0)) => c.runId } shouldBe Vector(ended.child.runId)
  }

  it should "be committed by neither a failed call nor its replay twice: once, when the call settles" in {
    val runtime  = GraphRuntime.inMemory()
    val children = new AtomicInteger(0)
    val client = Streaming(
      RoutingClient {
        case Lead(messages) if toolResults(messages).isEmpty =>
          Right(calling(call("c1", "task", ujson.Obj("subagent_type" -> "general", "description" -> "x"))))
        case Lead(_) => Right(CompletionFixture.simple("composed"))
        case Child("general", _) =>
          children.incrementAndGet()
          Right(CompletionFixture.simple("report"))
      },
      chunks = 2
    )
    // the parent's task fails after its call ran the child to its end, as a crash before the commit would
    val agent = lead(client)
      .withRuntime(runtime)
      .withMiddleware(crashingOnce("task"))
      .withSubagents(general.withStreaming())
      .build()
      .value
    agent.run(ThreadId("t1"), "go").isLeft shouldBe true
    taskEnds(runtime) shouldBe empty

    // the replay reattaches to the settled child: no run, so its live events are gone - one gap for it
    val seen = new CopyOnWriteArrayList[StreamEvent]()
    agent.streamRecover(ThreadId("t1"), nested = true)(seen.add(_): Unit).value.await().value
    val events = seen.asScala.toVector
    children.get shouldBe 1
    val started = events.indexWhere(SubagentEvents.TaskStarted.unapply(_).isDefined)
    val gap     = events.indexWhere { case StreamEvent.Nested(_, StreamEvent.LiveGap(0)) => true; case _ => false }
    val ended   = events.indexWhere(SubagentEvents.TaskEnded.unapply(_).isDefined)
    started should be >= 0
    started should be < gap
    gap should be < ended
    events.collect { case StreamEvent.Nested(_, e) => e } shouldBe Vector(StreamEvent.LiveGap(0))
    taskEnds(runtime).loneElement.status shouldBe "completed"
  }

  /** The run of the only child's latest checkpoint. */
  private def runtimeChildRun(agent: Agent): RunId =
    RunId(agent.runtime.history(agent.children(ThreadId("t1")).value.loneElement.threadId, None, 1).value.head.runId)

  private def taskEnds(runtime: GraphRuntime): Vector[org.llm4s.agent.events.TaskEnded] =
    runtime
      .storedEvents(ThreadId("t1"), 0L, 1000)
      .value
      .flatMap(r => SubagentEvents.TaskEnded.unapply(StreamEvent.Durable(r)))

  it should "not be committed by a call whose child suspended, only by the resumed call that settles it" in {
    import DelegatedSuspensionSpec.{ approvingClient, asking, probe, suspendedOf }
    val runtime = GraphRuntime.inMemory()
    val agent =
      lead(approvingClient()).withRuntime(runtime).withSubagents(asking(probe(new AtomicInteger(0)))).build().value
    val first = agent.run(ThreadId("t1"), "go").value
    taskEnds(runtime) shouldBe empty

    val id = suspendedOf(first).delegated.loneElement._1
    agent.resume(ThreadId("t1"), Map(first.approve(id))).value
    val ended = taskEnds(runtime).loneElement
    ended.status shouldBe "completed"
    ended.child.threadId shouldBe agent.children(ThreadId("t1")).value.loneElement.threadId
  }

  "a call reattaching to a Running child an earlier attempt left" should "give one gap for that attempt, then this run's events" in {
    val childCalls = new AtomicInteger(0)
    val client = RoutingClient {
      case Lead(messages) if toolResults(messages).isEmpty =>
        Right(calling(call("c1", "task_researcher", ujson.Obj("topic" -> "a"))))
      case Lead(messages) => Right(CompletionFixture.simple(toolResults(messages).mkString))
      case Child("researcher", _) =>
        childCalls.incrementAndGet() match
          case 1 => Right(calling(ToolCall("k1", "probe", ujson.Obj("text" -> "x"))))
          case 2 => Left(ProcessingError("provider", "the provider is down"))
          case _ => Right(CompletionFixture.simple("""{"summary":"recovered","sources":1}"""))
    }
    val probe = AgentTool(SpecText.spec("probe"))((_, _) => ToolOutcome.Success(ToolContent.Json(ujson.Str("probed"))))
    val agent = lead(client)
      .withSubagents(researcher.withTools(ToolSet.of(probe).value).withFailure(ChildFailure.Fail))
      .build()
      .value
    agent.run(ThreadId("t1"), "go").isLeft shouldBe true
    val earlier = runtimeChildRun(agent)

    val seen = new CopyOnWriteArrayList[StreamEvent]()
    agent.streamRecover(ThreadId("t1"), nested = true)(seen.add(_): Unit).value.await().value
    val events = seen.asScala.toVector
    val nested = events.collect { case n: StreamEvent.Nested => n }
    nested.head shouldBe StreamEvent.Nested(nested.head.child, StreamEvent.LiveGap(0))
    nested.head.child.runId shouldBe earlier
    // then the run this call drives, announced, from its claim
    val later = nested.tail.map(_.child.runId).distinct.loneElement
    later should not be earlier
    nested.tail.head.event match
      case StreamEvent.Durable(r) => r.event shouldBe a[RunEvent.RunRecovered]
      case other                  => fail(s"expected the recovered run's claim, got $other")
    events.flatMap(SubagentEvents.TaskStarted.unapply).map(_.child.runId) shouldBe Vector(earlier, later)
  }

  "a call resuming a suspended child" should "give one gap for the run that suspended it, then the resumed run's events" in {
    import DelegatedSuspensionSpec.{ approvingClient, asking, probe, suspendedOf }
    val agent   = lead(approvingClient()).withSubagents(asking(probe(new AtomicInteger(0)))).build().value
    val first   = agent.run(ThreadId("t1"), "go").value
    val id      = suspendedOf(first).delegated.loneElement._1
    val earlier = runtimeChildRun(agent)

    val seen = new CopyOnWriteArrayList[StreamEvent]()
    agent.streamResume(ThreadId("t1"), Map(first.approve(id)), nested = true)(seen.add(_): Unit).value.await().value
    val nested = seen.asScala.toVector.collect { case n: StreamEvent.Nested => n }
    nested.head shouldBe StreamEvent.Nested(nested.head.child, StreamEvent.LiveGap(0))
    nested.head.child.runId shouldBe earlier
    nested.tail should not be empty
    nested.tail.map(_.child.runId).distinct.loneElement should not be earlier
  }

  "a subagent of a handoff target" should "trace to the root's tracing, naming its parent" in {
    val tracing     = new Recording
    val targetCalls = new AtomicInteger(0)
    val client = RoutingClient {
      case Lead(_) => Right(calling(call("h1", "handoff_to_target", ujson.Obj())))
      case messages if messages.headOption.exists(_.content.startsWith("TARGET")) =>
        if targetCalls.incrementAndGet() == 1 then
          Right(calling(call("c1", "task", ujson.Obj("subagent_type" -> "general", "description" -> "x"))))
        else Right(CompletionFixture.simple("done"))
      case Child("general", _) => Right(CompletionFixture.simple("report"))
    }
    val target = Agent.builder("target", client).withSystemPrompt("TARGET").withSubagents(general)
    val agent = lead(client)
      .withTracing(tracing)
      .withHandoffs(org.llm4s.agent.Handoff.to("target", target, "the target does it"))
      .build()
      .value
    agent.run(ThreadId("t1"), "go").value
    val child = tracing.events.collect { case e: TraceEvent.AgentRunEnded if e.agent == "general" => e }.loneElement
    child.parentThreadId shouldBe Some("t1")
  }

  "subscribeChild" should "replay the child's own log after the run, and refuse another tenant" in {
    val tenant = RunConfig().withTenantId(TenantId("acme"))
    val agent  = lead(delegatingClient("hello")).withSubagents(general).build().value
    agent.run(ThreadId("t1"), "go", tenant).value
    val child = agent.children(ThreadId("t1"), tenant).value.loneElement
    val ref   = ChildRef(child.threadId, RunId("any"), child.origin.agent, Some(ToolCallId("c1")), 1)

    val seen = new ConcurrentLinkedQueue[StreamEvent]()
    val done = new CountDownLatch(1)
    val subscription = agent
      .subscribeChild(ref, config = tenant) { event =>
        seen.add(event)
        event match
          case StreamEvent.Durable(r) if r.event == RunEvent.RunCompleted => done.countDown()
          case _                                                          => ()
      }
      .value
    done.await(10, TimeUnit.SECONDS) shouldBe true
    subscription.cancel()
    val durable = seen.asScala.toVector.collect { case StreamEvent.Durable(r) => r }
    durable.head.event shouldBe a[RunEvent.RunStarted]
    durable.map(_.threadId).distinct.loneElement shouldBe child.threadId.value

    agent.subscribeChild(ref)(_ => ()).left.value shouldBe a[GraphError.TenantMismatch]
  }

  "a merged stream's subscriber that falls behind" should "lose nested events as a LiveGap on its own subscription (rule 12)" in {
    val chunks = Agent.StreamCapacity * 3
    val client = delegatingClient("x" * chunks, chunks)
    val agent  = lead(client).withSubagents(general.withStreaming()).build().value
    val held   = new CountDownLatch(1)
    val seen   = new CopyOnWriteArrayList[StreamEvent]()
    val run = agent
      .stream(ThreadId("t1"), "go", nested = true) { event =>
        seen.add(event)
        held.await(30, TimeUnit.SECONDS): Unit
      }
      .value
    // the turn finishes while its subscriber is held at its first event
    eventually(client.inner.sentTo(LeadPrompt).size == 2)
    held.countDown()
    run.await().value
    val events = seen.asScala.toVector
    events.collect { case StreamEvent.LiveGap(n) => n }.sum should be > 0
    events.flatMap(SubagentEvents.TaskEnded.unapply) should have size 1
  }

  "a child another runtime holds" should "give the merged stream one gap for it" in {
    val open    = new InMemoryCheckpointer()
    val claims  = ClaimPolicy(ttl = 300.millis, renewEvery = 100.millis)
    val entered = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val mode    = new AtomicInteger(0)
    val gate = SpecTools.tool("gate") { (_, _) =>
      mode.get match
        case 0 => ToolOutcome.Fatal(ProcessingError("gate", "shut"))
        case _ =>
          entered.countDown()
          release.await(30, TimeUnit.SECONDS)
          ToolOutcome.Success(ToolContent.Json(ujson.Str("open")))
    }
    def family(runtime: GraphRuntime): (Agent, Agent) =
      val child = Agent
        .builder(
          "worker",
          ScriptedLLMClient.of(SpecTools.calling(SpecTools.call("k1", "gate")), CompletionFixture.simple("r"))
        )
        .withRuntime(runtime)
        .withTools(SpecTools.set(gate))
        .build()
        .value
      val delegate = SpecTools.tool("delegate") { (args, ctx) =>
        val origin = RunOrigin(ctx.run.position.threadId, ctx.idempotencyKey, child.id, Instant.now(), 1)
        ChildRunner.run(
          child,
          origin,
          AgentInput(args.text, Vector.empty),
          RunConfig(),
          ctx,
          settleOnFailure = false
        ) match
          case Right(ChildEnd.Ended(_, _))      => ToolOutcome.Success(ToolContent.Json(ujson.Str("done")))
          case Right(ChildEnd.Failed(error, _)) => ToolOutcome.Fatal(error)
          case Left(error)                      => ToolOutcome.Fatal(error)
      }
      val parent = Agent
        .builder(
          "lead",
          ScriptedLLMClient.of(SpecTools.calling(SpecTools.call("c1", "delegate")), CompletionFixture.simple("ok"))
        )
        .withRuntime(runtime)
        .withTools(SpecTools.set(delegate))
        .build()
        .value
      (parent, child)

    val thread = ThreadId("held-elsewhere")
    val here   = new GraphRuntime(open, claims = claims)
    family(here)._1.run(thread, "go").isLeft shouldBe true
    val childId = here.children(thread).value.loneElement.threadId

    mode.set(1)
    val elsewhere = new GraphRuntime(open, claims = claims)
    val held      = elsewhere.recoverChild(childId, family(elsewhere)._2.loop.graph, RunConfig()).value
    entered.await(10, TimeUnit.SECONDS) shouldBe true

    val seen = new CopyOnWriteArrayList[StreamEvent]()
    family(here)._1.streamRecover(thread, nested = true)(seen.add(_): Unit).value.await().isLeft shouldBe true
    release.countDown()
    held.await().value

    val events = seen.asScala.toVector
    val nested = events.collect { case n: StreamEvent.Nested => n }
    nested.map(_.event) shouldBe Vector(StreamEvent.LiveGap(0))
    nested.head.child.threadId shouldBe childId
    // the gap follows a TaskStarted naming the same run: the holder's
    val gapAt   = events.indexOf(nested.head)
    val startAt = events.lastIndexWhere(SubagentEvents.TaskStarted.unapply(_).isDefined, gapAt)
    startAt should be >= 0
    SubagentEvents.TaskStarted.unapply(events(startAt)).value.child shouldBe nested.head.child
  }

  "a child's AgentRunEnded" should "name its parent thread and run; a top-level run's names none" in {
    val tracing = new Recording
    val agent = lead(delegatingClient("hello"))
      .withTracing(tracing)
      .withSubagents(general)
      .build()
      .value
    val run = agent.start(ThreadId("t1"), "go").value
    run.await().value

    val ends   = tracing.events.collect { case e: TraceEvent.AgentRunEnded => e }
    val parent = ends.find(_.threadId == "t1").value
    val child  = ends.find(_.threadId != "t1").value
    parent.parentThreadId shouldBe None
    parent.parentRunId shouldBe None
    child.agent shouldBe "general"
    child.parentThreadId shouldBe Some("t1")
    child.parentRunId shouldBe Some(run.runId.value)
    // the child-task metric is traced under its own name
    tracing.events.collect { case c: TraceEvent.CustomEvent if c.name == "agent.task_ended" => c } should have size 1
  }

  private def eventually(condition: => Boolean): Unit =
    val deadline = System.nanoTime() + 10.seconds.toNanos
    while !condition && System.nanoTime() < deadline do Thread.sleep(5)
    condition shouldBe true
}

object NestedStreamSpec {
  import DelegationSpec.*

  /** `inner`, streaming each answer's text in `chunks` pieces through `streamComplete`. */
  final class Streaming(val inner: RoutingClient, chunks: Int) extends LLMClient {
    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
      inner.complete(conversation, options)

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] =
      inner.complete(conversation, options).map { completion =>
        val text = completion.content
        if text.nonEmpty then
          val size = math.max(1, math.ceil(text.length.toDouble / chunks).toInt)
          text.grouped(size).foreach(piece => onChunk(StreamedChunk("c", Some(piece))))
        completion
      }

    override def getContextWindow(): Int     = 128000
    override def getReserveCompletion(): Int = 4096
  }

  /** A middleware that fails the task of the first call of `tool` once the call has run, then lets every call by. */
  def crashingOnce(tool: String): AgentMiddleware = new AgentMiddleware {
    private val crashed  = new java.util.concurrent.atomic.AtomicBoolean(false)
    val id: MiddlewareId = MiddlewareId("crash")
    override def wrapToolCall(request: ToolCallRequest, context: ToolContext)(next: () => ToolOutcome): ToolOutcome =
      val outcome = next()
      if request.call.name == tool && crashed.compareAndSet(false, true) then
        ToolOutcome.Fatal(ProcessingError("crash", "after the call"))
      else outcome
  }

  /** Records every trace event. */
  final class Recording extends Tracing {
    private val recorded                            = new ConcurrentLinkedQueue[TraceEvent]()
    def events: Vector[TraceEvent]                  = recorded.asScala.toVector
    def traceEvent(event: TraceEvent): Result[Unit] = { recorded.add(event); Right(()) }
    def traceToolCall(toolName: String, input: String, output: String): Result[Unit]       = Right(())
    def traceError(error: Throwable, context: String): Result[Unit]                        = Right(())
    def traceCompletion(completion: Completion, model: String): Result[Unit]               = Right(())
    def traceTokenUsage(usage: TokenUsage, model: String, operation: String): Result[Unit] = Right(())
  }
}
