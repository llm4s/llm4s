package org.llm4s.samples.harness

import org.llm4s.agent.{ Agent, AgentId, AgentResult, AgentStatus }
import org.llm4s.agent.delegation.ChildInterrupt
import org.llm4s.agent.events.{ AgentEvents, SubagentEvents, TaskEnded }
import org.llm4s.agent.graph._
import org.llm4s.agent.graph.sqlite.SqliteCheckpointer
import org.llm4s.agent.testkit.ManualClock
import org.llm4s.llmconnect.model._
import org.llm4s.samples.harness.ParallelDeepResearch._
import org.llm4s.samples.harness.ParallelResearchModel.Speaker
import org.llm4s.samples.harness.StoppingCheckpointer.Stop
import org.llm4s.testkit.{ RecordedCall, ScriptedLLMClient }
import org.scalatest.{ EitherValues, LoneElement, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{ Files, Path }
import java.time.Instant
import java.util.concurrent.{ ConcurrentHashMap, CopyOnWriteArrayList }
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger, AtomicReference }
import java.util.concurrent.locks.LockSupport
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

/**
 * Stage 4's exit example (#2053, design §4.49): the parallel-specialist deep-research agent of `ParallelDeepResearch`,
 * on a scripted model, over SQLite. Each case runs the sample's own agent: parallel children under their bound, a
 * crash and a recovery that does not run a finished child again, a child's failure and a child's approval the lead
 * composes after, a cancel, the nested stream, and the cleanup of every child thread - and the runtime rules they rest
 * on.
 */
class ParallelDeepResearchAcceptanceSpec
    extends AnyFlatSpec
    with Matchers
    with EitherValues
    with OptionValues
    with LoneElement {

  private val tenant     = TenantId("research-team")
  private val config     = RunConfig().withTenantId(tenant)
  private val claims     = ClaimPolicy(ttl = 2.hours, renewEvery = 1.hour)
  private val researcher = AgentId.unsafe("researcher")

  /** What each "process" shares: the database file, the clock, the pages fetched and every model client. */
  final private class World {
    val dir: Path                         = Files.createTempDirectory("llm4s-parallel-research")
    val db: Path                          = dir.resolve("research.db")
    val clock                             = ManualClock(Instant.parse("2026-10-20T09:00:00Z"))
    val fetched                           = new ConcurrentHashMap[String, AtomicInteger]()
    val clients                           = new CopyOnWriteArrayList[ScriptedLLMClient]()
    @volatile var onFetch: String => Unit = _ => ()

    def fetches(url: String): Int = Option(fetched.get(url)).fold(0)(_.get)
    def pages(url: String): Option[String] = {
      fetched.computeIfAbsent(url, _ => new AtomicInteger()).incrementAndGet()
      onFetch(url)
      ParallelResearchModel.pages.get(url)
    }
    def modelCalls: Int = clients.asScala.map(_.callCount).sum

    /** A process: its own store over the file, runtime, model client and agent. */
    def process(stop: Commit => Option[Stop] = _ => None, failing: Set[String] = Set.empty): Process = {
      val sqlite = SqliteCheckpointer.open(db, clock).value
      val store  = new StoppingCheckpointer(sqlite, stop)
      val client = ParallelResearchModel.client(failing)
      clients.add(client)
      Process(
        sqlite,
        store,
        client,
        ParallelDeepResearch.agent(client, GraphRuntime(store, clock, claims), pages).value
      )
    }
  }

  final private case class Process(
    sqlite: SqliteCheckpointer,
    store: StoppingCheckpointer,
    client: ScriptedLLMClient,
    agent: Agent
  ) {
    def close(): Unit = sqlite.close()

    /** This process's model calls made for `topic`'s researcher. */
    def researcherCalls(topic: String): Vector[RecordedCall] =
      client.calls.filter(c =>
        ParallelResearchModel.speaker(c.messages) == Speaker.Researcher &&
          ParallelResearchModel.questionOf(c.messages).exists(_.topic == topic)
      )
  }

  private def brief(result: AgentResult): DeepResearch.Brief = result.decodeOutput[DeepResearch.Brief].value

  private def toolResults(result: AgentResult): Map[String, ToolMessage] =
    result.messages.collect { case t: ToolMessage => t.toolCallId -> t }.toMap

  private def events(store: Checkpointer, thread: ThreadId): Vector[EventRecord] =
    store.eventsAfter(thread, 0L, 10_000).value

  /** Waits, without sleeping on the test's own clock, until `condition` holds or `within` passes. */
  private def await(within: FiniteDuration)(condition: => Boolean): Boolean = {
    val deadline = System.nanoTime() + within.toNanos
    var held     = condition
    while (!held && System.nanoTime() < deadline && !Thread.currentThread().isInterrupted) {
      LockSupport.parkNanos(5.millis.toNanos)
      held = condition
    }
    held
  }

  private def eventually(what: String)(condition: => Boolean): Unit =
    withClue(what)(await(10.seconds)(condition) shouldBe true)

  "The parallel deep-research example" should
    "run three researchers in parallel, at most two at once, each reporting typed Findings" in {
      val world = new World
      // every fetch waits until a second child is live, or the last has arrived, then stays live a little longer: the
      // children overlap as far as the bound lets them, and a third child live beside two would be seen
      val live    = new AtomicInteger(0)
      val peak    = new AtomicInteger(0)
      val arrived = new AtomicInteger(0)
      world.onFetch = _ => {
        peak.accumulateAndGet(live.incrementAndGet(), math.max): Unit
        arrived.incrementAndGet()
        await(5.seconds)(live.get >= 2 || arrived.get == Questions.size): Unit
        LockSupport.parkNanos(100.millis.toNanos)
        live.decrementAndGet(): Unit
      }
      val process = world.process()
      val thread  = ThreadId("parallel")
      val result  = process.agent.run(thread, Query, config).value

      result.status shouldBe a[AgentStatus.Completed]
      // the batch is wider than the bound, and the children overlapped exactly up to it
      MaxConcurrentChildren should be < Questions.size
      peak.get shouldBe MaxConcurrentChildren
      // each call's report is typed Findings, sourced from its own page
      val results = toolResults(result)
      Questions.foreach { q =>
        val findings = upickle.default.read[Findings](results(ParallelResearchModel.researchCall(q.topic)).content)
        findings shouldBe Findings(q.topic, ParallelDeepResearch.pages(q.url), Vector(q.url))
      }
      upickle.default.read[Review](results("review").content).approved shouldBe true
      brief(result) shouldBe DeepResearch.Brief("How durable agents work", ReportPath, Questions.map(_.url))
      val children = process.agent.children(thread, config).value
      children.map(_.origin.agent.value).sorted shouldBe Vector("critic", "researcher", "researcher", "researcher")
      children.map(_.status).distinct shouldBe Vector(CheckpointStatus.Completed)

      // rule 17: only typed data crossed - no child's conversation, tool call or tool result is in the lead's thread
      result.messages.foreach {
        case s: SystemMessage    => (s.content should not).startWith(ResearcherPrompt)
        case u: UserMessage      => u.content shouldBe Query
        case t: ToolMessage      => t.toolCallId should not be "fetch"
        case a: AssistantMessage => a.toolCalls.map(_.name).toSet should not contain "fetch_page"
      }
      // rule 11: the lead's usage counts every model call of the tree - its own and each child's - exactly once
      result.usage.requestCount shouldBe world.modelCalls.toLong
      world.modelCalls shouldBe 4 + 3 * 2 + 1

      // the child-task metric: one durable TaskEnded per child in the lead's log, with the child's own usage
      val ended = events(process.sqlite, thread).map(_.event).collect {
        case RunEvent.Custom(name, _, payload) if name == SubagentEvents.TaskEnded.name =>
          upickle.default.read[TaskEnded](payload)
      }
      ended.map(_.child.threadId).toSet shouldBe children.map(_.threadId).toSet
      ended.map(_.status).distinct shouldBe Vector("completed")
      ended.map(_.usage.requestCount).sum shouldBe 3 * 2 + 1

      // rule 9: no durable event of the lead or of any child carries a page, a report or the query
      val logged  = (thread +: children.map(_.threadId)).flatMap(t => events(process.sqlite, t)).map(_.toString)
      val content = ParallelDeepResearch.pages.values.toVector :+ Query :+ "Every claim is sourced"
      content.foreach(c => logged.exists(_.contains(c)) shouldBe false)
      process.close()
    }

  it should "show TaskStarted, the child's text deltas and TaskEnded per child in the merged stream" in {
    val world   = new World
    val process = world.process()
    val thread  = ThreadId("streams")
    // the merged stream, in the order the subscription delivered it
    val seen = new CopyOnWriteArrayList[(ThreadId, String)]()
    val text = new ConcurrentHashMap[ThreadId, StringBuilder]()
    val handlers = EventHandlers
      .on(SubagentEvents.TaskStarted)(s => seen.add(s.child.threadId -> "started"): Unit)
      .on(SubagentEvents.TaskEnded)(e => seen.add(e.child.threadId -> s"ended ${e.status}"): Unit)
      .onChild(AgentEvents.TextDelta) { (child, d) =>
        seen.add(child.threadId -> "delta")
        text.computeIfAbsent(child.threadId, _ => new StringBuilder).append(d.text): Unit
      }
    val result = process.agent.stream(thread, Query, config, nested = true)(handlers).flatMap(_.await()).value
    result.status shouldBe a[AgentStatus.Completed]

    val children = process.agent.children(thread, config).value
    children should have size 4
    val order = seen.asScala.toVector
    children.foreach { child =>
      val mine = order.filter(_._1 == child.threadId).map(_._2)
      withClue(s"${child.origin.agent.value} ${child.threadId.value}: $mine") {
        mine.head shouldBe "started"
        mine.last shouldBe "ended completed"
        mine.slice(1, mine.size - 1).distinct shouldBe Vector("delta")
      }
      // the deltas are the child's answer, as its model wrote it
      val answer = text.get(child.threadId).toString
      if (child.origin.agent == researcher) upickle.default.read[Findings](answer).sources should have size 1
      else upickle.default.read[Review](answer).approved shouldBe true
    }
    process.close()
  }

  it should "recover after a crash without running a finished child again, and recover the running one" in {
    val world  = new World
    val thread = ThreadId("crash")
    // the first child to fetch finishes; the second fetches once a third has started (so the first has finished and
    // released its slot), and its result is lost with the process; the third waits until the process has stopped
    val arrivals = new AtomicInteger(0)
    val topics   = new ConcurrentHashMap[Int, String]()
    val second   = new AtomicBoolean(false)
    val halted   = new AtomicBoolean(false)
    world.onFetch = url => {
      val n = arrivals.incrementAndGet()
      topics.put(n, Questions.find(_.url == url).value.topic)
      n match {
        case 1 => ()
        case 2 =>
          await(10.seconds)(arrivals.get == 3): Unit
          second.set(true)
        case _ => await(10.seconds)(halted.get): Unit
      }
    }
    val first = world.process { c =>
      val stop = second.get && StoppingCheckpointer.ranTool(c, researcher)
      if (stop) halted.set(true)
      Option.when(stop)(Stop.BeforeWriting)
    }
    first.agent.run(thread, Query, config).isLeft shouldBe true
    first.store.stopped shouldBe true
    val (finished, running, waiting) = (topics.get(1), topics.get(2), topics.get(3))
    val byTopic                      = Questions.map(q => q.topic -> q.url).toMap
    first.researcherCalls(finished) should have size 2
    first.researcherCalls(running) should have size 1
    first.researcherCalls(waiting) should have size 1
    first.close()

    // process 2: a new store over the same file, once the dead process's claims have lapsed
    world.clock.advance(3.hours)
    val next   = world.process()
    val listed = next.agent.children(thread, config).value
    listed.filter(_.status == CheckpointStatus.Completed) should have size 1
    listed.filter(_.status == CheckpointStatus.Running) should have size 2

    val result = next.agent.recover(thread, config).value
    result.status shouldBe a[AgentStatus.Completed]
    brief(result).sources.toSet shouldBe Questions.map(_.url).toSet

    // the finished child was not run again: no model call, no fetch
    next.researcherCalls(finished) shouldBe empty
    world.fetches(byTopic(finished)) shouldBe 1
    // the running ones continued from their checkpoints: their first model calls were not made again, only the lost
    // fetch was (at least once), then the answer
    Seq(running, waiting).foreach { topic =>
      val calls = next.researcherCalls(topic)
      calls.loneElement.messages.lastOption.value shouldBe a[ToolMessage]
      world.fetches(byTopic(topic)) shouldBe 2
    }

    // rule 14: one child per call across the crash
    val after = next.agent.children(thread, config).value
    after.map(_.threadId).toSet should contain allElementsOf listed.map(_.threadId)
    after.count(_.origin.agent == researcher) shouldBe Questions.size
    after.map(_.status).distinct shouldBe Vector(CheckpointStatus.Completed)
    // rule 11: every model call of both processes counted once
    result.usage.requestCount shouldBe world.modelCalls.toLong
    next.close()
  }

  it should "compose a Brief naming the gap when one researcher's provider fails" in {
    val world   = new World
    val failed  = Questions(1)
    val process = world.process(failing = Set(failed.topic))
    val thread  = ThreadId("failure")
    val result  = process.agent.run(thread, Query, config).value

    result.status shouldBe a[AgentStatus.Completed]
    // the lead saw the failure as the call's tool error ...
    val error = toolResults(result)(ParallelResearchModel.researchCall(failed.topic)).content
    error should include("subagent 'researcher'")
    // ... and composed from the other findings, naming the gap in the draft and the report
    val draft = process.client.calls
      .filter(c => ParallelResearchModel.speaker(c.messages) == Speaker.Critic)
      .flatMap(_.messages.collect { case u: UserMessage => u.content })
      .loneElement
    draft should include(ParallelResearchModel.gapLine(failed.topic))
    val report = result.messages
      .collect { case a: AssistantMessage => a.toolCalls }
      .flatten
      .find(_.id == "report")
      .value
      .arguments("content")
      .str
    report should include(ParallelResearchModel.gapLine(failed.topic))
    brief(result).sources shouldBe Questions.filterNot(_ == failed).map(_.url)

    // the failed child's thread stays, listed by children
    val researchers = process.agent.children(thread, config).value.filter(_.origin.agent == researcher)
    researchers.map(_.status).sortBy(_.toString) shouldBe
      Vector(CheckpointStatus.Completed, CheckpointStatus.Completed, CheckpointStatus.Failed).sortBy(_.toString)
    process.close()
  }

  it should "suspend the lead on a researcher's fetch outside the allowlist, and compose once it is approved" in {
    val world   = new World
    val process = world.process()
    val thread  = ThreadId("approval")
    val outside = ParallelResearchModel.OutsideSite

    val first = process.agent.run(thread, ParallelResearchModel.ApprovalQuery, config).value
    val suspended = first.status match {
      case s: AgentStatus.Suspended => s
      case other                    => fail(s"expected the lead suspended, got $other")
    }
    // one delegated approval: the researcher's fetch, asked by the lead's rule, nothing fetched yet
    val (id, request) = suspended.delegated.loneElement
    request.child.agent shouldBe researcher
    val approval = request.request match {
      case a: ChildInterrupt.Approval => a.request
      case other                      => fail(s"expected an approval, got $other")
    }
    approval.call.name shouldBe "fetch_page"
    approval.call.arguments("url").str shouldBe outside
    world.fetches(outside) shouldBe 0

    val done = process.agent.resume(thread, Map(first.approve(id)), config).value
    done.status shouldBe a[AgentStatus.Completed]
    world.fetches(outside) shouldBe 1
    brief(done).sources.toSet shouldBe Set(Questions(2).url, outside)
    process.agent.children(thread, config).value.map(_.status).distinct shouldBe Vector(CheckpointStatus.Completed)
    process.close()
  }

  it should "cancel both live children when the lead is cancelled, and recover to the Brief" in {
    val world   = new World
    val entered = new AtomicInteger(0)
    val release = new AtomicBoolean(false)
    world.onFetch = _ => {
      entered.incrementAndGet()
      await(30.seconds)(release.get): Unit
    }
    val process = world.process()
    val thread  = ThreadId("cancel")
    val run     = process.agent.start(thread, Query, config).value
    eventually("both children are fetching")(entered.get == MaxConcurrentChildren)
    run.cancel()
    run.await().isLeft shouldBe true

    val live = process.agent.children(thread, config).value
    live should have size MaxConcurrentChildren
    live.foreach { child =>
      eventually(s"${child.threadId.value} is cancelled")(
        events(process.sqlite, child.threadId).exists(_.event.isInstanceOf[RunEvent.RunCancelled])
      )
    }

    release.set(true)
    world.onFetch = _ => ()
    val recovered = new AtomicReference[Option[AgentResult]](None)
    eventually("the lead recovers") {
      recovered.set(process.agent.recover(thread, config).toOption)
      recovered.get.exists(_.status.isInstanceOf[AgentStatus.Completed])
    }
    brief(recovered.get.value).sources.toSet shouldBe Questions.map(_.url).toSet
    process.agent.children(thread, config).value.map(_.status).distinct shouldBe Vector(CheckpointStatus.Completed)
    process.close()
  }

  it should "delete every child thread when the lead's thread is forgotten" in {
    val world   = new World
    val process = world.process()
    val thread  = ThreadId("cleanup")
    process.agent.run(thread, Query, config).value.status shouldBe a[AgentStatus.Completed]
    val children = process.agent.children(thread, config).value
    children should have size 4

    process.agent.forget(thread, config).value
    process.sqlite.latest(thread).value shouldBe None
    children.foreach(child => process.sqlite.latest(child.threadId).value shouldBe None)
    process.close()
  }
}
