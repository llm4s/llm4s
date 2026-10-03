package org.llm4s.agent.graph.toolloop

import org.llm4s.agent.graph.*
import org.llm4s.agent.graph.GraphTestSupport.*
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.model.*
import org.llm4s.toolapi.{ Schema, ToolBuilder }
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.ReadWriter

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*

class ToolLoopSpec extends AnyFlatSpec with Matchers with EitherValues {

  private val thread = ThreadId("conversation-1")

  /** A model that plays `turns` in order and records every history it was given. */
  final private class ScriptedModel(turns: (Vector[Message] => AssistantMessage)*) extends ModelStep {
    val seen = new CopyOnWriteArrayList[Vector[Message]]()
    def next(messages: Vector[Message]): Result[AssistantMessage] = {
      val turn = seen.size
      seen.add(messages)
      turns.lift(turn).map(play => Right(play(messages))).getOrElse(Left(ValidationError("model", s"no turn $turn")))
    }
    def calls: Int = seen.size
  }

  private def calls(specs: (String, String, ujson.Value)*): Vector[Message] => AssistantMessage =
    _ => AssistantMessage(None, specs.map((id, name, args) => ToolCall(id, name, args)))

  /** The final turn: a summary of the tool results it was given, in order. */
  private val summarise: Vector[Message] => AssistantMessage = history =>
    AssistantMessage(
      "done: " + history.collect { case t: ToolMessage => s"${t.toolCallId}=${t.content}" }.mkString(" | ")
    )

  final private class Tools {
    val deploys = new CopyOnWriteArrayList[String]()
    val lookup  = LoopTool("lookup")((call, _) => ToolOutcome.Completed(s"found ${call.arguments("q").str}"))
    val echo    = LoopTool("echo")((call, _) => ToolOutcome.Completed(call.arguments("text").str))
    val deploy = LoopTool("deploy") { (call, approved) =>
      if !approved then ToolOutcome.NeedsApproval("deploys are irreversible")
      else
        deploys.add(call.arguments("env").str)
        ToolOutcome.Completed(s"deployed to ${call.arguments("env").str}")
    }
    val pesky   = LoopTool("pesky")((_, _) => ToolOutcome.NeedsApproval("always asks"))
    val broken  = LoopTool("broken")((_, _) => throw new IllegalStateException("boom"))
    val failing = LoopTool("failing")((_, _) => ToolOutcome.Failed("upstream said no"))
    val all     = Seq(lookup, echo, deploy, pesky, broken, failing)
  }

  private val policy: ToolCallPolicy = call =>
    call.name match {
      case "lookup"    => PolicyDecision.RequireApproval("lookups cost money")
      case "forbidden" => PolicyDecision.Deny("never allowed")
      case _           => PolicyDecision.Allow
    }

  private def loop(model: ModelStep, tools: Tools = Tools()) =
    ToolLoop.build("assistant", "v1", model, tools.all, policy).value

  private def threeCalls = ScriptedModel(
    calls(
      ("c1", "lookup", ujson.Obj("q" -> "x")),
      ("c2", "deploy", ujson.Obj("env" -> "prod")),
      ("c3", "echo", ujson.Obj("text" -> "hi"))
    ),
    summarise
  )

  private def messagesOf(state: ThreadState) = state.get(Messages.key).value.map(_.message)

  "The tool loop" should "suspend policy- and tool-raised approvals independently, behind the batch barrier" in {
    val model = threeCalls
    val tools = Tools()
    val l     = loop(model, tools)

    val first    = runInMemory(l.graph, "go").suspended
    val requests = l.requests(first).value
    requests.map((_, r) => (r.call.id, r.source)) shouldBe Vector(
      "c1" -> ApprovalSource.Policy,
      "c2" -> ApprovalSource.Tool
    )
    first.state.get(ToolLoop.results).value.map(_.toolCallId) shouldBe Vector("c3")
    messagesOf(first.state).map(_.role) shouldBe Vector(MessageRole.User, MessageRole.Assistant)
    model.calls shouldBe 1

    // answer only the policy approval: the model stays behind the barrier
    val (policyId, _) = requests.head
    val second =
      drive(l.graph, l.graph.resume(first.execution, l.answers(policyId -> ApprovalDecision.Approve)).value).suspended
    l.requests(second).value.map(_._2.call.id) shouldBe Vector("c2")
    second.state.get(ToolLoop.results).value.map(_.toolCallId) shouldBe Vector("c3", "c1")
    messagesOf(second.state).size shouldBe 2
    model.calls shouldBe 1

    // edit the tool-raised one: the assistant message is amended, then the call runs as edited
    val (toolId, _)     = requests(1)
    val edit            = ApprovalDecision.Edit(ujson.Obj("env" -> "staging"))
    val (state, answer) = drive(l.graph, l.graph.resume(second.execution, l.answers(toolId -> edit)).value).completed
    answer shouldBe "done: c1=found x | c2=deployed to staging | c3=hi"
    tools.deploys.asScala.toVector shouldBe Vector("staging")
    model.calls shouldBe 2

    val history = messagesOf(state)
    Message.validateConversation(history.toList).value shouldBe (())
    history.collect {
      case a: AssistantMessage if a.toolCalls.nonEmpty => a.toolCalls.map(c => c.id -> c.arguments)
    }.head shouldBe
      Seq("c1" -> ujson.Obj("q" -> "x"), "c2" -> ujson.Obj("env" -> "staging"), "c3" -> ujson.Obj("text" -> "hi"))
    // the model's second call saw exactly one result per call, in call order
    model.seen.get(1).collect { case t: ToolMessage => t.toolCallId } shouldBe Vector("c1", "c2", "c3")
    state.isSet(ToolLoop.results) shouldBe false
  }

  it should "write exactly one error result for denied, unknown, failing, rejected and re-asking calls" in {
    val model = ScriptedModel(
      calls(
        ("d1", "forbidden", ujson.Obj()),
        ("d2", "nosuch", ujson.Obj()),
        ("d3", "broken", ujson.Obj()),
        ("d4", "failing", ujson.Obj()),
        ("d5", "lookup", ujson.Obj("q" -> "y")),
        ("d6", "pesky", ujson.Obj())
      ),
      summarise
    )
    val l        = loop(model)
    val first    = runInMemory(l.graph, "go").suspended
    val requests = l.requests(first).value.map((id, r) => r.call.id -> id).toMap
    requests.keySet shouldBe Set("d5", "d6")
    val answers =
      l.answers(requests("d5") -> ApprovalDecision.Reject("too expensive"), requests("d6") -> ApprovalDecision.Approve)
    val (state, _) = drive(l.graph, l.graph.resume(first.execution, answers).value).completed

    val results =
      model.seen.get(1).collect { case t: ToolMessage => t.toolCallId -> ujson.read(t.content)("error").str }
    results shouldBe Vector(
      "d1" -> "Denied: never allowed",
      "d2" -> "Unknown tool 'nosuch'",
      "d3" -> "Tool 'broken' failed: boom",
      "d4" -> "upstream said no",
      "d5" -> "Rejected: too expensive",
      "d6" -> "Tool 'pesky' asked for approval again: always asks"
    )
    Message.validateConversation(messagesOf(state).toList).value shouldBe (())
  }

  it should "refuse a policy-denied edit without running it" in {
    val model = ScriptedModel(calls(("e1", "lookup", ujson.Obj("q" -> "x"))), summarise)
    val strict: ToolCallPolicy = call =>
      if call.arguments.obj.contains("q") && call.arguments("q").str == "secret" then PolicyDecision.Deny("secret")
      else PolicyDecision.RequireApproval("check")
    val l     = ToolLoop.build("assistant", "v1", model, Tools().all, strict).value
    val first = runInMemory(l.graph, "go").suspended
    val id    = l.requests(first).value.head._1
    val (state, answer) =
      drive(
        l.graph,
        l.graph.resume(first.execution, l.answers(id -> ApprovalDecision.Edit(ujson.Obj("q" -> "secret")))).value
      ).completed
    answer shouldBe """done: e1={"error":"Denied: secret"}"""
    // the edit is still recorded: the history shows what was refused
    messagesOf(state).collect {
      case a: AssistantMessage if a.toolCalls.nonEmpty => a.toolCalls.head.arguments
    }.head shouldBe
      ujson.Obj("q" -> "secret")
  }

  it should "resume approvals in separate runs and processes, persisting each suspension before returning" in {
    val model = threeCalls
    val tools = Tools()
    val store = InMemoryCheckpointer()
    val first =
      GraphRuntime(store)
        .start(thread, loop(model, tools).graph, "go", RunConfig().withRunId(RunId("run-1")), Durability.Async)
        .awaited
        .value
    val requests = loop(model, tools).requests(first.suspended).value.map((id, r) => r.call.id -> id).toMap
    store.latest(thread).value.map(_.checkpoint.status) shouldBe Some(CheckpointStatus.Suspended)

    // a new process: refused calls change nothing
    val again = loop(model, tools)
    GraphRuntime(store)
      .start(thread, again.graph, "more", RunConfig().withRunId(RunId("run-x")))
      .awaited
      .left
      .value shouldBe a[GraphError.PendingInterrupts]
    GraphRuntime(store)
      .recover(again.graph, thread, RunConfig().withRunId(RunId("run-x")))
      .awaited
      .left
      .value shouldBe a[GraphError.PendingInterrupts]
    GraphRuntime(store)
      .resume(again.graph, thread, Map(InterruptId("nope") -> ujson.Null), RunConfig().withRunId(RunId("run-x")))
      .awaited
      .left
      .value shouldBe
      a[GraphError.InvalidResume]
    store.latest(thread).value.map(_.checkpoint.runId) shouldBe Some("run-1")

    val second = GraphRuntime(store)
      .resume(
        again.graph,
        thread,
        again.answers(requests("c1") -> ApprovalDecision.Approve),
        RunConfig().withRunId(RunId("run-2")),
        Durability.OnExit
      )
      .awaited
      .value
    second.suspended.interrupts.map(_.id) shouldBe Vector(requests("c2"))
    store.latest(thread).value.map(s => s.checkpoint.status -> s.checkpoint.runId) shouldBe
      Some(CheckpointStatus.Suspended -> "run-2")

    val last = loop(model, tools)
    GraphRuntime(store)
      .resume(
        last.graph,
        thread,
        last.answers(requests("c2") -> ApprovalDecision.Approve),
        RunConfig().withRunId(RunId("run-3"))
      )
      .awaited
      .value
      .completed
      ._2 shouldBe "done: c1=found x | c2=deployed to prod | c3=hi"
    model.calls shouldBe 2
    tools.deploys.asScala.toVector shouldBe Vector("prod")

    GraphRuntime(store)
      .resume(
        last.graph,
        thread,
        last.answers(requests("c2") -> ApprovalDecision.Approve),
        RunConfig().withRunId(RunId("run-4"))
      )
      .awaited
      .left
      .value shouldBe
      GraphError.NotSuspended(thread.value)

    val events = store.eventsAfter(thread, 0L, 1000).value
    events.map(_.seq) shouldBe (1L to events.size.toLong).toVector
    def kind(r: EventRecord) = r.event.toString.takeWhile(_ != '(') + r.nodeId.fold("")(n => s"@$n")
    events
      .map(kind)
      .filter(k => k.startsWith("TaskSuspended") || k.startsWith("Run") || k == "TaskCompleted@approval") shouldBe
      Vector(
        "RunStarted",
        "TaskSuspended@call-tool",
        "TaskSuspended@call-tool",
        "RunSuspended",
        "RunResumed",
        "TaskCompleted@approval",
        "RunSuspended",
        "RunResumed",
        "TaskCompleted@approval",
        "RunCompleted"
      )
  }

  it should "refuse a resume that races another run for the thread, accepting nothing" in {
    val model = threeCalls
    val store = InMemoryCheckpointer()
    val l     = loop(model)
    val first =
      GraphRuntime(store).start(thread, l.graph, "go", RunConfig().withRunId(RunId("run-1"))).awaited.value.suspended
    val ids   = l.requests(first).value.map((id, r) => r.call.id -> id).toMap
    val stale = store.latest(thread).value

    GraphRuntime(store)
      .resume(l.graph, thread, l.answers(ids("c1") -> ApprovalDecision.Approve), RunConfig().withRunId(RunId("run-2")))
      .awaited
      .value
      .suspended
    val afterWinner = store.latest(thread).value.map(_.checkpoint.id)

    // the loser read the thread before the winner claimed it
    val racing = new Checkpointer {
      def commit(threadId: ThreadId, commit: Commit)                  = store.commit(threadId, commit)
      def latest(threadId: ThreadId)                                  = Right(stale)
      def eventsAfter(threadId: ThreadId, afterSeq: Long, limit: Int) = store.eventsAfter(threadId, afterSeq, limit)
      def compactEvents(threadId: ThreadId, beforeSeq: Long)          = store.compactEvents(threadId, beforeSeq)
    }
    GraphRuntime(racing)
      .resume(l.graph, thread, l.answers(ids("c2") -> ApprovalDecision.Approve), RunConfig().withRunId(RunId("run-3")))
      .awaited
      .left
      .value shouldBe
      GraphError.ThreadBusy(thread.value, afterWinner)
    store.latest(thread).value.map(_.checkpoint.id) shouldBe afterWinner
  }

  it should "treat a tool that throws as a failed call, without re-running its siblings" in {
    val echoes = new AtomicInteger()
    val model  = threeCalls
    val tools  = Tools()
    val counting = LoopTool("echo") { (call, _) =>
      echoes.incrementAndGet()
      ToolOutcome.Completed(call.arguments("text").str)
    }
    // the approved lookup throws, two runs after the echo's result was committed
    val flaky = LoopTool("lookup") { (call, approved) =>
      if approved then throw new java.io.IOException("connection reset")
      tools.lookup.execute(call, approved)
    }
    val l     = ToolLoop.build("assistant", "v1", model, Seq(flaky, counting, tools.deploy), policy).value
    val store = InMemoryCheckpointer()
    val first =
      GraphRuntime(store).start(thread, l.graph, "go", RunConfig().withRunId(RunId("run-1"))).awaited.value.suspended
    val ids     = l.requests(first).value.map((id, r) => r.call.id -> id).toMap
    val answers = l.answers(ids("c1") -> ApprovalDecision.Approve, ids("c2") -> ApprovalDecision.Approve)
    // a thrown tool is a tool-level failure, not a failed run: it becomes that call's error result
    GraphRuntime(store)
      .resume(l.graph, thread, answers, RunConfig().withRunId(RunId("run-2")))
      .awaited
      .value
      .completed
      ._2 shouldBe
      """done: c1={"error":"Tool 'lookup' failed: connection reset"} | c2=deployed to prod | c3=hi"""
    echoes.get shouldBe 1
  }

  it should "write no result for a cancelled call, and recover runs only that call" in {
    val started    = new java.util.concurrent.CountDownLatch(1)
    val slowRuns   = new AtomicInteger()
    val echoThread = new java.util.concurrent.LinkedBlockingQueue[Thread]()
    // echo records its thread; slow waits for that thread to end, so c1's result is certainly
    // committed before the interrupt (the technique of CancellationSpec.afterOthers, copied here)
    val echo = LoopTool("echo") { (call, _) =>
      echoThread.put(Thread.currentThread())
      ToolOutcome.Completed(call.arguments("text").str)
    }
    val slow = LoopTool("slow") { (_, _) =>
      if slowRuns.incrementAndGet() == 1 then
        echoThread.take().join()
        started.countDown()
        Thread.sleep(60_000) // interrupted: InterruptedException propagates out of the tool
      ToolOutcome.Completed("slow done")
    }
    val model = ScriptedModel(calls(("c1", "echo", ujson.Obj("text" -> "hi")), ("c2", "slow", ujson.Obj())), summarise)
    val l     = ToolLoop.build("assistant", "v1", model, Seq(echo, slow), policy).value
    val store = InMemoryCheckpointer()

    val handle = GraphRuntime(store).start(thread, l.graph, "go", RunConfig().withRunId(RunId("run-1"))).value
    started.await(10, java.util.concurrent.TimeUnit.SECONDS) shouldBe true
    handle.cancel()
    awaitResult(handle).value.failed._2 shouldBe a[GraphError.Cancelled]

    // c1's result was committed; c2 has none
    val pending = store.latest(thread).value.get.pendingWrites
    pending.size shouldBe 1

    val (_, answer) =
      GraphRuntime(store).recover(l.graph, thread, RunConfig().withRunId(RunId("run-2"))).awaited.value.completed
    answer shouldBe "done: c1=hi | c2=slow done"
    slowRuns.get shouldBe 2
    model.calls shouldBe 2
  }

  "Messages" should "apply operations to the current history and refuse impossible ones" in {
    val user = StoredMessage("u", UserMessage("hi"))
    val assistant = StoredMessage(
      "a",
      AssistantMessage(None, Seq(ToolCall("t1", "x", ujson.Obj()), ToolCall("t2", "y", ujson.Obj())))
    )
    def apply(history: Vector[StoredMessage], update: MessageUpdate) = Messages.key.applyUpdate(history, update)

    apply(Vector(user), MessageUpdate.Append(user)).left.value.message should include("already exists")
    apply(
      Vector(user),
      MessageUpdate.Replace("u", user.copy(message = UserMessage("hello")))
    ).value.head.message shouldBe
      UserMessage("hello")
    apply(Vector(user), MessageUpdate.Replace("zz", user)).left.value.message should include("no message 'zz'")
    apply(Vector(user, assistant), MessageUpdate.RemoveThrough("u")).value shouldBe Vector(assistant)

    // two edits to one message compose
    val once  = apply(Vector(user, assistant), MessageUpdate.EditToolCall("a", "t1", ujson.Obj("n" -> 1))).value
    val twice = apply(once, MessageUpdate.EditToolCall("a", "t2", ujson.Obj("n" -> 2))).value
    twice(1).message.asInstanceOf[AssistantMessage].toolCalls.map(_.arguments) shouldBe Seq(
      ujson.Obj("n" -> 1),
      ujson.Obj("n" -> 2)
    )
    apply(Vector(user), MessageUpdate.EditToolCall("u", "t1", ujson.Obj())).left.value.message should include(
      "no tool call 't1'"
    )
    upickle.default.read[StoredMessage](upickle.default.write(assistant)) shouldBe assistant
  }

  "ToolLoop.results" should "refuse a second result for the same call" in {
    val result = ToolResult("a", "t1", "ok", isError = false)
    ToolLoop.results.applyUpdate(Vector(result), result.copy(content = "again")).left.value.message should
      include("already has a result")
  }

  "LoopTool.fromToolFunction" should "run a core tool, mapping its errors to failed outcomes" in {
    final case class Echoed(message: String) derives ReadWriter
    val schema = Schema.`object`[Map[String, Any]]("Echo").withRequiredField("message", Schema.string("Message"))
    val tool = LoopTool.fromToolFunction(
      ToolBuilder[Map[String, Any], Echoed]("echo", "Echoes", schema)
        .withHandler(extractor => extractor.getString("message").map(Echoed(_)))
        .buildSafe()
        .value
    )
    tool.name shouldBe "echo"
    tool.execute(ToolCall("1", "echo", ujson.Obj("message" -> "hi")), approved = false) shouldBe
      ToolOutcome.Completed("""{"message":"hi"}""")
    tool.execute(ToolCall("2", "echo", ujson.Obj()), approved = false) shouldBe a[ToolOutcome.Failed]
  }
}
