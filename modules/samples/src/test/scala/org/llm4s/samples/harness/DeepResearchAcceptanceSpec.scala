package org.llm4s.samples.harness

import org.llm4s.agent.{ AgentResult, AgentStatus }
import org.llm4s.agent.events.AgentEvents
import org.llm4s.agent.graph._
import org.llm4s.agent.graph.sqlite.SqliteCheckpointer
import org.llm4s.agent.harness._
import org.llm4s.agent.memory.MemoryFilter
import org.llm4s.agent.testkit.ManualClock
import org.llm4s.llmconnect.model._
import org.llm4s.samples.harness.StoppingCheckpointer.Stop
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{ Files, Path }
import java.time.Instant
import java.util.concurrent.{ ConcurrentHashMap, CopyOnWriteArrayList }
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.util.Using

/**
 * Stage 3's first exit example (#1916, design §4.38): the deep-research agent of `DeepResearch`, on a scripted model,
 * over SQLite. Each case runs the sample's own agent, and asserts the five exit capabilities - tool policy, context
 * management, streaming, checkpoint/resume, persisted artifacts - and the runtime rules they rest on.
 */
class DeepResearchAcceptanceSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues {

  private val tenant = TenantId("research-team")
  private val config = RunConfig().withTenantId(tenant)
  private val claims = ClaimPolicy(ttl = 2.hours, renewEvery = 1.hour)

  /** What each "process" shares: the database and memory files, the clock, and the pages fetched. */
  final private class World {
    val dir: Path                 = Files.createTempDirectory("llm4s-deep-research")
    val db: Path                  = dir.resolve("research.db")
    val clock                     = ManualClock(Instant.parse("2026-10-16T09:00:00Z"))
    val fetched                   = new ConcurrentHashMap[String, AtomicInteger]()
    val clients                   = new CopyOnWriteArrayList[ScriptedLLMClient]()
    def fetches(url: String): Int = Option(fetched.get(url)).fold(0)(_.get)
    def pages(url: String): Option[String] = {
      fetched.computeIfAbsent(url, _ => new AtomicInteger()).incrementAndGet()
      DeepResearch.pages.get(url)
    }
    def modelCalls: Int = clients.asScala.map(_.callCount).sum

    /** A process: its own store over the file, runtime, memory stores, model client and agent. */
    def process(stop: Commit => Option[Stop] = _ => None): Process = {
      val sqlite   = SqliteCheckpointer.open(db, clock).value
      val store    = new StoppingCheckpointer(sqlite, stop)
      val memories = new MemoryFiles(dir)
      val client   = ResearchModel.client()
      clients.add(client)
      val research = DeepResearch.agent(client, GraphRuntime(store, clock, claims), memories.store, pages).value
      Process(sqlite, store, memories, client, research)
    }

    /** What `tenant`'s memory store holds, by content, read from its file. */
    def memories(t: Option[TenantId]): Vector[String] =
      Using.resource(new MemoryFiles(dir))(
        _.store(t).value.recall(MemoryFilter.All, 100).value.map(_.content).toVector.sorted
      )
  }

  final private case class Process(
    sqlite: SqliteCheckpointer,
    store: StoppingCheckpointer,
    memories: MemoryFiles,
    client: ScriptedLLMClient,
    research: DeepResearch.Research
  ) {
    def close(): Unit = { memories.close(); sqlite.close() }
  }

  /** The events a listener saw: the plan's statuses, and the answer's text, as they streamed. */
  final private class Seen {
    val plans = new CopyOnWriteArrayList[Vector[TodoStatus]]()
    val text  = new StringBuilder
    val handlers: EventHandlers[Unit] = EventHandlers
      .on(HarnessEvents.TodosUpdated)(t => plans.add(t.todos.map(_.status)): Unit)
      .on(AgentEvents.TextDelta)(d => text.synchronized(text.append(d.text)): Unit)
    def statuses: Vector[Vector[TodoStatus]] = plans.asScala.toVector
  }

  private def toolResults(result: AgentResult): Map[String, ToolMessage] =
    result.messages.collect { case t: ToolMessage => t.toolCallId -> t }.toMap

  private def state(p: Process, thread: ThreadId): ThreadState = p.research.stateIn(p.sqlite, thread).value

  /** Every result the thread has offloaded, by key: those it holds, and those since dropped or evicted. */
  private def offloaded(state: ThreadState): Vector[String] =
    state.get(ToolOutputOffload.key).toOption.fold(Vector.empty[String])(o => o.results.map(_.key) ++ o.evicted)

  "The deep-research example" should "stop after an intermediate SQLite checkpoint, and recover in a fresh runtime" in {
    val world  = new World
    val thread = ThreadId("durable-agents")

    // process 1 stops for good once the fetched page's commit is written: the page is fetched and offloaded
    val first =
      world.process(c => Option.when(StoppingCheckpointer.writes(c, ToolOutputOffload.key.id.value))(Stop.AfterWriting))
    val seen1 = new Seen
    first.research.agent
      .stream(thread, DeepResearch.Query, config)(seen1.handlers)
      .flatMap(_.await())
      .isLeft shouldBe true
    first.store.stopped shouldBe true
    world.fetches(DeepResearch.DurableAgentsPage) shouldBe 1
    seen1.statuses.headOption.value should contain(TodoStatus.InProgress) // the plan streamed as it was written
    first.close()

    // process 2: a new store over the same file. Nothing is pending, so recover (not resume) continues the run once
    // the dead process's claim has expired
    world.clock.advance(3.hours)
    val second = world.process()
    val before = state(second, thread)
    // the fetch's result is committed as a pending write of the superstep the process stopped in
    val pending = second.sqlite.latest(thread).value.value.pendingWrites
    pending.exists(_.operations.exists {
      case EncodedOperation.Update(id, _) => id == ToolOutputOffload.key.id.value
      case _                              => false
    }) shouldBe true
    before.get(TodoMiddleware.key).value.todos should have size 4
    second.research.filesIn(second.sqlite, thread).value shouldBe empty // the report is not written yet

    val seen2  = new Seen
    val result = second.research.agent.streamRecover(thread, config)(seen2.handlers).flatMap(_.await()).value
    result.status shouldBe a[AgentStatus.Completed]

    // persisted artifacts: the report and both notes of the parallel batch, read back from the SQLite file
    val files = second.research.filesIn(second.sqlite, thread).value
    files.get(DeepResearch.ReportPath).value shouldBe ResearchModel.Report
    files.get("/research/notes-a.md").value shouldBe DeepResearch.KeyFact
    files.get("/research/notes-b.md").value shouldBe DeepResearch.DurableAgentsPage
    files.keySet shouldBe Set(DeepResearch.ReportPath, "/research/notes-a.md", "/research/notes-b.md")
    // the typed answer
    result.decodeOutput[DeepResearch.Brief].value.sources shouldBe Vector(DeepResearch.DurableAgentsPage)

    // the committed fetch never ran again
    world.fetches(DeepResearch.DurableAgentsPage) shouldBe 1

    // tool policy: the other site and the application's files were refused, and the model was told why
    val results = toolResults(result)
    results("fetch-other").content should include(s"Denied: network ${ResearchModel.OtherSite}")
    results("write-app").content should include(s"Denied: write ${DeepResearch.AppMount}/AGENTS.md")
    world.fetches(ResearchModel.OtherSite) shouldBe 0

    // context management: the page was offloaded - the model saw a preview, kept its figure, and read the fact back
    // by path - and the older conversation was summarised
    results("fetch").content should include("/large_tool_results/")
    results("fetch").content.length should be < 4000
    results("fetch").parts.exists(ContentPart.isMedia) shouldBe true
    results("read-page").content should include(DeepResearch.KeyFact)
    val after = state(second, thread)
    // one page offloaded, once: since dropped, as the summary covers the result that named it
    offloaded(after) should have size 1
    after.get(SummarizationMiddleware.key).value shouldBe defined
    second.client.calls.exists(_.messages.exists {
      case u: UserMessage => u.content.startsWith(SummarizationMiddleware.SummaryHeader)
      case _              => false
    }) shouldBe true

    // the skill and the instructions the application ships were shown, and the skill read by its path
    val prompt = first.client.calls.head.messages.collectFirst { case s: SystemMessage => s.content }.value
    prompt should include(
      s"- research: How to research a topic from web pages and write a sourced report. (${DeepResearch.AppMount}/skills/research/SKILL.md)"
    )
    prompt should include("Never quote a credential")
    results("skill").content should include("Fetch each page with fetch_page")

    // streaming: the finished plan as it was written, and the answer as it was written
    seen2.statuses.lastOption.value.distinct shouldBe Vector(TodoStatus.Done)
    seen2.text.toString shouldBe ResearchModel.Answer

    // memory: the turn recorded once, for this tenant only
    world.memories(Some(tenant)) shouldBe Vector(ResearchModel.Answer, DeepResearch.Query).sorted
    // tenant scope: a new thread of the same tenant recalls the turn; another tenant's recalls nothing of it
    def firstPrompt(t: TenantId, id: String): String = {
      val probe = ScriptedLLMClient.always(Reply.text(ResearchModel.Answer))
      val agent = DeepResearch.agent(probe, GraphRuntime(second.sqlite, world.clock, claims), second.memories.store)
      agent.value.agent.run(ThreadId(id), DeepResearch.Query, RunConfig().withTenantId(t)).value
      probe.calls.head.messages.collectFirst { case s: SystemMessage => s.content }.value
    }
    firstPrompt(tenant, "same-tenant") should include("How durable agents survive a crash")
    (firstPrompt(TenantId("another-team"), "other-tenant") should not).include("How durable agents survive a crash")

    // every model call is accounted, across both processes
    result.usage.requestCount shouldBe world.modelCalls.toLong
    second.close()
  }

  it should "record memory once and keep one offload, after a crash before the finish commit" in {
    val world  = new World
    val thread = ThreadId("crash-at-finish")

    // the finish task records the turn in memory, then the process dies before its commit is written
    val first = world.process(c => Option.when(StoppingCheckpointer.completes(c, "memory.record"))(Stop.BeforeWriting))
    first.research.agent.run(thread, DeepResearch.Query, config).isLeft shouldBe true
    first.store.stopped shouldBe true
    world.memories(Some(tenant)) should have size 2 // the effect landed; its commit did not
    first.close()

    world.clock.advance(3.hours)
    val second = world.process()
    val result = second.research.agent.recover(thread, config).value
    result.decodeOutput[DeepResearch.Brief].value.report shouldBe DeepResearch.ReportPath

    // the finish task ran again: C4's guard answered the record from the store's applied key
    world.memories(Some(tenant)) shouldBe Vector(ResearchModel.Answer, DeepResearch.Query).sorted
    // no tool ran again, so the page was fetched and offloaded once
    world.fetches(DeepResearch.DurableAgentsPage) shouldBe 1
    offloaded(state(second, thread)) should have size 1
    second.research
      .filesIn(second.sqlite, thread)
      .value
      .get(DeepResearch.ReportPath)
      .value shouldBe ResearchModel.Report
    result.usage.requestCount shouldBe world.modelCalls.toLong
    second.close()
  }

  it should "fetch again, and offload once, when the process stops before the fetch's commit" in {
    val world  = new World
    val thread = ThreadId("crash-before-fetch")

    // the fetch runs, and its result is lost with the process: the tool runs at least once
    val first =
      world.process(c =>
        Option.when(StoppingCheckpointer.writes(c, ToolOutputOffload.key.id.value))(Stop.BeforeWriting)
      )
    first.research.agent.run(thread, DeepResearch.Query, config).isLeft shouldBe true
    world.fetches(DeepResearch.DurableAgentsPage) shouldBe 1
    first.close()

    world.clock.advance(3.hours)
    val second = world.process()
    second.research.agent.recover(thread, config).value.status shouldBe a[AgentStatus.Completed]
    world.fetches(DeepResearch.DurableAgentsPage) shouldBe 2
    // the rerun is the same call, with the same idempotency key, so its offload replaced its own: one in all
    offloaded(state(second, thread)) should have size 1
    world.memories(Some(tenant)) shouldBe Vector(ResearchModel.Answer, DeepResearch.Query).sorted
    second.close()
  }

  "An output Block" should "leave no file, plan, summary or memory from the blocked turn" in {
    val world   = new World
    val thread  = ThreadId("blocked")
    val process = world.process()
    val agent   = process.research.agent
    agent.run(thread, DeepResearch.Query, config).value.status shouldBe a[AgentStatus.Completed]
    val before        = state(process, thread)
    val filesBefore   = process.research.filesIn(process.sqlite, thread).value
    val memoryBefore  = world.memories(Some(tenant))
    val callsBefore   = process.client.callCount
    val summaryBefore = before.get(SummarizationMiddleware.key).value

    val blocked = agent.run(thread, ResearchModel.LeakQuery, config).value
    blocked.status shouldBe a[AgentStatus.Blocked]

    // the blocked turn did write a file and a plan, and its requests were summarised afresh ...
    val turn = process.client.calls.drop(callsBefore)
    turn
      .flatMap(_.messages)
      .collect { case t: ToolMessage if t.toolCallId == "leak-note" => t.content }
      .headOption
      .value should include("/research/key.md")
    val summaries = turn.flatMap(_.messages.collect {
      case u: UserMessage if u.content.startsWith(SummarizationMiddleware.SummaryHeader) => u.content
    })
    summaries.exists(s => !summaryBefore.exists(b => s.endsWith(b.text))) shouldBe true

    // ... and none of it is kept
    val after = state(process, thread)
    process.research.filesIn(process.sqlite, thread).value shouldBe filesBefore
    after.get(TodoMiddleware.key) shouldBe before.get(TodoMiddleware.key)
    after.get(SummarizationMiddleware.key) shouldBe before.get(SummarizationMiddleware.key)
    after.get(ToolOutputOffload.key) shouldBe before.get(ToolOutputOffload.key)
    // memory runs outside the guardrail, so the blocked answer was never recorded
    world.memories(Some(tenant)) shouldBe memoryBefore
    (blocked.messages.collect { case a: AssistantMessage => a.content }.mkString should not).include("AKIA")
    process.close()
  }
}
