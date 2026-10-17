package org.llm4s.agent.harness.memory

import org.llm4s.agent.{ Agent, AgentBuilder, AgentStatus, CompletionFixture, ScriptedLLMClient }
import org.llm4s.agent.AgentFixture.built
import org.llm4s.agent.graph.*
import org.llm4s.agent.graph.middleware.GuardrailMiddleware
import org.llm4s.agent.guardrails.builtin.LengthCheck
import org.llm4s.agent.memory.*
import org.llm4s.agent.testkit.ManualClock
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.*
import org.llm4s.types.Result
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.{ Clock, Duration as JDuration, Instant }
import java.util.concurrent.{ ConcurrentHashMap, CopyOnWriteArrayList }
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** Stage 3 slice F7 (#1911, design §4.31): semantic memory through `Agent`. */
class SemanticMemoryMiddlewareSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues {
  import MemoryTestSupport.*

  private val t1 = TenantId("t1")
  private val t2 = TenantId("t2")
  private val summarising = (store: MemoryStore, client: LLMClient) =>
    LLMMemoryManager(MemoryManagerConfig.default, store, client)
  private val later       = Clock.offset(Clock.systemUTC(), JDuration.ofDays(1))
  private val consolidate = Some(MemoryConsolidation(olderThan = 1.minute, minCount = 3))

  /** One fresh in-memory store per tenant, resolved once each. */
  private def perTenant(): (Option[TenantId] => Result[MemoryStore], ConcurrentHashMap[Option[TenantId], Int]) =
    val resolved = new ConcurrentHashMap[Option[TenantId], Int]()
    val resolver = (tenant: Option[TenantId]) =>
      resolved.merge(tenant, 1, _ + _)
      Right(InMemoryStore.empty)
    (resolver, resolved)

  private val simple: (MemoryStore, LLMClient) => MemoryManager = (store, _) => SimpleMemoryManager.withStore(store)

  private def answering(texts: String*): ScriptedLLMClient = ScriptedLLMClient.of(texts.map(CompletionFixture.simple)*)

  private def tenant(t: TenantId): RunConfig = RunConfig().withTenantId(t)

  private def systemPrompt(sent: Vector[Message]): String =
    sent.collectFirst { case s: SystemMessage => s.content }.getOrElse("")

  /** The store a middleware holds for `tenant`, as its last write left it. */
  private def held(m: SemanticMemoryMiddleware, tenant: Option[TenantId]): MemoryStore =
    m.withStore(tenant)(Right(_)).value

  private def agentWith(client: LLMClient, m: SemanticMemoryMiddleware, runtime: Option[GraphRuntime] = None): Agent =
    val builder = Agent.builder("assistant", client).withMiddleware(m)
    built(runtime.fold(builder)(builder.withRuntime))

  "Two threads of one tenant" should "share recall; a thread of another tenant recalls nothing of theirs" in {
    val (stores, resolved) = perTenant()
    val m                  = new SemanticMemoryMiddleware(stores, simple, answering())
    val client             = answering("Noted.", "It is teal.", "I do not know.")
    val agent              = agentWith(client, m)
    agent.run(ThreadId("a"), "My favourite colour is teal", tenant(t1)).value
    agent.run(ThreadId("b"), "What is my favourite colour?", tenant(t1)).value
    agent.run(ThreadId("c"), "What is my favourite colour?", tenant(t2)).value
    // the first model call of thread b - its turn's first - shows what thread a recorded
    systemPrompt(client.sent(1)) should include("My favourite colour is teal")
    (systemPrompt(client.sent(2)) should not).include("teal")
    resolved.asScala.toMap shouldBe Map(Some(t1) -> 1, Some(t2) -> 1)
    contents(held(m, Some(t2))) shouldBe Vector("I do not know.", "What is my favourite colour?")
  }

  "Recall" should "be shown to the turn's first model call, before any tool runs" in {
    val store = InMemoryStore.withMemories(Seq(Memory.userFact("The user's dog is called Rex"))).value
    val m     = new SemanticMemoryMiddleware(_ => Right(store), simple, answering(), record = None)
    val client = ScriptedLLMClient.of(
      CompletionFixture.withToolCall("missing_tool", ujson.Obj()),
      CompletionFixture.simple("Rex")
    )
    agentWith(client, m).run(ThreadId("first"), "What is my dog called?").value
    client.sent.head.collect { case s: SystemMessage => s.content }.mkString should include("dog is called Rex")
    client.sent(1).collect { case s: SystemMessage => s.content }.mkString should include("dog is called Rex")
  }

  it should "be cut to maxRecallTokens, in the key and in the section, and one turn's recall never shows in the next" in {
    // a manager that ignores the budget it is given: the middleware bounds what it keeps whatever the manager does
    val overlong = (store: MemoryStore, _: LLMClient) =>
      new Overlong(SimpleMemoryManager.withStore(store), q => Right(if q == "zebra" then "zebra " * 2000 else ""))
    val m = new SemanticMemoryMiddleware(
      _ => Right(InMemoryStore.empty),
      overlong,
      answering(),
      recall = Some(MemoryRecall(maxRecallTokens = 10)),
      record = None
    )
    val client     = answering("one", "two")
    val checkpoint = new InMemoryCheckpointer
    val peek       = new Peek
    val agent = built(Agent.builder("assistant", client).withMiddleware(m, peek).withRuntime(GraphRuntime(checkpoint)))
    agent.run(ThreadId("cut"), "zebra").value
    systemPrompt(client.sent.head) should include("zebra")
    peek.seen.asScala.toVector.map(_.length) shouldBe Vector(40)
    // the recall served its turn only: the thread at rest holds no entry for it
    checkpoint.latest(ThreadId("cut")).value.value.checkpoint.snapshot.state.keySet should not contain
      SemanticMemoryMiddleware.RecalledKey.id.value
    agent.run(ThreadId("cut"), "unrelated").value
    (systemPrompt(client.sent(1)) should not).include("zebra")
  }

  it should "not block the turn when the store's search fails, and say so with a durable event" in {
    val failing = new PlainStore(InMemoryStore.empty) {
      override def search(query: String, topK: Int, filter: MemoryFilter) =
        Left(org.llm4s.error.ProcessingError("search", "index down"))
    }
    val m      = new SemanticMemoryMiddleware(_ => Right(failing), simple, answering(), record = None)
    val seen   = new CopyOnWriteArrayList[StreamEvent]()
    val agent  = agentWith(answering("fine"), m)
    val result = agent.stream(ThreadId("skip"), "hello")(e => seen.add(e): Unit).value.await().value
    result.answer shouldBe Some("fine")
    seen.asScala.collect { case e @ MemoryEvents.Skipped(s) =>
      (e.isInstanceOf[StreamEvent.Durable], s)
    }.toVector shouldBe
      Vector(true -> MemorySkipped("memory.recall", "ProcessingError"))
  }

  "Recording" should "store each turn once, cut to maxRecordChars, and announce it with counts only" in {
    val (stores, _) = perTenant()
    val m =
      new SemanticMemoryMiddleware(stores, simple, answering(), record = Some(MemoryRecording(maxRecordChars = 5)))
    val seen  = new CopyOnWriteArrayList[StreamEvent]()
    val agent = agentWith(answering("a long answer"), m)
    agent.stream(ThreadId("rec"), "a long question")(e => seen.add(e): Unit).value.await().value
    contents(held(m, None)) shouldBe Vector("a lon", "a lon")
    seen.asScala.collect { case e @ MemoryEvents.Recorded(r) =>
      (e.isInstanceOf[StreamEvent.Durable], r)
    }.toVector shouldBe
      Vector(true -> MemoryRecorded(2, 0))
  }

  it should "keep the store within maxMemories with consolidation off, also under concurrent threads of a tenant" in {
    val bounds      = MemoryBounds(maxMemories = 5)
    val (stores, _) = perTenant()
    val m           = new SemanticMemoryMiddleware(stores, simple, answering(), bounds = bounds)
    val agent       = agentWith(answering((1 to 8).map(i => s"answer $i")*), m)
    val runs        = (1 to 8).map(i => agent.start(ThreadId(s"bound-$i"), s"question $i", tenant(t1)).value)
    runs.foreach(_.await().value)
    held(m, Some(t1)).count().value shouldBe 5L
  }

  it should "never read the store while another thread of the tenant writes it" in {
    // a store that is not thread-safe, as SQLiteMemoryStore is not: it fails any call that overlaps another
    val inside  = new java.util.concurrent.atomic.AtomicInteger()
    val overlap = new java.util.concurrent.atomic.AtomicBoolean(false)
    def exclusive[A](body: => A): A =
      if inside.incrementAndGet() > 1 then overlap.set(true)
      Thread.sleep(2)
      val result = body
      inside.decrementAndGet()
      result
    final class Exclusive(base: InMemoryStore) extends PlainStore(base):
      private def wrap(r: Result[MemoryStore])              = r.map(s => new Exclusive(s.asInstanceOf[InMemoryStore]))
      override def store(memory: Memory)                    = exclusive(wrap(base.store(memory)))
      override def delete(id: MemoryId)                     = exclusive(wrap(base.delete(id)))
      override def recall(filter: MemoryFilter, limit: Int) = exclusive(base.recall(filter, limit))
      override def search(query: String, topK: Int, filter: MemoryFilter) = exclusive(base.search(query, topK, filter))
    val m      = new SemanticMemoryMiddleware(_ => Right(new Exclusive(InMemoryStore.empty)), simple, answering())
    val client = new FunctionClient(_ => "answer")
    val agent  = agentWith(client, m)
    val runs   = (1 to 8).map(i => agent.start(ThreadId(s"x-$i"), s"question $i").value)
    runs.foreach(_.await().value)
    overlap.get shouldBe false
    held(m, None).count().value shouldBe 16L
  }

  it should "count every call the memory manager makes in the run's usage (rule 11)" in {
    val extraction =
      new FunctionClient(_ => """[{"entity_name": "Ada", "entity_type": "person", "fact": "Ada wrote programs"}]""")
    val extracting = (store: MemoryStore, client: LLMClient) =>
      LLMMemoryManager(MemoryManagerConfig.default.copy(autoExtractEntities = true), store, client)
    val m      = new SemanticMemoryMiddleware(_ => Right(InMemoryStore.empty), extracting, extraction)
    val result = agentWith(answering("Ada wrote programs."), m).run(ThreadId("usage"), "Who was Ada?").value
    extraction.calls.get should be > 0
    result.usage.requestCount shouldBe 1L + extraction.calls.get
    result.usage.byModel.keySet should contain("memory-model")
  }

  "A blocked answer" should "never be recorded when the memory middleware runs outside the guardrails" in {
    val (stores, _) = perTenant()
    val m           = new SemanticMemoryMiddleware(stores, simple, answering())
    val guard       = new GuardrailMiddleware(Nil, Seq(new LengthCheck(0, 3)))
    val agent       = built(Agent.builder("assistant", answering("far too long")).withMiddleware(m, guard))
    agent.run("question").value.status shouldBe a[AgentStatus.Blocked]
    contents(held(m, None)) shouldBe empty
  }

  "The memory middleware inside guardrail middleware" should "be refused when the agent is built (design 4.37)" in {
    val (stores, _) = perTenant()
    val m           = new SemanticMemoryMiddleware(stores, simple, answering())
    val guard       = new GuardrailMiddleware(Nil, Seq(new LengthCheck(0, 3)))
    val review      = new org.llm4s.agent.graph.middleware.GuardrailReviewMiddleware(Nil, Nil)
    val error       = Agent.builder("assistant", answering()).withMiddleware(guard, review, m).build().left.value
    error.message should include("'semantic-memory' must run outside 'guardrails'")
    error.message should include("'semantic-memory' must run outside 'guardrail-review'")
    // arranged outside by runsBefore, whatever the registration order
    val placed = new SemanticMemoryMiddleware(stores, simple, answering(), runsBefore = Set(guard.id))
    Agent.builder("assistant", answering()).withMiddleware(guard, placed).build().isRight shouldBe true
  }

  it should "be refused on a handoff target when the root has the guardrail, whose afterAgent runs last" in {
    val (stores, _) = perTenant()
    val target =
      Agent.builder("target", answering()).withMiddleware(new SemanticMemoryMiddleware(stores, simple, answering()))
    val error = Agent
      .builder("root", answering())
      .withMiddleware(new GuardrailMiddleware(Nil, Seq(new LengthCheck(0, 3))))
      .withHandoffs(org.llm4s.agent.Handoff.to("target", target, "delegate"))
      .build()
      .left
      .value
    error.message should include("agent 'target': middleware 'semantic-memory' must run outside 'guardrails'")
  }

  "A crash after the record's effect, then recover," should "leave exactly one copy of the turn" in {
    crashedRecord(Crash.Before) // the outcome's commit never landed: the store's applied key answers the replay
  }

  "A crash after the record's outcome, then recover," should "leave exactly one copy of the turn" in {
    crashedRecord(Crash.After) // the outcome landed: the guard replays it
  }

  /** A run whose store dies at the record's completed effect record, recovered by a new runtime over a new middleware. */
  private def crashedRecord(at: Crash) =
    val file   = java.nio.file.Files.createTempFile("llm4s-f7-crash", ".db")
    val stores = (_: Option[TenantId]) => SQLiteMemoryStore(file.toString): Result[MemoryStore]
    val clock  = ManualClock(Instant.parse("2026-10-14T09:00:00Z"))
    val claims = ClaimPolicy(ttl = 2.hours, renewEvery = 1.hour)
    val base   = InMemoryCheckpointer(clock)
    val store  = Crashing(base, c => Option.when(effects(c, "memory.record", completed = true))(at))
    val thread = ThreadId("crash")
    val first = agentWith(
      answering("teal"),
      new SemanticMemoryMiddleware(stores, simple, answering()),
      Some(GraphRuntime(store, clock, claims))
    )
    first.run(thread, "my colour").isLeft shouldBe true
    store.dead.get shouldBe true
    clock.advance(3.hours)
    val m     = new SemanticMemoryMiddleware(stores, simple, answering())
    val again = agentWith(answering(), m, Some(GraphRuntime(base, clock, claims)))
    again.recover(thread).value.answer shouldBe Some("teal")
    contents(held(m, None)) shouldBe Vector("my colour", "teal")
    java.nio.file.Files.deleteIfExists(file)

  /** Two old conversations of three memories each, in a SQLite file. */
  private def seeded(file: java.nio.file.Path): Unit =
    val old = Instant.parse("2026-01-01T00:00:00Z")
    val memories = for
      conv <- Seq("conv-a", "conv-b")
      i    <- 1 to 3
    yield Memory.fromConversation(s"$conv message $i", "user", Some(conv)).copy(timestamp = old.plusSeconds(i))
    val s = SQLiteMemoryStore(file.toString).value
    s.storeAll(memories).value
    s.close()

  "One afterAgent" should "record and consolidate two groups, all applied as distinct effects" in {
    val file = java.nio.file.Files.createTempFile("llm4s-f7-groups", ".db")
    seeded(file)
    val summaries = new FunctionClient(_ => "Consolidated summary.")
    val m = new SemanticMemoryMiddleware(
      _ => SQLiteMemoryStore(file.toString),
      summarising,
      summaries,
      consolidate = consolidate,
      clock = later
    )
    val seen = new CopyOnWriteArrayList[StreamEvent]()
    agentWith(answering("ok"), m).stream(ThreadId("groups"), "hello")(e => seen.add(e): Unit).value.await().value
    contents(held(m, None)) shouldBe Vector("Consolidated summary.", "Consolidated summary.", "hello", "ok")
    summaries.calls.get shouldBe 2
    val events = seen.asScala.toVector
    events.collect { case MemoryEvents.Recorded(r) => r } shouldBe Vector(MemoryRecorded(2, 0))
    events.collect { case MemoryEvents.Consolidated(c) => c } shouldBe Vector(MemoryConsolidated(2, 6, 0))
    java.nio.file.Files.deleteIfExists(file)
  }

  "A crash in the middle of a consolidation, then recover," should "leave exactly the consolidated set" in {
    for at <- Seq(Crash.Before, Crash.After) do
      val file = java.nio.file.Files.createTempFile("llm4s-f7-mid", ".db")
      seeded(file)
      val clock  = ManualClock(Instant.parse("2026-10-14T09:00:00Z"))
      val claims = ClaimPolicy(ttl = 2.hours, renewEvery = 1.hour)
      val base   = InMemoryCheckpointer(clock)
      val begun  = new java.util.concurrent.atomic.AtomicInteger()
      // dies at the second group's intent: the first group applied and completed, the second never ran
      val store = Crashing(
        base,
        c => Option.when(effects(c, "memory.consolidate", completed = false) && begun.incrementAndGet() == 2)(at)
      )
      def middleware() = new SemanticMemoryMiddleware(
        _ => SQLiteMemoryStore(file.toString),
        summarising,
        new FunctionClient(_ => "Consolidated summary."),
        consolidate = consolidate,
        clock = later
      )
      val thread = ThreadId("mid")
      agentWith(answering("ok"), middleware(), Some(GraphRuntime(store, clock, claims)))
        .run(thread, "hello")
        .isLeft shouldBe true
      clock.advance(3.hours)
      val m = middleware()
      agentWith(answering(), m, Some(GraphRuntime(base, clock, claims))).recover(thread).value
      withClue(s"crash $at: ") {
        contents(held(m, None)) shouldBe Vector("Consolidated summary.", "Consolidated summary.", "hello", "ok")
      }
      java.nio.file.Files.deleteIfExists(file)
  }

  "A replayed record" should "not resurrect memories another thread consolidated meanwhile" in {
    val clock  = ManualClock(Instant.parse("2026-10-14T09:00:00Z"))
    val claims = ClaimPolicy(ttl = 2.hours, renewEvery = 1.hour)
    val base   = InMemoryCheckpointer(clock)
    // thread a dies after its record applied, before the outcome was recorded
    val dying = Crashing(base, c => Option.when(effects(c, "memory.record", completed = true))(Crash.Before))
    val m = new SemanticMemoryMiddleware(
      _ => Right(InMemoryStore.empty),
      summarising,
      new FunctionClient(_ => "Consolidated summary."),
      consolidate = Some(MemoryConsolidation(olderThan = 1.minute, minCount = 2)),
      clock = later
    )
    agentWith(answering("teal"), m, Some(GraphRuntime(dying, clock, claims)))
      .run(ThreadId("a"), "my colour")
      .isLeft shouldBe true
    contents(held(m, None)) shouldBe Vector("my colour", "teal")
    // thread b's turn consolidates thread a's two memories, and its own
    agentWith(answering("noted"), m, Some(GraphRuntime(base, clock, claims))).run(ThreadId("b"), "hello").value
    contents(held(m, None)) shouldBe Vector("Consolidated summary.", "Consolidated summary.")
    clock.advance(3.hours)
    agentWith(answering(), m, Some(GraphRuntime(base, clock, claims))).recover(ThreadId("a")).value
    contents(held(m, None)) shouldBe Vector("Consolidated summary.", "Consolidated summary.")
  }

  "A store that is not transactional" should "record through the Unverifiable guard" in {
    val m = new SemanticMemoryMiddleware(_ => Right(new PlainStore(InMemoryStore.empty)), simple, answering())
    agentWith(answering("ok"), m).run("hello").value
    contents(held(m, None)) shouldBe Vector("hello", "ok")
  }

  it should "refuse consolidation before any model call" in {
    val client = answering("never")
    val m = new SemanticMemoryMiddleware(
      _ => Right(new PlainStore(InMemoryStore.empty)),
      summarising,
      answering(),
      consolidate = consolidate
    )
    val error = agentWith(client, m).run("hello").left.value
    error.message should include("TransactionalMemoryStore")
    client.callCount shouldBe 0
  }

  "A resolver" should "be refused when it gives two tenants one store instance" in {
    val shared = InMemoryStore.empty
    val m      = new SemanticMemoryMiddleware(_ => Right(shared), simple, answering())
    val agent  = agentWith(answering("one", "two"), m)
    agent.run(ThreadId("x"), "hi", tenant(t1)).value
    agent.run(ThreadId("y"), "hi", tenant(t2)).left.value.message should include("another tenant")
  }

  it should "be asked again after it fails" in {
    val attempts = new java.util.concurrent.atomic.AtomicInteger()
    val m = new SemanticMemoryMiddleware(
      _ =>
        if attempts.incrementAndGet() == 1 then Left(org.llm4s.error.ProcessingError("open", "not yet"))
        else Right(InMemoryStore.empty),
      simple,
      answering()
    )
    val agent = agentWith(answering("ok"), m)
    agent.run(ThreadId("r1"), "hi").isLeft shouldBe true
    agent.run(ThreadId("r2"), "hi").value.answer shouldBe Some("ok")
  }

  "An output Block" should "restore the recalled key to its value before the turn (C1)" in {
    val store      = InMemoryStore.withMemories(Seq(Memory.userFact("apples are red"))).value
    val m          = new SemanticMemoryMiddleware(_ => Right(store), simple, answering(), record = None)
    val guard      = new GuardrailMiddleware(Nil, Seq(new LengthCheck(0, 5)))
    val checkpoint = new InMemoryCheckpointer
    val peek       = new Peek
    val agent = built(
      Agent
        .builder("assistant", answering("short", "much too long"))
        .withMiddleware(m, guard, peek)
        .withRuntime(GraphRuntime(checkpoint))
    )
    val thread = ThreadId("restore")
    def stored = checkpoint.latest(thread).value.value.checkpoint.snapshot.state.keySet
    agent.run(thread, "apples").value
    stored should not contain SemanticMemoryMiddleware.RecalledKey.id.value
    // the blocked turn recalled, and its Block takes the recall away with the rest of the turn
    agent.run(thread, "apples").value.status shouldBe a[AgentStatus.Blocked]
    peek.seen.asScala.last should include("apples are red")
    stored should not contain SemanticMemoryMiddleware.RecalledKey.id.value
  }

  "An agent's existing thread" should "continue at rest once the middleware is added, and after it is removed (§4.29)" in {
    val checkpoint                   = new InMemoryCheckpointer
    val thread                       = ThreadId("evolving")
    def agent(builder: AgentBuilder) = built(builder.withRuntime(GraphRuntime(checkpoint)))
    agent(Agent.builder("assistant", answering("hi"))).run(thread, "My dog is called Rex").value
    checkpoint.latest(thread).value.value.checkpoint.snapshot.state.keySet should not contain
      SemanticMemoryMiddleware.RecalledKey.id.value
    val store  = InMemoryStore.withMemories(Seq(Memory.userFact("The dog is called Rex"))).value
    val client = answering("Rex")
    val remembering = agent(
      Agent
        .builder("assistant", client)
        .withMiddleware(new SemanticMemoryMiddleware(_ => Right(store), simple, answering()))
    )
    remembering.run(thread, "What is my dog called?").value.answer shouldBe Some("Rex")
    systemPrompt(client.sent.head) should include("The dog is called Rex")
    // and back: the thread at rest holds no recall, so an agent without the middleware takes it again
    agent(Agent.builder("assistant", answering("bye"))).run(thread, "Goodbye").value.answer shouldBe Some("bye")
  }

  it should "be refused in flight, suspended for an approval, by an agent that added the middleware" in {
    val checkpoint = new InMemoryCheckpointer
    val thread     = ThreadId("parked")
    def agent(builder: AgentBuilder) =
      built(
        builder
          .withTools(
            org.llm4s.agent.SpecTools.set(
              org.llm4s.agent.SpecTools.tool("echo")((a, _) =>
                org.llm4s.agent.graph.tool.ToolOutcome
                  .Success(org.llm4s.agent.graph.tool.ToolContent.Json(ujson.Str(a.text)))
              )
            )
          )
          .withMiddleware(new org.llm4s.agent.graph.middleware.ApprovalMiddleware(_ => Some("review")))
          .withRuntime(GraphRuntime(checkpoint))
      )
    val calling = org.llm4s.agent.SpecTools.calling(org.llm4s.agent.SpecTools.call("c1", "echo"))
    val parked  = agent(Agent.builder("assistant", ScriptedLLMClient.of(calling))).run(thread, "go").value
    val answers = parked.status match {
      case AgentStatus.Suspended(approvals, _, _, _) => approvals.map(a => parked.approve(a._1)).toMap
      case other                                     => fail(s"expected Suspended, got $other")
    }
    val memory = new SemanticMemoryMiddleware(_ => Right(InMemoryStore.empty), simple, answering())
    agent(Agent.builder("assistant", answering()).withMiddleware(memory))
      .resume(thread, answers)
      .left
      .value shouldBe a[GraphError.RestoreRejected]
  }

  "An agent without SemanticMemoryMiddleware" should "register no memory key, so its structure is unchanged (rule 7)" in {
    val store                        = new InMemoryCheckpointer
    val thread                       = ThreadId("plain")
    def agent(builder: AgentBuilder) = built(builder.withRuntime(GraphRuntime(store)))
    val plain                        = agent(Agent.builder("assistant", answering("hi")))
    plain.run(thread, "q").value
    val state = plain.loop.graph.restore(store.latest(thread).value.value.checkpoint.snapshot).value.state
    state.get(SemanticMemoryMiddleware.RecalledKey).left.value shouldBe a[GraphError.UnknownStateKey]
    val remembering = agent(
      Agent
        .builder("assistant", answering())
        .withMiddleware(new SemanticMemoryMiddleware(_ => Right(InMemoryStore.empty), simple, answering()))
    )
    plain.loop.graph.fingerprint should not be remembering.loop.graph.fingerprint
  }

  "The settings" should "refuse limits below 1" in {
    an[IllegalArgumentException] should be thrownBy MemoryRecall(maxRecallTokens = 0)
    an[IllegalArgumentException] should be thrownBy MemoryRecording(maxRecordChars = 0)
    an[IllegalArgumentException] should be thrownBy MemoryConsolidation(minCount = 0)
    an[IllegalArgumentException] should be thrownBy MemoryConsolidation(olderThan = -1.second)
    MemoryRecall().withMaxRecallTokens(7).withFilter(MemoryFilter.userFacts).maxRecallTokens shouldBe 7
    MemoryRecording().withMaxRecordChars(9).maxRecordChars shouldBe 9
    MemoryConsolidation().withOlderThan(2.days).withMinCount(4) shouldBe MemoryConsolidation(2.days, 4)
  }

  "With recall off" should "show no section and leave the key empty" in {
    val store  = InMemoryStore.withMemories(Seq(Memory.userFact("apples are red"))).value
    val m      = new SemanticMemoryMiddleware(_ => Right(store), simple, answering(), recall = None, record = None)
    val client = answering("ok")
    agentWith(client, m).run("apples").value
    (systemPrompt(client.sent.head) should not).include("apples are red")
  }

  "With recording and consolidation off" should "leave the store as it was" in {
    val (stores, _) = perTenant()
    val m           = new SemanticMemoryMiddleware(stores, simple, answering(), record = None)
    agentWith(answering("ok"), m).run("hello").value
    contents(held(m, None)) shouldBe empty
  }

  "Consolidation with recording off" should "still replace each group" in {
    val old = Instant.parse("2026-01-01T00:00:00Z")
    val seeded = InMemoryStore
      .withMemories((1 to 3).map(i => Memory.fromConversation(s"m$i", "user", Some("c")).copy(timestamp = old)))
      .value
    val m = new SemanticMemoryMiddleware(
      _ => Right(seeded),
      summarising,
      new FunctionClient(_ => "Consolidated summary."),
      record = None,
      consolidate = consolidate,
      clock = later
    )
    agentWith(answering("ok"), m).run("hello").value
    contents(held(m, None)) shouldBe Vector("Consolidated summary.")
  }

  "A consolidation that fails after a group applied" should "still announce the group it replaced" in {
    val a = Memory.userFact("a")
    val b = Memory.userFact("b")
    // replaces one group through the view, then fails, as a strict manager does on a later group
    val failingLater = (store: MemoryStore, _: LLMClient) =>
      new Overlong(
        SimpleMemoryManager.withStore(store),
        _ => Right(""),
        consolidating = Some(view =>
          view
            .replace(Seq(Memory.userFact("a and b")), Seq(a.id, b.id))
            .flatMap(_ => Left(org.llm4s.error.ProcessingError("summary", "the second group failed")))
        )
      )
    val m = new SemanticMemoryMiddleware(
      _ => Right(InMemoryStore.withMemories(Seq(a, b)).value),
      failingLater,
      answering(),
      record = None,
      consolidate = consolidate
    )
    val seen = new CopyOnWriteArrayList[StreamEvent]()
    agentWith(answering("ok"), m).stream(ThreadId("partial"), "hi")(e => seen.add(e): Unit).value.await().value
    contents(held(m, None)) shouldBe Vector("a and b")
    val events = seen.asScala.toVector
    events.collect { case MemoryEvents.Consolidated(c) => c } shouldBe Vector(MemoryConsolidated(1, 2, 0))
    events.collect { case MemoryEvents.Skipped(s) => s } shouldBe
      Vector(MemorySkipped("memory.consolidate", "ProcessingError"))
  }

  "A consolidation whose sources another writer already replaced" should "announce nothing" in {
    val stale = (store: MemoryStore, _: LLMClient) =>
      new Overlong(
        SimpleMemoryManager.withStore(store),
        _ => Right(""),
        consolidating = Some(view =>
          view.replace(Seq(Memory.userFact("summary")), Seq(MemoryId("gone"))).map(_ => SimpleMemoryManager.empty)
        )
      )
    val m = new SemanticMemoryMiddleware(
      _ => Right(InMemoryStore.empty),
      stale,
      answering(),
      record = None,
      consolidate = consolidate
    )
    val seen = new CopyOnWriteArrayList[StreamEvent]()
    agentWith(answering("ok"), m).stream(ThreadId("stale"), "hi")(e => seen.add(e): Unit).value.await().value
    contents(held(m, None)) shouldBe empty
    seen.asScala.collect { case MemoryEvents.Consolidated(c) => c } shouldBe empty
  }

  "A manager that swallows a cancellation" should "still fail the turn" in {
    // as LLMMemoryManager did in best-effort mode: the task is interrupted, but the manager returns success
    val swallowing = (store: MemoryStore, _: LLMClient) =>
      new Overlong(
        SimpleMemoryManager.withStore(store),
        _ => Right(""),
        consolidating = Some { _ =>
          Thread.currentThread().interrupt()
          Right(SimpleMemoryManager.empty)
        }
      )
    val m = new SemanticMemoryMiddleware(
      _ => Right(InMemoryStore.empty),
      swallowing,
      answering(),
      record = None,
      consolidate = consolidate
    )
    agentWith(answering("ok"), m).run("hi").isLeft shouldBe true
    Thread.interrupted(): Unit // the run's thread is not this one; clear defensively
  }

  "A manager that returns a store other than its view" should "have its recording skipped, the answer kept" in {
    val elsewhere = (_: MemoryStore, _: LLMClient) => SimpleMemoryManager.withStore(InMemoryStore.empty)
    val m         = new SemanticMemoryMiddleware(_ => Right(InMemoryStore.empty), elsewhere, answering())
    val seen      = new CopyOnWriteArrayList[StreamEvent]()
    val result =
      agentWith(answering("ok"), m).stream(ThreadId("other"), "hi")(e => seen.add(e): Unit).value.await().value
    result.answer shouldBe Some("ok")
    seen.asScala.collect { case MemoryEvents.Skipped(s) => s }.toVector shouldBe
      Vector(MemorySkipped("memory.record", "ValidationError"))
  }

  "A cancelled memory operation" should "fail the turn rather than be skipped" in {
    val cancelled = org.llm4s.error.CancelledError("memory")
    val inRecall =
      (store: MemoryStore, _: LLMClient) => new Overlong(SimpleMemoryManager.withStore(store), _ => Left(cancelled))
    val m1 = new SemanticMemoryMiddleware(_ => Right(InMemoryStore.empty), inRecall, answering())
    agentWith(answering("ok"), m1).run("hi").isLeft shouldBe true
    val inRecord = (store: MemoryStore, _: LLMClient) =>
      new Overlong(SimpleMemoryManager.withStore(store), _ => Right(""), Some(Left(cancelled)))
    val m2 = new SemanticMemoryMiddleware(_ => Right(InMemoryStore.empty), inRecord, answering(), recall = None)
    agentWith(answering("ok"), m2).run("hi").isLeft shouldBe true
  }

  "A recovered turn whose store no longer resolves" should "keep its answer and announce the skipped record" in {
    val clock  = ManualClock(Instant.parse("2026-10-14T09:00:00Z"))
    val claims = ClaimPolicy(ttl = 2.hours, renewEvery = 1.hour)
    val base   = InMemoryCheckpointer(clock)
    val store  = Crashing(base, c => Option.when(effects(c, "memory.record", completed = true))(Crash.Before))
    val thread = ThreadId("gone")
    val first  = new SemanticMemoryMiddleware(_ => Right(InMemoryStore.empty), simple, answering())
    agentWith(answering("teal"), first, Some(GraphRuntime(store, clock, claims)))
      .run(thread, "colour")
      .isLeft shouldBe true
    clock.advance(3.hours)
    val gone =
      new SemanticMemoryMiddleware(_ => Left(org.llm4s.error.ProcessingError("open", "gone")), simple, answering())
    val run = agentWith(answering(), gone, Some(GraphRuntime(base, clock, claims)))
    run.recover(thread).value.answer shouldBe Some("teal")
    val events = base.eventsAfter(thread, 0L, 1000).value
    events.map(_.event).collect {
      case RunEvent.Custom(name, _, payload) if name == "memory.skipped" => payload
    } shouldBe
      Vector(ujson.Obj("operation" -> "memory.resolve", "error" -> "ProcessingError"))
  }

  "The guide's Semantic Memory Middleware example" should "compile and run as written" in {
    val dir    = java.nio.file.Files.createTempDirectory("llm4s-f7-guide")
    val client = answering("Noted.", "Teal.")
    // as in docs/guide/agents/memory.md, "Semantic Memory Middleware"; the files go in a temporary directory
    val memory = new SemanticMemoryMiddleware(
      stores = tenant => SQLiteMemoryStore(dir.resolve(s"memory-${tenant.fold("default")(_.value)}.db").toString),
      manager = (store, _) => SimpleMemoryManager.withStore(store),
      client = client,
      recall = Some(MemoryRecall(maxRecallTokens = 512)),
      bounds = MemoryBounds(maxMemories = 500)
    )
    def ask(query: String) =
      for {
        agent  <- Agent.builder("assistant", client).withMiddleware(memory).build()
        result <- agent.run(ThreadId(s"t-${client.callCount}"), query, RunConfig().withTenantId(TenantId("alice")))
      } yield result.answer
    ask("My favourite colour is teal").value shouldBe Some("Noted.")
    ask("What did I say my favourite colour was?").value shouldBe Some("Teal.")
    systemPrompt(client.sent(1)) should include("My favourite colour is teal")
    java.nio.file.Files.exists(dir.resolve("memory-alice.db")) shouldBe true
  }

  // memory is text: a turn's media - a tool's image, imported history's documents - is never recorded or recalled,
  // so no media bytes reach a tenant's store (#1913)
  "A turn that carried media" should "record its user text and answer only, never the media" in {
    val (stores, _) = perTenant()
    val m           = new SemanticMemoryMiddleware(stores, simple, answering(), record = Some(MemoryRecording()))
    val chart = ContentPart
      .image(org.llm4s.media.MediaSource.Inline(IArray.fill[Byte](64)(9)), org.llm4s.media.MediaType.Png)
      .value
    val tool = org.llm4s.agent.SpecTools.tool("render")((_, _) =>
      org.llm4s.agent.graph.tool.ToolOutcome.Success(org.llm4s.agent.graph.tool.ToolContent.Parts(Vector(chart)))
    )
    val client = new ScriptedLLMClient(
      Right(org.llm4s.agent.SpecTools.calling(org.llm4s.agent.SpecTools.call("c1", "render"))),
      Right(CompletionFixture.simple("It rose."))
    )
    val history = Seq(UserMessage(Vector(ContentPart.Text("earlier"), chart)), AssistantMessage("ok"))
    built(Agent.builder("assistant", client).withMiddleware(m).withTools(org.llm4s.agent.SpecTools.set(tool)))
      .run(ThreadId("media"), "How did sales go?", RunConfig(), history)
      .value
    contents(held(m, None)) shouldBe Vector("How did sales go?", "It rose.")
  }
}

/** Records what `RecalledKey` holds whenever a model step's sections are collected. */
final class Peek extends org.llm4s.agent.graph.middleware.AgentMiddleware:
  val id   = org.llm4s.agent.graph.middleware.MiddlewareId("peek")
  val seen = new java.util.concurrent.CopyOnWriteArrayList[String]()
  override def promptSections(context: org.llm4s.agent.graph.middleware.ModelContext) =
    context.state.get(SemanticMemoryMiddleware.RecalledKey).map { text =>
      seen.add(text)
      Vector.empty
    }
