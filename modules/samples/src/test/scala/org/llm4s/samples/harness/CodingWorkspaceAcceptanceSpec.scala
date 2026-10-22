package org.llm4s.samples.harness

import org.llm4s.agent.{ AgentResult, AgentStatus }
import org.llm4s.agent.events.AgentEvents
import org.llm4s.agent.graph._
import org.llm4s.agent.graph.sqlite.SqliteCheckpointer
import org.llm4s.agent.harness._
import org.llm4s.agent.harness.workspace.FakeWorkspace
import org.llm4s.agent.memory.MemoryFilter
import org.llm4s.agent.testkit.ManualClock
import org.llm4s.llmconnect.model._
import org.llm4s.samples.harness.StoppingCheckpointer.Stop
import org.llm4s.shared.ExecuteCommandResponse
import org.llm4s.testkit.ScriptedLLMClient
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{ Files, Path }
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.util.Using

/**
 * Stage 3's second exit example (#1916, design §4.38): the coding agent of `CodingWorkspace`, on a scripted model,
 * over SQLite and an in-memory workspace with the runner's file semantics (`FakeWorkspace`). Each case runs the
 * sample's own agent, and asserts the five exit capabilities - tool policy, context management, streaming,
 * checkpoint/resume, persisted artifacts - and the runtime rules they rest on. `CodingWorkspaceIntegrationSpec`
 * (`modules/it`, `@Workspace`) runs the scenario against the live container.
 */
class CodingWorkspaceAcceptanceSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues {

  private val tenant = TenantId("platform-team")
  private val config = RunConfig().withTenantId(tenant)
  private val claims = ClaimPolicy(ttl = 2.hours, renewEvery = 1.hour)

  /** A test run's log: long, as a test runner's is, so the harness offloads it. */
  private val TestLog: String = (1 to 300).map(i => s"[info] GreeterSpec case $i passed").mkString("\n")

  /** What each "process" shares: the workspace, the database and memory files, the clock and the commands run. */
  final private class World {
    val dir: Path = Files.createTempDirectory("llm4s-coding-workspace")
    val db: Path  = dir.resolve("coding.db")
    val clock     = ManualClock(Instant.parse("2026-10-16T09:00:00Z"))
    val commands  = new CopyOnWriteArrayList[String]()
    val clients   = new CopyOnWriteArrayList[ScriptedLLMClient]()
    val workspace = new FakeWorkspace(exec = (command, _, _, _) => {
      commands.add(command)
      ExecuteCommandResponse("local", s"1\n$TestLog", "", 0, isOutputTruncated = false, duration = 5.millis)
    })
    CodingWorkspace.seedFiles.foreach((path, text) => workspace.seed(path, text))

    def runs(command: String): Int = commands.asScala.count(_ == command)
    def modelCalls: Int            = clients.asScala.map(_.callCount).sum

    /** A process: its own store over the file, runtime, memory stores, model client and agent; the same workspace. */
    def process(stop: Commit => Option[Stop] = _ => None): Process = {
      val sqlite   = SqliteCheckpointer.open(db, clock).value
      val store    = new StoppingCheckpointer(sqlite, stop)
      val memories = new MemoryFiles(dir)
      val client   = CodingModel.client()
      clients.add(client)
      val coder = CodingWorkspace.agent(client, GraphRuntime(store, clock, claims), workspace, memories.store).value
      Process(sqlite, store, memories, client, coder)
    }

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
    coder: CodingWorkspace.Coder
  ) {
    def close(): Unit = { memories.close(); sqlite.close() }

    def state(thread: ThreadId): ThreadState =
      sqlite
        .latest(thread)
        .flatMap(l => coder.agent.loop.graph.restore(l.value.checkpoint.snapshot))
        .map(_.state)
        .fold(e => throw new IllegalStateException(e.message), identity)
  }

  /** The events a listener saw: the plan's statuses, the tools run and the answer's text, as they streamed. */
  final private class Seen {
    val plans = new CopyOnWriteArrayList[Vector[TodoStatus]]()
    val tools = new CopyOnWriteArrayList[String]()
    val text  = new StringBuilder
    val handlers: EventHandlers[Unit] = EventHandlers
      .on(HarnessEvents.TodosUpdated)(t => plans.add(t.todos.map(_.status)): Unit)
      .on(AgentEvents.ToolExecuted)(t => tools.add(t.tool): Unit)
      .on(AgentEvents.TextDelta)(d => text.synchronized(text.append(d.text)): Unit)
    def statuses: Vector[Vector[TodoStatus]] = plans.asScala.toVector
  }

  private def toolResults(result: AgentResult): Map[String, ToolMessage] =
    result.messages.collect { case t: ToolMessage => t.toolCallId -> t }.toMap

  private def offloaded(state: ThreadState): Vector[String] =
    state.get(ToolOutputOffload.key).toOption.fold(Vector.empty[String])(o => o.results.map(_.key) ++ o.evicted)

  private def approvals(result: AgentResult): Map[InterruptId, ujson.Value] =
    result.status match {
      case AgentStatus.Suspended(approvals, _, _, _, _) => approvals.map((id, _) => result.approve(id)).toMap
      case other                                        => fail(s"not suspended: $other")
    }

  "The coding example" should "suspend at execute, resume approved in a fresh runtime, and run the command once" in {
    val world  = new World
    val thread = ThreadId("fix-greeting")

    // process 1: the run plans, edits in one batch, and suspends when it asks to run the check
    val first = world.process()
    val seen1 = new Seen
    val suspended =
      first.coder.agent.stream(thread, CodingWorkspace.Query, config)(seen1.handlers).flatMap(_.await()).value
    suspended.status match {
      case AgentStatus.Suspended(approvals, _, _, _, _) =>
        approvals.map(_._2.call.name) shouldBe Vector("execute")
        approvals.head._2.reason should include(s"execute ${CodingModel.CheckCommand}")
      case other => fail(s"not suspended: $other")
    }
    world.runs(CodingModel.CheckCommand) shouldBe 0
    // the parallel batch's edits are in the workspace; the write outside /src was denied
    world.workspace.text(CodingWorkspace.SourcePath) shouldBe CodingModel.FixedSource
    world.workspace.text("/src/GreeterSpec.scala") shouldBe CodingModel.TestSource
    world.workspace.exists("/README.md") shouldBe false
    toolResults(suspended)("readme").content should include("Denied: write /README.md")
    seen1.statuses.headOption.value should contain(TodoStatus.InProgress)
    first.close()

    // process 2: a fresh runtime over the same file resumes the run approved; it runs the command, answers, records
    // the turn in memory - and stops before the finish task's commit is written
    val second = world.process(c => Option.when(StoppingCheckpointer.completes(c, "memory.record"))(Stop.BeforeWriting))
    val seen2  = new Seen
    second.coder.agent
      .streamResume(thread, approvals(suspended), config)(seen2.handlers)
      .flatMap(_.await())
      .isLeft shouldBe true
    second.store.stopped shouldBe true
    world.runs(CodingModel.CheckCommand) shouldBe 1
    world.memories(Some(tenant)) should have size 2
    seen2.tools.asScala should contain("execute")
    seen2.text.toString shouldBe CodingModel.Answer
    second.close()

    // process 3: once the dead process's claim has expired, recover runs the finish task again
    world.clock.advance(3.hours)
    val third  = world.process()
    val result = third.coder.agent.recover(thread, config).value
    result.answer.value shouldBe CodingModel.Answer

    // the command ran exactly once, through the suspension, the resume and the crash
    world.runs(CodingModel.CheckCommand) shouldBe 1
    // persisted artifacts: the edited files are in the workspace after resume
    world.workspace.text(CodingWorkspace.SourcePath) shouldBe CodingModel.FixedSource
    world.workspace.text("/src/GreeterSpec.scala") shouldBe CodingModel.TestSource
    // memory recorded once, for this tenant only
    world.memories(Some(tenant)) shouldBe Vector(CodingModel.Answer, CodingWorkspace.Query).sorted
    world.memories(Some(TenantId("another-team"))) shouldBe empty

    // context management: the command's long output was offloaded once, and the conversation summarised
    val results = toolResults(result)
    results("check").content should include("/large_tool_results/")
    results("check").content.length should be < TestLog.length
    val state = third.state(thread)
    offloaded(state) should have size 1
    state.get(SummarizationMiddleware.key).value shouldBe defined
    world.clients.asScala.exists(_.calls.exists(_.messages.exists {
      case u: UserMessage => u.content.startsWith(SummarizationMiddleware.SummaryHeader)
      case _              => false
    })) shouldBe true
    // the plan is finished, and the workspace's AGENTS.md was the standing instructions
    state.get(TodoMiddleware.key).value.todos.map(_.status).distinct shouldBe Vector(TodoStatus.Done)
    val prompt = first.client.calls.head.messages.collectFirst { case s: SystemMessage => s.content }.value
    prompt should include("## /AGENTS.md")
    prompt should include(CodingModel.CheckCommand)

    // every model call is accounted, across the three processes
    result.usage.requestCount shouldBe world.modelCalls.toLong
    third.close()
  }

  it should "never run the command twice, when the process stops before the command's commit" in {
    val world  = new World
    val thread = ThreadId("crash-at-command")
    val first  = world.process()
    val asked  = first.coder.agent.run(thread, CodingWorkspace.Query, config).value
    first.close()

    // the approved command runs, and the process stops before its outcome is committed: the outcome is unknown
    val second =
      world.process(c => Option.when(StoppingCheckpointer.completes(c, "sandbox.execute"))(Stop.BeforeWriting))
    second.coder.agent.resume(thread, approvals(asked), config).isLeft shouldBe true
    world.runs(CodingModel.CheckCommand) shouldBe 1
    second.close()

    // recover does not run it again: the replay is ambiguous, so it is refused, and the model is told
    world.clock.advance(3.hours)
    val third  = world.process()
    val result = third.coder.agent.recover(thread, config).value
    result.answer.value shouldBe CodingModel.Answer
    world.runs(CodingModel.CheckCommand) shouldBe 1
    toolResults(result)("check").content should include("it may already have run; it is not run again")
    world.memories(Some(tenant)) shouldBe Vector(CodingModel.Answer, CodingWorkspace.Query).sorted
    third.close()
  }

  "An output Block" should "leave no plan, summary or note from the blocked turn, and record no memory" in {
    val world   = new World
    val thread  = ThreadId("blocked")
    val process = world.process()
    val agent   = process.coder.agent
    val asked   = agent.run(thread, CodingWorkspace.Query, config).value
    agent.resume(thread, approvals(asked), config).value.answer.value shouldBe CodingModel.Answer
    val before       = process.state(thread)
    val notesBefore  = process.coder.notesIn(process.sqlite, thread).value
    val memoryBefore = world.memories(Some(tenant))
    val callsBefore  = process.client.callCount

    val blocked = agent.run(thread, CodingModel.LeakQuery, config).value
    blocked.status shouldBe a[AgentStatus.Blocked]

    // the blocked turn did write a note and a plan, and its requests were summarised afresh ...
    val turn = process.client.calls.drop(callsBefore)
    turn
      .flatMap(_.messages)
      .collect { case t: ToolMessage if t.toolCallId == "leak-note" => t.content }
      .headOption
      .value should include(s"${CodingWorkspace.NotesMount}/key.md")
    val summaryBefore = before.get(SummarizationMiddleware.key).value
    turn
      .flatMap(_.messages.collect {
        case u: UserMessage if u.content.startsWith(SummarizationMiddleware.SummaryHeader) => u.content
      })
      .exists(s => !summaryBefore.exists(b => s.endsWith(b.text))) shouldBe true

    // ... and none of it is kept in the thread
    val after = process.state(thread)
    process.coder.notesIn(process.sqlite, thread).value shouldBe notesBefore
    after.get(TodoMiddleware.key) shouldBe before.get(TodoMiddleware.key)
    after.get(SummarizationMiddleware.key) shouldBe before.get(SummarizationMiddleware.key)
    after.get(ToolOutputOffload.key) shouldBe before.get(ToolOutputOffload.key)
    world.memories(Some(tenant)) shouldBe memoryBefore
    // a file written in the workspace is an effect outside thread state: a Block cannot undo it (rule 5)
    world.workspace.exists("/src/Deploy.scala") shouldBe true
    process.close()
  }
}
