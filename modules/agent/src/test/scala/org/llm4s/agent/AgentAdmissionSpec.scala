package org.llm4s.agent

import org.llm4s.types.ProviderModelTypes.ModelName
import org.llm4s.agent.AgentFixture._
import org.llm4s.agent.graph._
import org.llm4s.agent.graph.GraphTestSupport.awaited
import org.llm4s.agent.graph.middleware._
import org.llm4s.agent.graph.toolloop.{ Admission, AgentInput }
import org.llm4s.agent.guardrails.InputGuardrail
import org.llm4s.agent.service._
import org.llm4s.error.{ LLMError, ProcessingError, ValidationError }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.testutil.TestTimeLimit
import org.llm4s.types.Result
import org.scalatest.{ BeforeAndAfterEach, EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.{ ConcurrentLinkedQueue, CopyOnWriteArrayList }
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

/**
 * The admission stage (#2854): `AgentMiddleware.admit` runs before anything of a turn is stored, may rewrite the
 * query or refuse it, and nothing else; input guardrails run there. `GuardedContentContract` holds every store to
 * what this leaves in it; this spec covers the stage itself - what each result does, when it runs and how often,
 * what it may not do, what it counts, and the paths that store a turn before the agent sees it.
 */
class AgentAdmissionSpec
    extends AnyFlatSpec
    with TestTimeLimit
    with Matchers
    with EitherValues
    with OptionValues
    with BeforeAndAfterEach {

  private val Secret = "SECRET-42"

  /** Every commit a store is given, as text, so that a row written and later retracted is seen too. */
  final private class Seen(to: Checkpointer) extends ForwardingCheckpointer(to) {
    val commits = new ConcurrentLinkedQueue[String]()
    override def commit(threadId: ThreadId, commit: Commit): Result[Vector[EventRecord]] = {
      commits.add(commit.toString)
      to.commit(threadId, commit)
    }
    def all: String = commits.asScala.mkString("\n")
  }

  /** Logs each stage and hook it runs with the text it was given, and does what `onAdmit` says with a query. */
  final private class Staged(
    name: String,
    log: CopyOnWriteArrayList[String],
    onAdmit: String => Result[String] = Right(_)
  ) extends AgentMiddleware {
    val id: MiddlewareId = MiddlewareId(name)
    override def admit(query: String, context: AdmissionContext): Result[String] = {
      log.add(s"$name:admit:$query")
      onAdmit(query)
    }
    override def beforeAgent(input: String, context: ModelContext): Result[BoundaryResult] = {
      log.add(s"$name:before:$input")
      Right(BoundaryResult(input))
    }
  }

  /** Blocks every query from `admit`, as itself. */
  final private class Refusing(reason: String) extends AgentMiddleware {
    val id: MiddlewareId                                                         = MiddlewareId("refusing")
    override def admit(query: String, context: AdmissionContext): Result[String] = block(reason)
  }

  /** Asks its question from `admit`, which admission cannot take. */
  final private class AsksAtAdmission extends AgentMiddleware.Asking[String, String] {
    val id: MiddlewareId                                                         = MiddlewareId("asks")
    override def admit(query: String, context: AdmissionContext): Result[String] = ask("may I?")
  }

  private def redacting(log: CopyOnWriteArrayList[String] = new CopyOnWriteArrayList[String]()) =
    new Staged("redact", log, q => Right(q.replace(Secret, "[redacted]")))

  private def answers(texts: String*): ScriptedLLMClient = ScriptedLLMClient.of(texts.map(CompletionFixture.simple)*)

  private def over(store: Checkpointer, client: LLMClient, id: String = "assistant"): AgentBuilder =
    Agent.builder(id, client).withRuntime(new GraphRuntime(store))

  private def thread(): ThreadId = ThreadId(s"admit-${java.util.UUID.randomUUID().toString.take(8)}")

  private def sent(client: ScriptedLLMClient): String = client.sent.flatten.map(_.content).mkString("\n")

  private def eventNames(store: Checkpointer, on: ThreadId): Vector[String] =
    store.eventsAfter(on, 0L, 1000).value.map(_.event).collect { case RunEvent.Custom(name, _, _) => name }

  private val started = new ConcurrentLinkedQueue[AgentWorker]()

  override def afterEach(): Unit = {
    started.asScala.foreach(_.shutdown())
    started.clear()
  }

  // ---- what each result does ----

  "admit" should "rewrite the query: the store, the model and every beforeAgent hold only the rewrite" in {
    val store  = new Seen(new InMemoryCheckpointer())
    val log    = new CopyOnWriteArrayList[String]()
    val client = answers("noted")
    val agent  = built(over(store, client).withMiddleware(new Staged("later", log), redacting(log)))
    val on     = thread()

    val result = agent.run(on, s"my card is $Secret").value
    result.answer.value shouldBe "noted"
    result.messages.head.content shouldBe "my card is [redacted]"
    sent(client) should include("[redacted]")
    (sent(client) should not).include(Secret)
    (store.all should not).include(Secret)
    // every admit, in stack order, before any beforeAgent - which sees the query as admitted
    log.asScala.toList shouldBe List(
      s"later:admit:my card is $Secret",
      s"redact:admit:my card is $Secret",
      "later:before:my card is [redacted]",
      "redact:before:my card is [redacted]"
    )
  }

  it should "refuse the query with block: Blocked with the reason, no model call, and no query in any commit" in {
    val store  = new Seen(new InMemoryCheckpointer())
    val client = answers("never sent")
    val agent  = built(over(store, client).withMiddleware(new Refusing("not on a weekend")))
    val on     = thread()

    val result = agent.run(on, s"my card is $Secret").value
    result.status shouldBe AgentStatus.Blocked("refusing", Some("not on a weekend"))
    result.messages shouldBe empty
    client.callCount shouldBe 0
    (store.all should not).include(Secret)
    (store.all should not).include("not on a weekend")

    // the blocked run is on record, by name: its event, its failure and its closing checkpoint
    eventNames(store, on) should contain("agent.guardrail_blocked")
    val closing = store.latest(on).value.value.checkpoint
    closing.status shouldBe CheckpointStatus.Failed
    closing.outcome shouldBe Some(RunOutcome.Blocked("refusing"))
    // and the thread takes its next turn
    val next = built(over(store, answers("hello")).withMiddleware(new Staged("s", new CopyOnWriteArrayList[String]())))
    next.run(on, "hi").value.answer.value shouldBe "hello"
  }

  it should "refuse to start the turn on any other Left: nothing is stored and the thread is unchanged" in {
    val store   = new Seen(new InMemoryCheckpointer())
    val refused = ValidationError("policy", "the policy service is down")
    val failing = new Staged("failing", new CopyOnWriteArrayList[String](), _ => Left(refused))
    val agent   = built(over(store, answers("never sent")).withMiddleware(failing))
    val on      = thread()

    agent.run(on, s"my card is $Secret").error shouldBe refused
    store.commits shouldBe empty
    store.latest(on).value shouldBe None
    agent.start(on, s"my card is $Secret").left.value shouldBe refused
  }

  it should "refuse a blank rewrite, a question and a throw before anything is stored" in {
    def refusal(middleware: AgentMiddleware): (LLMError, Seen) = {
      val store = new Seen(new InMemoryCheckpointer())
      val agent = built(over(store, answers("never sent")).withMiddleware(middleware))
      (agent.run(thread(), "hello").error, store)
    }
    val (blank, afterBlank) = refusal(new Staged("blanking", new CopyOnWriteArrayList[String](), _ => Right("  ")))
    blank shouldBe a[ValidationError]
    blank.message should include("blank")
    afterBlank.commits shouldBe empty

    val (asked, afterAsk) = refusal(new AsksAtAdmission)
    asked shouldBe a[ValidationError]
    asked.message should include("'asks'")
    asked.message should include("cannot suspend")
    afterAsk.commits shouldBe empty

    val throwing =
      new Staged("throwing", new CopyOnWriteArrayList[String](), _ => throw new IllegalStateException("boom"))
    val (thrown, afterThrow) = refusal(throwing)
    thrown shouldBe a[GraphError.MiddlewareFailed]
    afterThrow.commits shouldBe empty
  }

  // ---- when it runs, and how often ----

  it should "run once for a turn: a recover runs beforeAgent again, never admit" in {
    val store  = new InMemoryCheckpointer()
    val log    = new CopyOnWriteArrayList[String]()
    val client = new ScriptedLLMClient(Left(ProcessingError("provider", "down")), Right(CompletionFixture.simple("ok")))
    // the input step fails once, after its hooks ran, so that recover runs the step again
    val flaky = new AgentMiddleware {
      val id: MiddlewareId = MiddlewareId("flaky")
      private val failed   = new AtomicInteger(0)
      override def beforeAgent(input: String, context: ModelContext): Result[BoundaryResult] =
        if (failed.getAndIncrement() == 0) throw new InterruptedException("stopped") else Right(BoundaryResult(input))
    }
    val agent = built(over(store, client).withMiddleware(redacting(log), flaky))
    val on    = thread()

    agent.run(on, s"my card is $Secret").isLeft shouldBe true
    Thread.interrupted()                   // the cancellation the hook threw set the flag
    agent.recover(on).isLeft shouldBe true // the provider is down
    agent.recover(on).value.answer.value shouldBe "ok"

    log.asScala.count(_.startsWith("redact:admit")) shouldBe 1
    log.asScala.count(_.startsWith("redact:before")) should be > 1
    log.asScala.filter(_.startsWith("redact:before")).toSet shouldBe Set("redact:before:my card is [redacted]")
  }

  it should "run the active handoff target's stage before the turn is stored, after the root's" in {
    val store = new Seen(new InMemoryCheckpointer())
    val log   = new CopyOnWriteArrayList[String]()
    val handoff = CompletionFixture.withMessage(
      AssistantMessage(
        None,
        Seq(ToolCall(ToolCallId("call_h"), "handoff_to_specialist", ujson.Obj("reason" -> "specialist")))
      )
    )
    val specialist = Agent
      .builder("specialist", answers("specialist answer", "never sent"))
      .withMiddleware(
        new Staged("target", log, q => if (q.contains(Secret)) Left(ValidationError("t", "no")) else Right(q))
      )
    val agent = built(
      over(store, ScriptedLLMClient.of(handoff), "triage")
        .withMiddleware(new Staged("root", log))
        .withHandoffs(Handoff.to("specialist", specialist))
    )
    val on = thread()

    agent.run(on, "yes please").value.activeAgent.value shouldBe "specialist"
    log.clear()
    val before = store.commits.size
    // the target's stage refuses to start the turn: its Left is the caller's, and nothing is stored
    agent.run(on, s"my card is $Secret").isLeft shouldBe true
    log.asScala.toList shouldBe List(s"root:admit:my card is $Secret", s"target:admit:my card is $Secret")
    store.commits.size shouldBe before
    (store.all should not).include(Secret)
  }

  // ---- input guardrails ----

  "An input guardrail" should "see the query as submitted, before a beforeAgent hook changes it" in {
    val seen = new CopyOnWriteArrayList[String]()
    val spy: InputGuardrail = new InputGuardrail {
      val name: String                            = "Spy"
      def validate(value: String): Result[String] = { seen.add(value); Right(value) }
    }
    val shouting = new AgentMiddleware {
      val id: MiddlewareId = MiddlewareId("shouting")
      override def beforeAgent(input: String, context: ModelContext): Result[BoundaryResult] =
        Right(BoundaryResult(input.toUpperCase))
    }
    val client = answers("ok")
    val agent = built(
      over(new InMemoryCheckpointer(), client).withMiddleware(shouting).withInputGuardrails(spy)
    )
    agent.run(thread(), "hello").value.messages.head.content shouldBe "HELLO"
    seen.asScala.toList shouldBe List("hello")
  }

  it should "have a judge's model calls counted in the thread's usage and announced, once" in {
    val judge = ScriptedLLMClient.of(CompletionFixture.withUsage("fine", prompt = 7, completion = 3))
    val judging: InputGuardrail = new InputGuardrail {
      val name: String                            = "Judge"
      def validate(value: String): Result[String] = Right(value)
      override def validateIn(value: String, context: UsageAccounting): Result[String] =
        context.accounted(judge).complete(Conversation(Seq(UserMessage(value))), CompletionOptions()).map(_ => value)
    }
    val store = new InMemoryCheckpointer()
    val client = new ScriptedLLMClient(
      Left(ProcessingError("provider", "down")),
      Right(CompletionFixture.withUsage("ok", prompt = 10, completion = 5))
    )
    val agent = built(over(store, client).withInputGuardrails(judging))
    val on    = thread()

    agent.run(on, "hello").isLeft shouldBe true
    val result = agent.recover(on).value
    judge.callCount shouldBe 1
    // the judge's call and the agent's own, each once, across the failed run and its recover
    result.usage.requestCount shouldBe 2
    result.usage.inputTokens shouldBe 17
    result.usage.outputTokens shouldBe 8
    eventNames(store, on).count(_ == "agent.model_call_completed") shouldBe 2
  }

  it should "have a judge's calls counted when it refuses the query" in {
    val judge = ScriptedLLMClient.of(CompletionFixture.withUsage("unsafe", prompt = 7, completion = 3))
    val judging: InputGuardrail = new InputGuardrail {
      val name: String                            = "Judge"
      def validate(value: String): Result[String] = Right(value)
      override def validateIn(value: String, context: UsageAccounting): Result[String] =
        context
          .accounted(judge)
          .complete(Conversation(Seq(UserMessage(value))), CompletionOptions())
          .flatMap(_ => Left(ValidationError.invalid("input", "judged unsafe")))
    }
    val store  = new Seen(new InMemoryCheckpointer())
    val agent  = built(over(store, answers("never sent")).withInputGuardrails(judging))
    val on     = thread()
    val result = agent.run(on, s"my card is $Secret").value

    result.status should matchPattern { case AgentStatus.Blocked("Judge", Some(_)) => }
    result.usage.requestCount shouldBe 1
    result.usage.inputTokens shouldBe 7
    eventNames(store, on) should contain("agent.model_call_completed")
    (store.all should not).include(Secret)
  }

  // ---- a turn stored before the agent saw it ----

  "A turn started with an input nothing admitted" should "be admitted by its input step, with the same verdict" in {
    val store = new InMemoryCheckpointer()
    val log   = new CopyOnWriteArrayList[String]()
    val agent = built(over(store, answers("noted", "noted again")).withMiddleware(redacting(log)))
    val on    = thread()
    // as a delegated child's runner or an old queue entry starts it: the runtime is given the input as it is
    val result = agent.runtime.start(on, agent.loop.graph, AgentInput(s"my card is $Secret")).awaited.value
    result shouldBe a[RunResult.Completed[?]]
    log.asScala.toList shouldBe List(s"redact:admit:my card is $Secret", "redact:before:my card is [redacted]")
    agent.run(on, "and again").value.messages.head.content shouldBe "my card is [redacted]"

    val refusing = built(over(store, answers("never sent"), "strict").withMiddleware(new Refusing("no")))
    val other    = thread()
    refusing.runtime.start(other, refusing.loop.graph, AgentInput("hello")).awaited.value match {
      case RunResult.Failed(_, error) => error shouldBe GuardrailBlocked("refusing", Some("no"))
      case unexpected                 => fail(s"expected a block, got $unexpected")
    }
    store.latest(other).value.value.checkpoint.outcome shouldBe Some(RunOutcome.Blocked("refusing"))
  }

  "AgentInput" should "read an input stored before admission existed, and round-trip its admission record" in {
    val old = ujson.Obj("query" -> "hello", "history" -> ujson.Arr())
    upickle.default.read[AgentInput](old) shouldBe AgentInput("hello")
    AgentInput("hello").admission shouldBe None

    val admitted = AgentInput("hello").withAdmission(
      Admission(Vector(AgentId.unsafe("assistant")), UsageSummary().add(ModelName("m"), TokenUsage(1, 2, 3), None))
        .withRefusedBy("NoCards")
    )
    upickle.default.read[AgentInput](upickle.default.writeJs(admitted)) shouldBe admitted
  }

  // ---- the run queue ----

  private def workerOf(store: InMemoryCheckpointer, agent: Agent): AgentWorker = {
    val settings = WorkerSettings(pollInterval = 20.millis, sweepEvery = None, drainGrace = 2.seconds)
    val queue    = RunQueueSettings(lease = LeasePolicy(ttl = 600.millis, renewEvery = 150.millis))
    val worker   = AgentWorker(store.runQueue, store, Seq(QueuedAgent(agent)), settings, queue).value
    started.add(worker)
    worker
  }

  private def submit(store: InMemoryCheckpointer, on: ThreadId, input: QueuedInput): EntryId =
    store.runQueue
      .submit(Submission(on, AgentId.unsafe("assistant"), input, policy = BusyThreadPolicy.Enqueue))
      .value
      .entryId

  "Agent.admit" should "give a queue a refused query's refusal and nothing of the query" in {
    val raw    = new InMemoryCheckpointer()
    val store  = new Seen(raw)
    val checks = new AtomicInteger(0)
    val noCards: InputGuardrail = new InputGuardrail {
      val name: String = "NoCards"
      def validate(value: String): Result[String] = {
        checks.incrementAndGet()
        if (value.contains(Secret)) Left(ValidationError.invalid("input", "holds a card number")) else Right(value)
      }
    }
    val agent = built(over(store, answers("never sent")).withInputGuardrails(noCards))
    val on    = thread()

    val admitted = agent.admit(on, s"my card is $Secret").value
    admitted should matchPattern { case AdmittedQuery.Refused(_, "NoCards", reason) if reason.contains("card") => }
    admitted.input.query shouldBe ""
    store.commits shouldBe empty

    val id = submit(raw, on, QueuedInput.admitted(admitted))
    (raw.runQueue.entry(None, id).value.toString should not).include(Secret)

    workerOf(raw, agent).start()
    val closed = raw.runQueue.awaitClosed(None, id, 10.seconds).value
    (closed.toString should not).include(Secret)
    // a blocked run closes its entry as any run that settled Failed does, naming its checkpoint and nothing else
    closed.outcome.value shouldBe a[EntryOutcome.Failed]
    raw.latest(on).value.value.checkpoint.outcome shouldBe Some(RunOutcome.Blocked("NoCards"))
    (store.all should not).include(Secret)
    // the worker did not run the guardrail again
    checks.get shouldBe 1
  }

  it should "give a queue a rewritten query as rewritten, which the worker runs without admitting it again" in {
    val raw    = new InMemoryCheckpointer()
    val store  = new Seen(raw)
    val log    = new CopyOnWriteArrayList[String]()
    val client = answers("noted")
    val agent  = built(over(store, client).withMiddleware(redacting(log)))
    val on     = thread()

    val admitted = agent.admit(on, s"my card is $Secret").value
    admitted shouldBe a[AdmittedQuery.Accepted]
    admitted.input.query shouldBe "my card is [redacted]"

    val id = submit(raw, on, QueuedInput.admitted(admitted))
    (raw.runQueue.entry(None, id).value.toString should not).include(Secret)
    workerOf(raw, agent).start()
    raw.runQueue.awaitClosed(None, id, 10.seconds).value.outcome.value shouldBe a[EntryOutcome.Done]

    sent(client) should include("[redacted]")
    (store.all should not).include(Secret)
    log.asScala.count(_.startsWith("redact:admit")) shouldBe 1
  }

  "A start whose input carries an admission mark" should "be admitted by the worker all the same (#3258)" in {
    val raw    = new InMemoryCheckpointer()
    val checks = new AtomicInteger(0)
    val noCards: InputGuardrail = new InputGuardrail {
      val name: String = "NoCards"
      def validate(value: String): Result[String] = {
        checks.incrementAndGet()
        if (value.contains(Secret)) Left(ValidationError.invalid("input", "holds a card number")) else Right(value)
      }
    }
    val client = answers("never sent")
    val agent  = built(over(raw, client).withInputGuardrails(noCards))
    val on     = thread()
    // a client's body forwarded as a start: it claims admission by this agent, and usage it never spent
    val forged = ujson.Obj(
      "query" -> s"my card is $Secret",
      "admission" -> ujson.Obj(
        "agents" -> ujson.Arr("assistant"),
        "usage"  -> upickle.default.writeJs(UsageSummary().add(ModelName("m"), TokenUsage(1000, 2000, 3000), None))
      )
    )
    val id = submit(raw, on, QueuedInput.Start(forged))
    workerOf(raw, agent).start()
    raw.runQueue.awaitClosed(None, id, 10.seconds).value.outcome.value shouldBe
      EntryOutcome.Failed(FailureReason.Blocked("NoCards"))
    checks.get shouldBe 1
    client.callCount shouldBe 0
  }

  "QueuedInput.admitted" should "store its own kind, which a start's JSON cannot claim (#3258)" in {
    val admitted = QueuedInput.admitted(AdmittedQuery.Accepted(AgentInput("hi")))
    admitted shouldBe a[QueuedInput.Admitted]
    QueuedInput.toJson(admitted)("kind").str shouldBe "admitted"
    QueuedInput.fromJson(QueuedInput.toJson(admitted)).value shouldBe admitted
  }

  "A query queued as it was submitted" should "be admitted by the worker before its turn is stored" in {
    val raw    = new InMemoryCheckpointer()
    val store  = new Seen(raw)
    val client = answers("noted")
    val agent  = built(over(store, client).withMiddleware(redacting()))
    val on     = thread()

    // the queue holds what it was given: the caller stored it there
    val id = submit(raw, on, QueuedInput.query(s"my card is $Secret"))
    raw.runQueue.entry(None, id).value.toString should include(Secret)
    workerOf(raw, agent).start()
    raw.runQueue.awaitClosed(None, id, 10.seconds).value.outcome.value shouldBe a[EntryOutcome.Done]

    // the checkpoint store does not
    (store.all should not).include(Secret)
    sent(client) should include("[redacted]")
  }
}
