package org.llm4s.agent.graph.toolloop

import org.llm4s.agent.graph.*
import org.llm4s.agent.graph.GraphTestSupport.*
import org.llm4s.agent.graph.middleware.{ AgentMiddleware, MiddlewareId, ModelRequest, ToolCallRequest }
import org.llm4s.agent.graph.tool.*
import org.llm4s.error.{ CancelledError, ValidationError }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.*
import org.llm4s.toolapi.{ Schema, SchemaDefinition, ToolBuilder }
import org.llm4s.types.Result
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.ReadWriter

import java.util.concurrent.{ ConcurrentHashMap, CopyOnWriteArrayList }
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*

object ToolLoopFixtures {
  final case class Lookup(q: String) derives ReadWriter
  final case class Echo(text: String) derives ReadWriter
  final case class Deploy(env: String) derives ReadWriter
  final case class Other(x: String) derives ReadWriter
  final case class Confirm(prompt: String) derives ReadWriter
  final case class Reply(ok: Boolean) derives ReadWriter
  final case class Find(q: String, limit: Int = 10) derives ReadWriter
}

class ToolLoopSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues {
  import ToolLoopFixtures.*

  private val thread = ThreadId("conversation-1")

  /** A model that plays `turns` in order and records every history and tool set it was given. */
  final private class ScriptedModel(turns: (Vector[Message] => AssistantMessage)*) extends ModelStep {
    val seen      = new CopyOnWriteArrayList[Vector[Message]]()
    val toolNames = new CopyOnWriteArrayList[Vector[String]]()
    def next(messages: Vector[Message], tools: ToolSet): Result[AssistantMessage] = {
      val turn = seen.size
      seen.add(messages)
      toolNames.add(tools.tools.map(_.spec.name))
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

  /** An object schema with one required string field per name. */
  private def strings[A](description: String, fields: String*): SchemaDefinition[A] =
    fields.foldLeft(Schema.`object`[A](description))((s, f) => s.withRequiredField(f, Schema.string(f)))

  private def tool[A: ReadWriter](name: String, fields: String*)(run: (A, ToolContext) => ToolOutcome): AgentTool[A] =
    AgentTool(AgentToolSpec[A](name, s"The $name tool", strings[A](name, fields*)))(run)

  /** A tool taking no arguments. */
  private def bare(name: String)(run: ToolContext => ToolOutcome): AgentTool[ujson.Value] =
    tool[ujson.Value](name)((_, context) => run(context))

  private def text(s: String): ToolOutcome = ToolOutcome.Success(ujson.Str(s))

  final private class Tools {
    val deploys = new CopyOnWriteArrayList[String]()
    val lookup  = tool[Lookup]("lookup", "q")((a, _) => text(s"found ${a.q}"))
    val echo    = tool[Echo]("echo", "text")((a, _) => text(a.text))
    val deploy = tool[Deploy]("deploy", "env") { (a, context) =>
      if !context.approved then ToolOutcome.NeedsApproval("deploys are irreversible")
      else
        deploys.add(a.env)
        text(s"deployed to ${a.env}")
    }
    val pesky     = bare("pesky")(_ => ToolOutcome.NeedsApproval("always asks"))
    val broken    = bare("broken")(_ => throw new IllegalStateException("boom"))
    val failing   = bare("failing")(_ => ToolOutcome.Error("upstream said no"))
    val forbidden = bare("forbidden")(_ => text("never runs"))
    val all       = Seq(lookup, echo, deploy, pesky, broken, failing, forbidden)
  }

  /** A middleware whose tool wrapper is `wrap`. */
  private def wrapper(name: String)(
    wrap: (ToolCallRequest, ToolContext, () => ToolOutcome) => ToolOutcome
  ): AgentMiddleware =
    new AgentMiddleware {
      val id: MiddlewareId = MiddlewareId(name)
      override def wrapToolCall(request: ToolCallRequest, context: ToolContext)(next: () => ToolOutcome): ToolOutcome =
        wrap(request, context, next)
    }

  /** A policy middleware: lookups need approval, `forbidden` is denied, everything else passes. */
  private val policy: AgentMiddleware = wrapper("policy") { (request, context, next) =>
    request.call.name match {
      case "lookup"    => if context.approved then next() else ToolOutcome.NeedsApproval("lookups cost money")
      case "forbidden" => ToolOutcome.Error("Denied: never allowed")
      case _           => next()
    }
  }

  /** Logs each invocation of the chain as `<call id>:<approved>`, then passes through. */
  private def recorder(name: String, log: CopyOnWriteArrayList[String]): AgentMiddleware =
    wrapper(name) { (request, context, next) =>
      log.add(s"${request.call.id}:${context.approved}")
      next()
    }

  private def set(tools: AgentTool[?]*): ToolSet = ToolSet.of(tools*).value

  private def loop(model: ModelStep, tools: Tools = Tools()) =
    ToolLoop.build("assistant", "v1", model, set(tools.all*), Seq(policy)).value

  private def threeCalls = ScriptedModel(
    calls(
      ("c1", "lookup", ujson.Obj("q" -> "x")),
      ("c2", "deploy", ujson.Obj("env" -> "prod")),
      ("c3", "echo", ujson.Obj("text" -> "hi"))
    ),
    summarise
  )

  private def messagesOf(state: ThreadState) = state.get(Messages.key).value.map(_.message)

  /** The error each call's result carries, from the model's second turn. */
  private def errors(model: ScriptedModel): Vector[(String, String)] =
    model.seen.get(1).collect { case t: ToolMessage => t.toolCallId -> ujson.read(t.content)("error").str }

  /** The run's failure, unwrapped to the `ToolFailed` its call-tool task failed with. */
  private def toolFailure(result: RunResult[?]): GraphError.ToolFailed =
    result.failed._2 match {
      case GraphError.NodeFailed(_, _, cause: GraphError.ToolFailed) => cause
      case other                                                     => fail(s"not a tool failure: $other")
    }

  "The tool loop" should "record a blank string result in its quoted JSON form, keeping the conversation valid" in {
    val blank = tool[Echo]("blank", "text")((a, _) => text(a.text))
    val model = ScriptedModel(
      calls(("c1", "blank", ujson.Obj("text" -> "")), ("c2", "blank", ujson.Obj("text" -> "  "))),
      summarise
    )
    val l = ToolLoop.build("assistant", "v1", model, set(blank), Seq(policy)).value
    runInMemory(l.graph, "go")
    model.calls shouldBe 2
    model.seen.get(1).collect { case t: ToolMessage => t.content } shouldBe Vector("\"\"", "\"  \"")
  }

  it should "suspend middleware- and tool-raised approvals independently, behind the batch barrier" in {
    val model = threeCalls
    val tools = Tools()
    val log   = new CopyOnWriteArrayList[String]()
    val l     = ToolLoop.build("assistant", "v1", model, set(tools.all*), Seq(recorder("outer", log), policy)).value

    val first    = runInMemory(l.graph, "go").suspended
    val requests = l.requests(first).value
    requests.map((_, r) => (r.call.id, r.source)) shouldBe Vector(
      "c1" -> ApprovalSource.Middleware(MiddlewareId("policy")),
      "c2" -> ApprovalSource.Tool
    )
    l.questions(first).value shouldBe empty
    first.state.get(ToolLoop.results).value.map(_.toolCallId) shouldBe Vector("c3")
    messagesOf(first.state).map(_.role) shouldBe Vector(MessageRole.User, MessageRole.Assistant)
    model.calls shouldBe 1

    // answer only the middleware approval: the model stays behind the barrier
    val (policyId, _) = requests.head
    log.clear()
    val second =
      drive(l.graph, l.graph.resume(first.execution, l.answers(policyId -> ApprovalDecision.Approve)).value).suspended
    // Approve ran the whole chain again, from the outermost wrapper, as approved; the policy passed it
    log.asScala.toVector shouldBe Vector("c1:true")
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
    // every model call was offered the loop's tools
    model.toolNames.asScala.toVector.distinct shouldBe Vector(tools.all.map(_.spec.name).toVector)
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

    errors(model) shouldBe Vector(
      "d1" -> "Denied: never allowed",
      "d2" -> "Unknown tool 'nosuch'",
      "d3" -> "Tool 'broken' failed: boom",
      "d4" -> "upstream said no",
      "d5" -> "Rejected: too expensive",
      "d6" -> "Tool 'pesky' asked for approval again: always asks"
    )
    Message.validateConversation(messagesOf(state).toList).value shouldBe (())
  }

  it should "refuse an edit the middleware denies, without running the tool" in {
    val model    = ScriptedModel(calls(("e1", "lookup", ujson.Obj("q" -> "x"))), summarise)
    val executed = new AtomicInteger()
    val counted = tool[Lookup]("lookup", "q") { (a, _) =>
      executed.incrementAndGet(); text(a.q)
    }
    val strict = wrapper("strict") { (request, context, next) =>
      if request.call.arguments("q").str == "secret" then ToolOutcome.Error("Denied: secret")
      else if context.approved then next()
      else ToolOutcome.NeedsApproval("check")
    }
    val l                 = ToolLoop.build("assistant", "v1", model, set(counted), Seq(strict)).value
    val first             = runInMemory(l.graph, "go").suspended
    val Vector((id, req)) = l.requests(first).value
    req.source shouldBe ApprovalSource.Middleware(MiddlewareId("strict"))
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
    executed.get shouldBe 0
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
      .recover(thread, again.graph, RunConfig().withRunId(RunId("run-x")))
      .awaited
      .left
      .value shouldBe a[GraphError.PendingInterrupts]
    GraphRuntime(store)
      .resume(thread, again.graph, Map(InterruptId("nope") -> ujson.Null), RunConfig().withRunId(RunId("run-x")))
      .awaited
      .left
      .value shouldBe
      a[GraphError.InvalidResume]
    store.latest(thread).value.map(_.checkpoint.runId) shouldBe Some("run-1")

    val second = GraphRuntime(store)
      .resume(
        thread,
        again.graph,
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
        thread,
        last.graph,
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
        thread,
        last.graph,
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
      .resume(thread, l.graph, l.answers(ids("c1") -> ApprovalDecision.Approve), RunConfig().withRunId(RunId("run-2")))
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
      .resume(thread, l.graph, l.answers(ids("c2") -> ApprovalDecision.Approve), RunConfig().withRunId(RunId("run-3")))
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
    val counting = tool[Echo]("echo", "text") { (a, _) =>
      echoes.incrementAndGet(); text(a.text)
    }
    // the approved lookup throws, two runs after the echo's result was committed
    val flaky = tool[Lookup]("lookup", "q") { (a, context) =>
      if context.approved then throw new java.io.IOException("connection reset")
      text(s"found ${a.q}")
    }
    val l     = ToolLoop.build("assistant", "v1", model, set(flaky, counting, tools.deploy), Seq(policy)).value
    val store = InMemoryCheckpointer()
    val first =
      GraphRuntime(store).start(thread, l.graph, "go", RunConfig().withRunId(RunId("run-1"))).awaited.value.suspended
    val ids     = l.requests(first).value.map((id, r) => r.call.id -> id).toMap
    val answers = l.answers(ids("c1") -> ApprovalDecision.Approve, ids("c2") -> ApprovalDecision.Approve)
    // a thrown tool is a tool-level failure, not a failed run: it becomes that call's error result
    GraphRuntime(store)
      .resume(thread, l.graph, answers, RunConfig().withRunId(RunId("run-2")))
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
    val echo = tool[Echo]("echo", "text") { (a, _) =>
      echoThread.put(Thread.currentThread())
      text(a.text)
    }
    val slow = bare("slow") { _ =>
      if slowRuns.incrementAndGet() == 1 then
        echoThread.take().join()
        started.countDown()
        Thread.sleep(60_000) // interrupted: InterruptedException propagates out of the tool
      text("slow done")
    }
    val model = ScriptedModel(calls(("c1", "echo", ujson.Obj("text" -> "hi")), ("c2", "slow", ujson.Obj())), summarise)
    val l     = ToolLoop.build("assistant", "v1", model, set(echo, slow), Seq(policy)).value
    val store = InMemoryCheckpointer()

    val handle = GraphRuntime(store).start(thread, l.graph, "go", RunConfig().withRunId(RunId("run-1"))).value
    started.await(10, java.util.concurrent.TimeUnit.SECONDS) shouldBe true
    handle.cancel()
    awaitResult(handle).value.failed._2 shouldBe a[GraphError.Cancelled]

    // c1's result was committed; c2 has none
    val pending = store.latest(thread).value.get.pendingWrites
    pending.size shouldBe 1

    val (_, answer) =
      GraphRuntime(store).recover(thread, l.graph, RunConfig().withRunId(RunId("run-2"))).awaited.value.completed
    answer shouldBe "done: c1=hi | c2=slow done"
    slowRuns.get shouldBe 2
    model.calls shouldBe 2
  }

  // ---- the call pipeline (#1278) ----

  it should "check arguments before any middleware or the tool, reporting every violation" in {
    val decided  = new AtomicInteger()
    val executed = new AtomicInteger()
    val counted = tool[Lookup]("lookup", "q") { (a, _) =>
      executed.incrementAndGet(); text(a.q)
    }
    val counting = wrapper("counting") { (_, _, next) =>
      decided.incrementAndGet(); next()
    }
    val model = ScriptedModel(
      calls(
        ("v1", "lookup", ujson.Obj("q" -> 5)),
        ("v2", "lookup", ujson.Obj("extra" -> true)),
        ("v3", "lookup", ujson.Str("hi"))
      ),
      summarise
    )
    val l = ToolLoop.build("assistant", "v1", model, set(counted), Seq(counting)).value
    runInMemory(l.graph, "go").completed
    errors(model) shouldBe Vector(
      "v1" -> "Invalid arguments for 'lookup': $.q: expected string, got integer",
      "v2" -> "Invalid arguments for 'lookup': $.q: required property missing; $.extra: property not allowed",
      "v3" -> "Invalid arguments for 'lookup': $: expected object, got string"
    )
    decided.get shouldBe 0
    executed.get shouldBe 0
  }

  it should "report arguments that do not decode, or fail the tool's own check, without running it" in {
    val executed = new AtomicInteger()
    // the schema declares `q`, the codec reads `x`: arguments that validate but do not decode
    val mismatched = AgentTool(AgentToolSpec[Other]("mismatched", "Mismatched", strings[Other]("m", "q"))) { (_, _) =>
      executed.incrementAndGet(); text("ran")
    }
    val checked = AgentTool(
      AgentToolSpec[Lookup]("checked", "Checked", strings[Lookup]("c", "q"))
        .withValidation(a => Either.cond(a.q.nonEmpty, (), ValidationError("q", "must not be empty")))
    ) { (_, _) =>
      executed.incrementAndGet(); text("ran")
    }
    val model = ScriptedModel(
      calls(("m1", "mismatched", ujson.Obj("q" -> "x")), ("k1", "checked", ujson.Obj("q" -> ""))),
      summarise
    )
    val l = ToolLoop.build("assistant", "v1", model, set(mismatched, checked)).value
    runInMemory(l.graph, "go").completed
    val results = errors(model).toMap
    results("m1") shouldBe "Invalid arguments for 'mismatched': $: missing keys in dictionary: x"
    results("k1") shouldBe "Invalid arguments for 'checked': Invalid q: must not be empty"
    executed.get shouldBe 0
  }

  it should "commit a tool's update to a key it declares" in {
    val hits = StateKey.replace[Int]("hits", 0)
    val counter = AgentTool(AgentToolSpec[ujson.Value]("count", "Counts", strings[ujson.Value]("c")), Set(hits)) {
      (_, context) =>
        val seen = context.state.get(hits).getOrElse(0)
        ToolOutcome.Success(ujson.Obj("hits" -> (seen + 1)), StateUpdate.update(hits, seen + 1))
    }
    val model           = ScriptedModel(calls(("h1", "count", ujson.Obj())), summarise)
    val l               = ToolLoop.build("assistant", "v1", model, set(counter)).value
    val (state, answer) = runInMemory(l.graph, "go").completed
    answer shouldBe """done: h1={"hits":1}"""
    state.get(hits).value shouldBe 1
  }

  it should "fail the run when a tool updates a key it does not declare" in {
    val hits   = StateKey.replace[Int]("hits", 0)
    val sneaky = bare("sneaky")(_ => ToolOutcome.Success(ujson.Str("ok"), StateUpdate.update(hits, 1)))
    val honest =
      AgentTool(AgentToolSpec[ujson.Value]("honest", "Honest", strings[ujson.Value]("h")), Set(hits))((_, _) =>
        text("ok")
      )
    val model   = ScriptedModel(calls(("s1", "sneaky", ujson.Obj())), summarise)
    val l       = ToolLoop.build("assistant", "v1", model, set(sneaky, honest)).value
    val failure = toolFailure(runInMemory(l.graph, "go"))
    (failure.tool, failure.toolCallId) shouldBe ("sneaky" -> "s1")
    failure.message should include("hits")
  }

  it should "fail the run on Fatal, and recover re-runs only that call" in {
    val runs = new ConcurrentHashMap[String, AtomicInteger]()
    def count(context: ToolContext): Int =
      runs.computeIfAbsent(context.toolCallId, _ => new AtomicInteger()).incrementAndGet()
    val flaky = bare("flaky") { context =>
      if count(context) == 1 then ToolOutcome.Fatal(ValidationError("upstream", "down")) else text("ok")
    }
    val echo = tool[Echo]("echo", "text") { (a, context) =>
      count(context); text(a.text)
    }
    val model = ScriptedModel(calls(("f1", "flaky", ujson.Obj()), ("f2", "echo", ujson.Obj("text" -> "hi"))), summarise)
    val l     = ToolLoop.build("assistant", "v1", model, set(flaky, echo)).value
    val store = InMemoryCheckpointer()

    val failed  = GraphRuntime(store).start(thread, l.graph, "go", RunConfig().withRunId(RunId("run-1"))).awaited.value
    val failure = toolFailure(failed)
    (failure.tool, failure.toolCallId) shouldBe ("flaky" -> "f1")
    failure.cause.message should include("down")
    store.latest(thread).value.map(_.checkpoint.status) shouldBe Some(CheckpointStatus.Running)

    val (_, answer) =
      GraphRuntime(store).recover(thread, l.graph, RunConfig().withRunId(RunId("run-2"))).awaited.value.completed
    answer shouldBe "done: f1=ok | f2=hi"
    runs.asScala.view.mapValues(_.get).toMap shouldBe Map("f1" -> 2, "f2" -> 1)
  }

  /** Asks to confirm, then to confirm again, then deploys; counts its resumes. */
  final private class Confirming
      extends AgentTool.Asking[Deploy, Confirm, Reply](
        AgentToolSpec[Deploy]("confirm", "Deploys after two confirmations", strings[Deploy]("d", "env"))
      ) {
    val resumes                                                  = new AtomicInteger()
    def execute(args: Deploy, context: ToolContext): ToolOutcome = ask(Confirm(s"deploy to ${args.env}?"))
    def resume(args: Deploy, question: Confirm, answer: Reply, context: ToolContext): ToolOutcome =
      resumes.incrementAndGet()
      if !answer.ok then ToolOutcome.Error("not confirmed")
      else if question.prompt.startsWith("deploy") then ask(Confirm("really?"))
      else text(s"deployed to ${args.env}")
  }

  it should "suspend on a typed question, and resume the tool with the answer, twice" in {
    val confirming = Confirming()
    val tools      = Tools()
    val model = ScriptedModel(
      calls(("a1", "confirm", ujson.Obj("env" -> "prod")), ("a2", "echo", ujson.Obj("text" -> "hi"))),
      summarise
    )
    val l = ToolLoop.build("assistant", "v1", model, set(confirming, tools.echo), Seq(policy)).value

    val first = runInMemory(l.graph, "go").suspended
    l.requests(first).value shouldBe empty
    val Vector((q1, request)) = l.questions(first).value
    request.call.id shouldBe "a1"
    ToolLoop.question[Confirm](request).value shouldBe Confirm("deploy to prod?")
    first.interrupts.map(_.resumeNode) shouldBe Vector(NodeId("ask/confirm"))

    val second = drive(l.graph, l.graph.resume(first.execution, Map(l.answer(q1, Reply(true)))).value).suspended
    val Vector((q2, again)) = l.questions(second).value
    ToolLoop.question[Confirm](again).value shouldBe Confirm("really?")
    ToolLoop.question[Reply](again).left.value shouldBe a[org.llm4s.error.LLMError]
    model.calls shouldBe 1

    val (_, answer) = drive(l.graph, l.graph.resume(second.execution, Map(l.answer(q2, Reply(true)))).value).completed
    answer shouldBe "done: a1=deployed to prod | a2=hi"
    confirming.resumes.get shouldBe 2
  }

  it should "answer approvals and questions in one resume" in {
    val model = ScriptedModel(
      calls(("a1", "confirm", ujson.Obj("env" -> "prod")), ("a2", "lookup", ujson.Obj("q" -> "x"))),
      summarise
    )
    val l           = ToolLoop.build("assistant", "v1", model, set(Confirming(), Tools().lookup), Seq(policy)).value
    val first       = runInMemory(l.graph, "go").suspended
    val approval    = l.requests(first).value.head._1
    val question    = l.questions(first).value.head._1
    val answers     = l.answers(approval -> ApprovalDecision.Approve) + l.answer(question, Reply(false))
    val (_, answer) = drive(l.graph, l.graph.resume(first.execution, answers).value).completed
    answer shouldBe """done: a1={"error":"not confirmed"} | a2=found x"""
  }

  it should "keep a call's approval across its question, so resume does not ask for approval again" in {
    val seen = new CopyOnWriteArrayList[Boolean]()
    val gated = new AgentTool.Asking[Deploy, Confirm, Reply](
      AgentToolSpec[Deploy]("gated", "Approved, then asks", strings[Deploy]("g", "env"))
    ) {
      def execute(args: Deploy, context: ToolContext): ToolOutcome =
        if !context.approved then ToolOutcome.NeedsApproval("deploys are irreversible")
        else ask(Confirm(s"deploy to ${args.env}?"))
      def resume(args: Deploy, question: Confirm, answer: Reply, context: ToolContext): ToolOutcome =
        seen.add(context.approved)
        if !context.approved then ToolOutcome.NeedsApproval("lost the approval")
        else text(s"deployed to ${args.env}")
    }
    val model    = ScriptedModel(calls(("g1", "gated", ujson.Obj("env" -> "prod"))), summarise)
    val l        = ToolLoop.build("assistant", "v1", model, set(gated)).value
    val first    = runInMemory(l.graph, "go").suspended
    val approval = l.requests(first).value.head._1
    val asked =
      drive(l.graph, l.graph.resume(first.execution, l.answers(approval -> ApprovalDecision.Approve)).value).suspended
    l.requests(asked).value shouldBe empty
    val Vector((question, request)) = l.questions(asked).value
    request.approved shouldBe true
    val (_, answer) =
      drive(l.graph, l.graph.resume(asked.execution, Map(l.answer(question, Reply(true)))).value).completed
    answer shouldBe "done: g1=deployed to prod"
    seen.asScala.toVector shouldBe Vector(true)
  }

  it should "turn an answer of the wrong type into an error result, without resuming the tool" in {
    val confirming = Confirming()
    val model      = ScriptedModel(calls(("a1", "confirm", ujson.Obj("env" -> "prod"))), summarise)
    val l          = ToolLoop.build("assistant", "v1", model, set(confirming)).value
    val first      = runInMemory(l.graph, "go").suspended
    val (id, _)    = l.questions(first).value.head
    val (_, answer) =
      drive(l.graph, l.graph.resume(first.execution, Map(id -> ujson.Num(42))).value).completed
    answer shouldBe """done: a1={"error":"Invalid answer for 'confirm': $: expected dictionary got float64"}"""
    confirming.resumes.get shouldBe 0
  }

  it should "refuse an approval asked for after a question, which would lose the answer" in {
    val asksTwice = new AgentTool.Asking[ujson.Value, Confirm, Reply](
      AgentToolSpec[ujson.Value]("asks_twice", "Asks, then wants approval", strings[ujson.Value]("t"))
    ) {
      def execute(args: ujson.Value, context: ToolContext): ToolOutcome = ask(Confirm("sure?"))
      def resume(args: ujson.Value, question: Confirm, answer: Reply, context: ToolContext): ToolOutcome =
        ToolOutcome.NeedsApproval("one more check")
    }
    val model   = ScriptedModel(calls(("n1", "asks_twice", ujson.Obj())), summarise)
    val l       = ToolLoop.build("assistant", "v1", model, set(asksTwice)).value
    val first   = runInMemory(l.graph, "go").suspended
    val (id, _) = l.questions(first).value.head
    val (_, answer) =
      drive(l.graph, l.graph.resume(first.execution, Map(l.answer(id, Reply(true)))).value).completed
    answer shouldBe
      """done: n1={"error":"Tool 'asks_twice' asked for approval after a question: one more check"}"""
  }

  it should "refuse a tool that declares a key the loop owns" in {
    val results = AgentTool(
      AgentToolSpec[ujson.Value]("results_writer", "Writes results", strings[ujson.Value]("r")),
      Set(ToolLoop.results)
    )((_, _) => text("x"))
    val history = AgentTool(
      AgentToolSpec[ujson.Value]("history_writer", "Writes messages", strings[ujson.Value]("h")),
      Set(Messages.key)
    )((_, _) => text("x"))
    val refused = ToolLoop.build("assistant", "v1", ScriptedModel(summarise), set(results, history, Tools().echo))
    refused.left.value shouldBe a[ValidationError]
    refused.left.value.message should (include("results_writer").and(include("history_writer")))
    (refused.left.value.message should not).include("echo")
  }

  it should "refuse a call whose validator throws, without running the tool" in {
    val executed = new AtomicInteger()
    val counted = tool[Lookup]("lookup", "q") { (a, _) =>
      executed.incrementAndGet(); text(a.q)
    }
    val throwing = new ToolArgumentValidator {
      def unsupported(schema: ujson.Value): Vector[String] = Vector.empty
      def validate(schema: ujson.Value, arguments: ujson.Value): Vector[String] =
        throw new IllegalStateException("validator broke")
    }
    val model = ScriptedModel(calls(("v1", "lookup", ujson.Obj("q" -> "x"))), summarise)
    val l     = ToolLoop.build("assistant", "v1", model, ToolSet.of(throwing, counted).value).value
    runInMemory(l.graph, "go").completed
    errors(model) shouldBe Vector("v1" -> "Invalid arguments for 'lookup': validator broke")
    executed.get shouldBe 0
  }

  it should "cancel, not fail, a call that throws a wrapped interrupt or returns Fatal(CancelledError)" in {
    val wrapped = bare("wrapped")(_ => throw new RuntimeException("wrapped", new InterruptedException("stop")))
    val fatal   = bare("fatal")(_ => ToolOutcome.Fatal(org.llm4s.error.CancelledError("upstream call")))
    Seq("wrapped", "fatal").foreach { name =>
      val model = ScriptedModel(calls(("x1", "echo", ujson.Obj("text" -> "hi")), ("x2", name, ujson.Obj())), summarise)
      val l     = ToolLoop.build("assistant", "v1", model, set(wrapped, fatal, Tools().echo)).value
      val store = InMemoryCheckpointer()
      val ended = GraphRuntime(store).start(thread, l.graph, "go", RunConfig().withRunId(RunId("run-1"))).awaited.value
      withClue(name)(ended.failed._2 shouldBe a[GraphError.Cancelled])
      // the cancelled call recorded nothing: no result, so recover would run it again
      store.latest(thread).value.get.pendingWrites.size should be <= 1
      (store.latest(thread).value.get.pendingWrites.map(_.toString).mkString should not).include("x2")
    }
  }

  it should "run a call that omits an optional field, as core's non-strict clients allow" in {
    val schema = Schema
      .`object`[Find]("Find")
      .withRequiredField("q", Schema.string("q"))
      .withOptionalField("limit", Schema.integer("limit"))
    val find = AgentTool(AgentToolSpec[Find]("find", "Finds", schema))((a, _) => text(s"${a.q}/${a.limit}"))
    val model = ScriptedModel(
      calls(("o1", "find", ujson.Obj("q" -> "x")), ("o2", "find", ujson.Obj("q" -> "y", "limit" -> 5))),
      summarise
    )
    val l = ToolLoop.build("assistant", "v1", model, set(find)).value
    runInMemory(l.graph, "go").completed._2 shouldBe "done: o1=x/10 | o2=y/5"
  }

  it should "cancel, not refuse, a call whose validator or validateDecoded throws a cancellation" in {
    val executed             = new AtomicInteger()
    def interrupt(): Nothing = throw new RuntimeException("wrapped", new InterruptedException("stop"))
    val validating = new ToolArgumentValidator {
      def unsupported(schema: ujson.Value): Vector[String] = Vector.empty
      def validate(schema: ujson.Value, arguments: ujson.Value): Vector[String] =
        if arguments.obj.contains("cancel") then interrupt() else Vector.empty
    }
    val checking = AgentTool(
      AgentToolSpec[ujson.Value]("checking", "Checks", strings[ujson.Value]("c")).withValidation(_ => interrupt())
    ) { (_, _) =>
      executed.incrementAndGet(); text("ran")
    }
    val validated = AgentTool(AgentToolSpec[ujson.Value]("validated", "Validated", strings[ujson.Value]("v"))) {
      (_, _) =>
        executed.incrementAndGet(); text("ran")
    }
    Seq(("validated", ujson.Obj("cancel" -> true)), ("checking", ujson.Obj())).foreach { (name, args) =>
      val model = ScriptedModel(calls(("x1", "echo", ujson.Obj("text" -> "hi")), ("x2", name, args)), summarise)
      val tools = ToolSet.of(validating, validated, checking, Tools().echo).value
      val l     = ToolLoop.build("assistant", "v1", model, tools).value
      val store = InMemoryCheckpointer()
      val ended = GraphRuntime(store).start(thread, l.graph, "go", RunConfig().withRunId(RunId("run-1"))).awaited.value
      withClue(name)(ended.failed._2 shouldBe a[GraphError.Cancelled])
      // the cancelled call recorded nothing, and its tool never ran
      (store.latest(thread).value.get.pendingWrites.map(_.toString).mkString should not).include("x2")
      executed.get shouldBe 0
    }
  }

  it should "turn a resume that throws into an error result" in {
    val throwing = new AgentTool.Asking[ujson.Value, Confirm, Reply](
      AgentToolSpec[ujson.Value]("throwing", "Throws on resume", strings[ujson.Value]("t"))
    ) {
      def execute(args: ujson.Value, context: ToolContext): ToolOutcome = ask(Confirm("sure?"))
      def resume(args: ujson.Value, question: Confirm, answer: Reply, context: ToolContext): ToolOutcome =
        throw new IllegalStateException("resume broke")
    }
    val model   = ScriptedModel(calls(("r1", "throwing", ujson.Obj())), summarise)
    val l       = ToolLoop.build("assistant", "v1", model, set(throwing)).value
    val first   = runInMemory(l.graph, "go").suspended
    val (id, _) = l.questions(first).value.head
    val resumed = drive(l.graph, l.graph.resume(first.execution, Map(l.answer(id, Reply(true)))).value)
    resumed.completed._2 shouldBe """done: r1={"error":"Tool 'throwing' failed: resume broke"}"""
  }

  it should "fail the run when a tool asks a question it does not declare, or one of the wrong type" in {
    val undeclared = bare("undeclared")(_ => ToolOutcome.Ask("what now?"))
    val mistyped = new AgentTool.Asking[ujson.Value, Confirm, Reply](
      AgentToolSpec[ujson.Value]("mistyped", "Asks the wrong type", strings[ujson.Value]("t"))
    ) {
      def execute(args: ujson.Value, context: ToolContext): ToolOutcome = ToolOutcome.Ask(42)
      def resume(args: ujson.Value, question: Confirm, answer: Reply, context: ToolContext): ToolOutcome = text("no")
    }
    val first = ScriptedModel(calls(("u1", "undeclared", ujson.Obj())), summarise)
    val undeclaredFailure =
      toolFailure(runInMemory(ToolLoop.build("assistant", "v1", first, set(undeclared)).value.graph, "go"))
    (undeclaredFailure.tool, undeclaredFailure.toolCallId) shouldBe ("undeclared" -> "u1")
    undeclaredFailure.message should include("asked a question it does not declare")

    val second = ScriptedModel(calls(("w1", "mistyped", ujson.Obj())), summarise)
    val mistypedFailure =
      toolFailure(runInMemory(ToolLoop.build("assistant", "v1", second, set(mistyped)).value.graph, "go"))
    (mistypedFailure.tool, mistypedFailure.toolCallId) shouldBe ("mistyped" -> "w1")
  }

  it should "re-validate edited arguments, refusing invalid ones without running the tool" in {
    val tools           = Tools()
    val model           = ScriptedModel(calls(("e1", "deploy", ujson.Obj("env" -> "prod"))), summarise)
    val l               = ToolLoop.build("assistant", "v1", model, set(tools.all*), Seq(policy)).value
    val first           = runInMemory(l.graph, "go").suspended
    val id              = l.requests(first).value.head._1
    val edit            = ApprovalDecision.Edit(ujson.Obj("env" -> 7))
    val (state, answer) = drive(l.graph, l.graph.resume(first.execution, l.answers(id -> edit)).value).completed
    answer shouldBe """done: e1={"error":"Invalid arguments for 'deploy': $.env: expected string, got integer"}"""
    tools.deploys.asScala shouldBe empty
    messagesOf(state).collect {
      case a: AssistantMessage if a.toolCalls.nonEmpty => a.toolCalls.head.arguments
    }.head shouldBe
      ujson.Obj("env" -> 7)
  }

  it should "run a core ToolFunction through the same pipeline" in {
    final case class Echoed(message: String) derives ReadWriter
    val schema = Schema.`object`[Map[String, Any]]("Echo").withRequiredField("message", Schema.string("Message"))
    val function = ToolBuilder[Map[String, Any], Echoed]("say", "Echoes", schema)
      .withHandler(extractor => extractor.getString("message").map(Echoed(_)))
      .buildSafe()
      .value
    val model = ScriptedModel(
      calls(("t1", "say", ujson.Obj("message" -> "hi")), ("t2", "say", ujson.Obj("message" -> 3))),
      summarise
    )
    val l = ToolLoop.build("assistant", "v1", model, set(AgentTool.fromToolFunction(function))).value
    runInMemory(l.graph, "go").completed._2 shouldBe
      """done: t1={"message":"hi"} | t2={"error":"Invalid arguments for 'say': $.message: expected string, got integer"}"""
  }

  it should "treat null arguments as {} for a tool that requires nothing, and refuse them otherwise" in {
    val seenByCore   = new CopyOnWriteArrayList[String]()
    val optionalOnly = Schema.`object`[Map[String, Any]]("Opt").withOptionalField("note", Schema.string("Note"))
    val core = ToolBuilder[Map[String, Any], String]("core_opt", "Optional", optionalOnly)
      .withHandler { extractor =>
        seenByCore.add(extractor.params.render()); Right("ok")
      }
      .buildSafe()
      .value
    val seenByTyped = new CopyOnWriteArrayList[String]()
    val typed = tool[ujson.Value]("typed_none") { (args, _) =>
      seenByTyped.add(args.render()); text("ok")
    }
    val strict = tool[Echo]("needs_text", "text")((a, _) => text(a.text))
    val model = ScriptedModel(
      calls(
        ("c1", "core_opt", ujson.Null),
        ("c2", "typed_none", ujson.Null),
        ("c3", "needs_text", ujson.Null)
      ),
      summarise
    )
    val l = ToolLoop.build("assistant", "v1", model, set(AgentTool.fromToolFunction(core), typed, strict)).value
    runInMemory(l.graph, "go").completed._2 shouldBe
      """done: c1=ok | c2=ok | c3={"error":"Invalid arguments for 'needs_text': $: expected object, got null"}"""
    seenByCore.toArray.toSeq shouldBe Seq("{}")
    seenByTyped.toArray.toSeq shouldBe Seq("{}")
  }

  // ---- tool-call middleware (#1279) ----

  it should "refuse a middleware stack that does not build" in {
    val pass = wrapper("bad id")((_, _, next) => next())
    ToolLoop.build("assistant", "v1", ScriptedModel(summarise), set(Tools().echo), Seq(pass)).left.value shouldBe
      a[ValidationError]
  }

  it should "run tool wrappers in stack order around the tool, and only after argument validation" in {
    val log = new CopyOnWriteArrayList[String]()
    def nested(name: String) = wrapper(name) { (request, _, next) =>
      log.add(s"$name:before:${request.call.id}")
      val result = next()
      log.add(s"$name:after:${request.call.id}")
      result
    }
    val echo = tool[Echo]("echo", "text") { (a, _) =>
      log.add(s"tool:${a.text}"); text(a.text)
    }
    val model = ScriptedModel(
      calls(("w1", "echo", ujson.Obj("text" -> "hi"))),
      calls(("w2", "echo", ujson.Obj("text" -> 5))),
      summarise
    )
    val l = ToolLoop.build("assistant", "v1", model, set(echo), Seq(nested("a"), nested("b"))).value
    runInMemory(l.graph, "go").completed
    log.asScala.toVector shouldBe Vector("a:before:w1", "b:before:w1", "tool:hi", "b:after:w1", "a:after:w1")
    model.seen.get(2).collect {
      case t: ToolMessage if t.toolCallId == "w2" => "w2" -> ujson.read(t.content)("error").str
    } shouldBe
      Vector("w2" -> "Invalid arguments for 'echo': $.text: expected string, got integer")
  }

  it should "record one result, and only the returned attempt's update, when a wrapper retries a tool" in {
    val attempts = StateKey[Vector[Int], Int]("attempts", Vector.empty)((seen, n) => Right(seen :+ n))
    val runs     = new AtomicInteger()
    val writer = AgentTool(AgentToolSpec[ujson.Value]("writer", "Writes", strings[ujson.Value]("w")), Set(attempts)) {
      (_, _) =>
        val n = runs.incrementAndGet()
        ToolOutcome.Success(ujson.Str(s"attempt $n"), StateUpdate.update(attempts, n))
    }
    val retry = wrapper("retry") { (_, _, next) =>
      next() match {
        case ToolOutcome.Success(ujson.Str("attempt 1"), _) => next()
        case other                                          => other
      }
    }
    val model           = ScriptedModel(calls(("r1", "writer", ujson.Obj())), summarise)
    val l               = ToolLoop.build("assistant", "v1", model, set(writer), Seq(retry)).value
    val (state, answer) = runInMemory(l.graph, "go").completed
    runs.get shouldBe 2
    answer shouldBe "done: r1=attempt 2"
    model.seen.get(1).collect { case t: ToolMessage => t.toolCallId } shouldBe Vector("r1")
    state.get(attempts).value shouldBe Vector(2)
  }

  it should "fail the run when a wrapper adds an update to a key the tool does not declare" in {
    val hits = StateKey.replace[Int]("hits", 0)
    val honest =
      AgentTool(AgentToolSpec[ujson.Value]("honest", "Honest", strings[ujson.Value]("h")), Set(hits))((_, _) =>
        text("ok")
      )
    val adding = wrapper("adding") { (_, _, next) =>
      next() match {
        case ToolOutcome.Success(content, update) =>
          ToolOutcome.Success(content, update.combine(StateUpdate.update(hits, 1)))
        case other => other
      }
    }
    val model   = ScriptedModel(calls(("s1", "echo", ujson.Obj("text" -> "hi"))), summarise)
    val l       = ToolLoop.build("assistant", "v1", model, set(Tools().echo, honest), Seq(adding)).value
    val failure = toolFailure(runInMemory(l.graph, "go"))
    (failure.tool, failure.toolCallId) shouldBe ("echo" -> "s1")
    failure.message should include("hits")
  }

  it should "refuse a second approval from a wrapper inside the one that asked first" in {
    val tools = Tools()
    // x is a well-behaved approval middleware; y asks whether or not the call is approved
    val x = wrapper("x") { (request, context, next) =>
      if request.call.name == "deploy" && !context.approved then ToolOutcome.NeedsApproval("x checks deploys")
      else next()
    }
    val y = wrapper("y") { (request, _, next) =>
      if request.call.name == "deploy" then ToolOutcome.NeedsApproval("y checks deploys") else next()
    }
    val model             = ScriptedModel(calls(("d1", "deploy", ujson.Obj("env" -> "prod"))), summarise)
    val l                 = ToolLoop.build("assistant", "v1", model, set(tools.all*), Seq(x, y)).value
    val first             = runInMemory(l.graph, "go").suspended
    val Vector((id, req)) = l.requests(first).value
    (req.source, req.reason) shouldBe (ApprovalSource.Middleware(MiddlewareId("x")) -> "x checks deploys")
    val (_, answer) =
      drive(l.graph, l.graph.resume(first.execution, l.answers(id -> ApprovalDecision.Approve)).value).completed
    answer shouldBe """done: d1={"error":"Middleware 'y' asked for approval again: y checks deploys"}"""
    tools.deploys.asScala shouldBe empty
  }

  it should "run a tool's resume after a question inside the chain, with the approval it asked with" in {
    val log = new CopyOnWriteArrayList[String]()
    val gated = new AgentTool.Asking[Deploy, Confirm, Reply](
      AgentToolSpec[Deploy]("gated", "Approved, then asks", strings[Deploy]("g", "env"))
    ) {
      def execute(args: Deploy, context: ToolContext): ToolOutcome =
        if !context.approved then ToolOutcome.NeedsApproval("deploys are irreversible")
        else ask(Confirm(s"deploy to ${args.env}?"))
      def resume(args: Deploy, question: Confirm, answer: Reply, context: ToolContext): ToolOutcome =
        text(s"deployed to ${args.env}")
    }
    val model    = ScriptedModel(calls(("g1", "gated", ujson.Obj("env" -> "prod"))), summarise)
    val l        = ToolLoop.build("assistant", "v1", model, set(gated), Seq(recorder("rec", log))).value
    val first    = runInMemory(l.graph, "go").suspended
    val approval = l.requests(first).value.head._1
    val asked =
      drive(l.graph, l.graph.resume(first.execution, l.answers(approval -> ApprovalDecision.Approve)).value).suspended
    val question = l.questions(asked).value.head._1
    val (_, answer) =
      drive(l.graph, l.graph.resume(asked.execution, Map(l.answer(question, Reply(true)))).value).completed
    answer shouldBe "done: g1=deployed to prod"
    // execute, the approved execute, then the resume - each through the wrapper
    log.asScala.toVector shouldBe Vector("g1:false", "g1:true", "g1:true")
  }

  it should "fail the run when a wrapper throws, and recover re-runs only that call" in {
    val echoes = new AtomicInteger()
    val echo = tool[Echo]("echo", "text") { (a, _) =>
      echoes.incrementAndGet(); text(a.text)
    }
    val tools = Tools()
    val model = ScriptedModel(
      calls(("c1", "echo", ujson.Obj("text" -> "hi")), ("c2", "lookup", ujson.Obj("q" -> "x"))),
      summarise
    )
    val throwing = wrapper("boom-mw") { (request, _, next) =>
      if request.call.name == "lookup" then throw new IllegalStateException("boom") else next()
    }
    val store  = InMemoryCheckpointer()
    val broken = ToolLoop.build("assistant", "v1", model, set(echo, tools.lookup), Seq(throwing)).value
    val failed =
      GraphRuntime(store).start(thread, broken.graph, "go", RunConfig().withRunId(RunId("run-1"))).awaited.value
    failed.failed._2 match {
      case GraphError.NodeFailed(node, _, GraphError.MiddlewareFailed("boom-mw", cause)) =>
        node shouldBe NodeId("call-tool")
        cause.getMessage shouldBe "boom"
      case other => fail(s"not a middleware failure: $other")
    }
    store.latest(thread).value.map(_.checkpoint.status) shouldBe Some(CheckpointStatus.Running)

    val fixed = ToolLoop
      .build("assistant", "v1", model, set(echo, tools.lookup), Seq(wrapper("boom-mw")((_, _, next) => next())))
      .value
    val (_, answer) =
      GraphRuntime(store).recover(thread, fixed.graph, RunConfig().withRunId(RunId("run-2"))).awaited.value.completed
    answer shouldBe "done: c1=hi | c2=found x"
    echoes.get shouldBe 1
  }

  it should "cancel, not fail, the run when a wrapper throws a wrapped interrupt" in {
    val interrupting = wrapper("interrupting") { (request, _, next) =>
      if request.call.name == "lookup" then throw new RuntimeException("wrapped", new InterruptedException("stop"))
      else next()
    }
    val model = ScriptedModel(
      calls(("x1", "echo", ujson.Obj("text" -> "hi")), ("x2", "lookup", ujson.Obj("q" -> "x"))),
      summarise
    )
    val tools = Tools()
    val l     = ToolLoop.build("assistant", "v1", model, set(tools.echo, tools.lookup), Seq(interrupting)).value
    val store = InMemoryCheckpointer()
    val ended = GraphRuntime(store).start(thread, l.graph, "go", RunConfig().withRunId(RunId("run-1"))).awaited.value
    ended.failed._2 shouldBe a[GraphError.Cancelled]
    store.latest(thread).value.get.pendingWrites.size should be <= 1
    (store.latest(thread).value.get.pendingWrites.map(_.toString).mkString should not).include("x2")
  }

  it should "never run a cancelled tool again, however often a wrapper retries" in {
    val runs    = new AtomicInteger()
    val retries = new AtomicInteger()
    val cancelling = bare("cancelling") { _ =>
      runs.incrementAndGet()
      throw new RuntimeException(new InterruptedException())
    }
    // retries any outcome other than Success, up to three attempts in all
    val retry = wrapper("retry") { (_, _, next) =>
      def attempt(n: Int): ToolOutcome =
        retries.incrementAndGet()
        next() match {
          case ok: ToolOutcome.Success => ok
          case other                   => if n < 3 then attempt(n + 1) else other
        }
      attempt(1)
    }
    val model = ScriptedModel(calls(("k1", "cancelling", ujson.Obj())), summarise)
    val l     = ToolLoop.build("assistant", "v1", model, set(cancelling), Seq(retry)).value
    val store = InMemoryCheckpointer()
    val ended = GraphRuntime(store).start(thread, l.graph, "go", RunConfig().withRunId(RunId("run-1"))).awaited.value
    ended.failed._2 shouldBe a[GraphError.Cancelled]
    retries.get shouldBe 3
    runs.get shouldBe 1
    (store.latest(thread).value.get.pendingWrites.map(_.toString).mkString should not).include("k1")
  }

  /** Retries any outcome other than `Success`, and any throw from `next`, up to three attempts in all. */
  private def catchAllRetry(attempts: AtomicInteger): AgentMiddleware =
    wrapper("catch-all") { (_, _, next) =>
      def attempt(n: Int): ToolOutcome =
        attempts.incrementAndGet()
        // test sources are outside scalafix; this is the catch-all a careless wrapper would write
        val outcome =
          try next()
          catch { case _: Throwable => ToolOutcome.Error("next threw") }
        outcome match {
          case ok: ToolOutcome.Success => ok
          case other                   => if n < 3 then attempt(n + 1) else other
        }
      attempt(1)
    }

  it should "never run a tool again that threw a bare InterruptedException, under a catch-all retrying wrapper" in {
    val runs     = new AtomicInteger()
    val attempts = new AtomicInteger()
    val interrupted = bare("interrupted") { _ =>
      runs.incrementAndGet()
      throw new InterruptedException("stop")
    }
    val model = ScriptedModel(calls(("b1", "interrupted", ujson.Obj())), summarise)
    val l     = ToolLoop.build("assistant", "v1", model, set(interrupted), Seq(catchAllRetry(attempts))).value
    val store = InMemoryCheckpointer()
    val ended = GraphRuntime(store).start(thread, l.graph, "go", RunConfig().withRunId(RunId("run-1"))).awaited.value
    ended.failed._2 shouldBe a[GraphError.Cancelled]
    attempts.get shouldBe 3
    runs.get shouldBe 1
    (store.latest(thread).value.get.pendingWrites.map(_.toString).mkString should not).include("b1")
  }

  it should "never run a tool again that reported its cancellation as an Error, under a wrapper that retries Errors" in {
    val runs     = new AtomicInteger()
    val attempts = new AtomicInteger()
    // a core ToolFunction cancelled mid-call: it sets the flag and returns Left, which fromToolFunction maps to Error
    val function = ToolBuilder[Map[String, Any], String]("halting", "Halts", Schema.`object`[Map[String, Any]]("Halt"))
      .withHandler { _ =>
        runs.incrementAndGet()
        Thread.currentThread().interrupt()
        Left("cancelled")
      }
      .buildSafe()
      .value
    val model = ScriptedModel(calls(("h1", "halting", ujson.Obj())), summarise)
    val l =
      ToolLoop
        .build("assistant", "v1", model, set(AgentTool.fromToolFunction(function)), Seq(catchAllRetry(attempts)))
        .value
    val store = InMemoryCheckpointer()
    val ended = GraphRuntime(store).start(thread, l.graph, "go", RunConfig().withRunId(RunId("run-1"))).awaited.value
    ended.failed._2 shouldBe a[GraphError.Cancelled]
    attempts.get shouldBe 3
    runs.get shouldBe 1
    (store.latest(thread).value.get.pendingWrites.map(_.toString).mkString should not).include("h1")
  }

  it should "never run a tool again after an inner wrapper throws a bare InterruptedException, under a retrying outer one" in {
    val runs     = new AtomicInteger()
    val attempts = new AtomicInteger()
    val counted = tool[Echo]("counted", "text") { (a, _) =>
      runs.incrementAndGet(); text(a.text)
    }
    // runs the tool, then is interrupted while it waits (a rate limiter, say)
    val waiting = wrapper("waiting") { (_, _, next) =>
      next()
      throw new InterruptedException("stop")
    }
    val model = ScriptedModel(calls(("w1", "counted", ujson.Obj("text" -> "hi"))), summarise)
    val l     = ToolLoop.build("assistant", "v1", model, set(counted), Seq(catchAllRetry(attempts), waiting)).value
    val ended = GraphRuntime(InMemoryCheckpointer())
      .start(thread, l.graph, "go", RunConfig().withRunId(RunId("run-1")))
      .awaited
      .value
    ended.failed._2 shouldBe a[GraphError.Cancelled]
    attempts.get shouldBe 3
    runs.get shouldBe 1
  }

  it should "never call a cancelled model again, under a retrying model wrapper" in {
    val retrying = middleware(
      "retrying",
      // retries a Left, and a throw from next, up to three calls in all
      model = (request, next) => {
        def attempt(n: Int): Result[AssistantMessage] = {
          val result =
            try next(request)
            catch { case _: Throwable => Left(ValidationError("model", "next threw")) }
          if result.isLeft && n < 3 then attempt(n + 1) else result
        }
        attempt(1)
      }
    )
    final class Cancelling(cancel: () => Result[AssistantMessage]) extends ModelStep {
      val calls = new AtomicInteger()
      def next(messages: Vector[Message], tools: ToolSet): Result[AssistantMessage] =
        calls.incrementAndGet()
        cancel()
    }
    val throwing = Cancelling(() => throw new InterruptedException("stop"))
    val reporting = Cancelling { () =>
      Thread.currentThread().interrupt()
      Left(org.llm4s.error.CancelledError("model"))
    }
    Seq("throws a bare InterruptedException" -> throwing, "returns a cancelled Left" -> reporting).foreach {
      (name, model) =>
        val l = ToolLoop.build("assistant", "v1", model, set(Tools().echo), Seq(retrying)).value
        val ended = GraphRuntime(InMemoryCheckpointer())
          .start(thread, l.graph, "go", RunConfig().withRunId(RunId("run-1")))
          .awaited
          .value
        withClue(name) {
          ended.failed._2 shouldBe a[GraphError.Cancelled]
          model.calls.get shouldBe 1
        }
    }
  }

  it should "refuse an approval a wrapper asks for when the tool resumes after a question" in {
    val invocations = new AtomicInteger()
    // passes the call's first run, then wants approval once the tool resumes
    val late = wrapper("late") { (_, _, next) =>
      if invocations.incrementAndGet() == 1 then next() else ToolOutcome.NeedsApproval("second thoughts")
    }
    val confirming = Confirming()
    val model      = ScriptedModel(calls(("q1", "confirm", ujson.Obj("env" -> "prod"))), summarise)
    val l          = ToolLoop.build("assistant", "v1", model, set(confirming), Seq(late)).value
    val first      = runInMemory(l.graph, "go").suspended
    val (id, _)    = l.questions(first).value.head
    val (_, answer) =
      drive(l.graph, l.graph.resume(first.execution, Map(l.answer(id, Reply(true)))).value).completed
    answer shouldBe
      """done: q1={"error":"Middleware 'late' asked for approval after a question: second thoughts"}"""
    confirming.resumes.get shouldBe 0
  }

  // ---- model wrapper, run-boundary hooks, contributed tools and keys (#1279) ----

  /** A middleware built from optional hooks; each one left out passes through. */
  private def middleware(
    name: String,
    before: String => Result[String] = Right(_),
    after: String => Result[String] = Right(_),
    model: (ModelRequest, ModelRequest => Result[AssistantMessage]) => Result[AssistantMessage] = (r, next) => next(r),
    contributes: Vector[AgentTool[?]] = Vector.empty,
    declares: Set[StateKey[?, ?]] = Set.empty,
    wrapTool: (ToolCallRequest, ToolContext, () => ToolOutcome) => ToolOutcome = (_, _, next) => next()
  ): AgentMiddleware =
    new AgentMiddleware {
      val id: MiddlewareId                                                         = MiddlewareId(name)
      override def writes: Set[StateKey[?, ?]]                                     = declares
      override def tools: Vector[AgentTool[?]]                                     = contributes
      override def beforeAgent(input: String, context: RunContext): Result[String] = before(input)
      override def afterAgent(answer: String, context: RunContext): Result[String] = after(answer)
      override def wrapModelCall(request: ModelRequest, context: RunContext)(
        next: ModelRequest => Result[AssistantMessage]
      ): Result[AssistantMessage] = model(request, next)
      override def wrapToolCall(request: ToolCallRequest, context: ToolContext)(next: () => ToolOutcome): ToolOutcome =
        wrapTool(request, context, next)
    }

  /** The run's failure, unwrapped to the error its node failed with. */
  private def nodeFailure(result: RunResult[?]): (NodeId, org.llm4s.error.LLMError) =
    result.failed._2 match {
      case GraphError.NodeFailed(node, _, cause) => node -> cause
      case other                                 => fail(s"not a node failure: $other")
    }

  it should "run beforeAgent on the input the model sees, and block the run before any model call on Left" in {
    val model      = ScriptedModel(summarise)
    val shout      = middleware("shout", before = s => Right(s.toUpperCase))
    val tag        = middleware("tag", before = s => Right(s"[$s]"))
    val l          = ToolLoop.build("assistant", "v1", model, set(Tools().echo), Seq(shout, tag)).value
    val (state, _) = runInMemory(l.graph, "go").completed
    // stack order: shout first, then tag
    model.seen.get(0).collect { case u: UserMessage => u.content } shouldBe Vector("[GO]")
    messagesOf(state).head shouldBe UserMessage("[GO]")

    val blocked       = ScriptedModel(summarise)
    val refusing      = middleware("refusing", before = _ => Left(ValidationError("input", "not allowed")))
    val refused       = ToolLoop.build("assistant", "v1", blocked, set(Tools().echo), Seq(refusing)).value
    val (kept, cause) = runInMemory(refused.graph, "go").failed
    // the guardrail's own error, with nothing stored
    cause shouldBe ValidationError("input", "not allowed")
    messagesOf(kept) shouldBe empty
    blocked.calls shouldBe 0
  }

  it should "let a model wrapper filter the offered tools, and retry the model on a Left" in {
    val tools = Tools()
    val model = ScriptedModel(calls(("m1", "echo", ujson.Obj("text" -> "hi"))), summarise)
    val filtering = middleware(
      "filtering",
      model = (request, next) =>
        ToolSet
          .of(request.tools.tools.filter(t => Set("echo", "lookup").contains(t.spec.name))*)
          .flatMap(filtered => next(request.copy(tools = filtered)))
    )
    val l = ToolLoop.build("assistant", "v1", model, set(tools.all*), Seq(filtering)).value
    runInMemory(l.graph, "go").completed._2 shouldBe "done: m1=hi"
    model.toolNames.asScala.toVector shouldBe Vector(Vector("lookup", "echo"), Vector("lookup", "echo"))

    // a model that fails its first call; the wrapper retries once
    val attempts = new AtomicInteger()
    val flaky = new ModelStep {
      def next(messages: Vector[Message], tools: ToolSet): Result[AssistantMessage] =
        if attempts.incrementAndGet() == 1 then Left(ValidationError("model", "rate limited"))
        else Right(AssistantMessage("second time lucky"))
    }
    val retrying = middleware("retrying", model = (request, next) => next(request).left.flatMap(_ => next(request)))
    val retried  = ToolLoop.build("assistant", "v1", flaky, set(tools.echo), Seq(retrying)).value
    runInMemory(retried.graph, "go").completed._2 shouldBe "second time lucky"
    attempts.get shouldBe 2
  }

  it should "run afterAgent in reverse stack order, replacing the stored answer only when it changes" in {
    val order = new CopyOnWriteArrayList[String]()
    def appending(name: String) = middleware(
      name,
      after = answer => {
        order.add(name); Right(s"$answer+$name")
      }
    )
    val model = ScriptedModel(_ => AssistantMessage("answer"))
    val l     = ToolLoop.build("assistant", "v1", model, set(Tools().echo), Seq(appending("a"), appending("b"))).value
    val (state, answer) = runInMemory(l.graph, "go").completed
    order.asScala.toVector shouldBe Vector("b", "a")
    answer shouldBe "answer+b+a"
    messagesOf(state).last shouldBe AssistantMessage("answer+b+a")
    state.get(Messages.key).value.size shouldBe 2

    val unchangedModel = ScriptedModel(_ => AssistantMessage("as is"))
    val observing      = middleware("observing", after = Right(_))
    val unchanged      = ToolLoop.build("assistant", "v1", unchangedModel, set(Tools().echo), Seq(observing)).value
    val (kept, same)   = runInMemory(unchanged.graph, "go").completed
    same shouldBe "as is"
    val stored = kept.get(Messages.key).value.last
    stored.message shouldBe AssistantMessage("as is")
    stored.id should endWith("/assistant")
  }

  it should "carry each turn's replaced answer into the next turn on the same thread" in {
    val model   = ScriptedModel(_ => AssistantMessage("first"), _ => AssistantMessage("second"))
    val ticking = middleware("ticking", after = answer => Right(s"$answer ✓"))
    val l       = ToolLoop.build("assistant", "v1", model, set(Tools().echo), Seq(ticking)).value
    val runtime = GraphRuntime(InMemoryCheckpointer())
    val (_, first) =
      runtime.start(thread, l.graph, "one", RunConfig().withRunId(RunId("run-1"))).awaited.value.completed
    first shouldBe "first ✓"

    val (state, second) =
      runtime.start(thread, l.graph, "two", RunConfig().withRunId(RunId("run-2"))).awaited.value.completed
    second shouldBe "second ✓"
    // the second model call saw turn 1's answer as afterAgent replaced it
    model.seen.get(1) shouldBe Vector(UserMessage("one"), AssistantMessage("first ✓"), UserMessage("two"))
    val history = messagesOf(state)
    history shouldBe
      Vector(UserMessage("one"), AssistantMessage("first ✓"), UserMessage("two"), AssistantMessage("second ✓"))
    history.size shouldBe 4
    Message.validateConversation(history.toList).value shouldBe (())
  }

  it should "block the run when afterAgent returns a blank answer, or a Left, keeping none of the turn" in {
    val blanking = middleware("blanking", after = _ => Right("  "))
    val l        = ToolLoop.build("assistant", "v1", ScriptedModel(summarise), set(Tools().echo), Seq(blanking)).value
    val (blankedState, cause) = runInMemory(l.graph, "go").failed
    cause shouldBe ValidationError("tool loop", "afterAgent returned a blank answer")
    messagesOf(blankedState) shouldBe empty

    val refusing = middleware("refusing", after = _ => Left(ValidationError("output", "blocked")))
    val refused  = ToolLoop.build("assistant", "v1", ScriptedModel(summarise), set(Tools().echo), Seq(refusing)).value
    val (refusedState, why) = runInMemory(refused.graph, "go").failed
    why shouldBe ValidationError("output", "blocked")
    messagesOf(refusedState) shouldBe empty
  }

  // ---- a guardrail Block is a finished failure that leaves the thread usable (#1328, design 4.13) ----

  /** Blocks any input equal to "forbidden" and any answer containing "SECRET". */
  private def gate: AgentMiddleware = middleware(
    "gate",
    before = in => if in == "forbidden" then Left(ValidationError("input", "not allowed")) else Right(in),
    after = out => if out.contains("SECRET") then Left(ValidationError("output", "blocked")) else Right(out)
  )

  it should "finish an input Block as Failed with the history unchanged, so the next turn runs from it" in {
    val model   = ScriptedModel(_ => AssistantMessage("hello"))
    val l       = ToolLoop.build("assistant", "v1", model, set(Tools().echo), Seq(gate)).value
    val store   = InMemoryCheckpointer()
    val runtime = GraphRuntime(store)

    val (kept, error) = runtime.start(thread, l.graph, "forbidden").awaited.value.failed
    error shouldBe ValidationError("input", "not allowed")
    messagesOf(kept) shouldBe empty
    model.calls shouldBe 0
    store.latest(thread).value.value.checkpoint.status shouldBe CheckpointStatus.Failed

    val (state, answer) = runtime.start(thread, l.graph, "hi").awaited.value.completed
    answer shouldBe "hello"
    model.seen.get(0) shouldBe Vector(UserMessage("hi"))
    messagesOf(state) shouldBe Vector(UserMessage("hi"), AssistantMessage("hello"))
  }

  it should "remove the whole blocked turn on an output Block: input, tool calls and results, and the answer" in {
    val model = ScriptedModel(
      _ => AssistantMessage("fine"),
      calls(("c1", "echo", ujson.Obj("text" -> "hi"))),
      _ => AssistantMessage("SECRET answer"),
      _ => AssistantMessage("after")
    )
    val l       = ToolLoop.build("assistant", "v1", model, set(Tools().echo), Seq(gate)).value
    val store   = InMemoryCheckpointer()
    val runtime = GraphRuntime(store)
    runtime.start(thread, l.graph, "one").awaited.value.completed

    // turn two calls a tool, then answers with what the gate refuses
    val (kept, error) = runtime.start(thread, l.graph, "two").awaited.value.failed
    error shouldBe ValidationError("output", "blocked")
    model.calls shouldBe 3
    messagesOf(kept) shouldBe Vector(UserMessage("one"), AssistantMessage("fine"))

    // no blocked content is stored: not in the checkpoint, nor in any pending write
    val stored = store.latest(thread).value.value
    stored.checkpoint.status shouldBe CheckpointStatus.Failed
    val persisted = Checkpoint.toJson(stored.checkpoint).render()
    (persisted should not).include("SECRET")
    (persisted should not).include("\"c1\"")
    stored.pendingWrites shouldBe empty

    // the thread continues from the history before the turn
    val (state, answer) = runtime.start(thread, l.graph, "three").awaited.value.completed
    answer shouldBe "after"
    model.seen.get(3) shouldBe Vector(UserMessage("one"), AssistantMessage("fine"), UserMessage("three"))
    val history = messagesOf(state)
    history shouldBe Vector(
      UserMessage("one"),
      AssistantMessage("fine"),
      UserMessage("three"),
      AssistantMessage("after")
    )
    Message.validateConversation(history.toList).value shouldBe (())
  }

  it should "refuse recover on a blocked thread: it is finished, not interrupted" in {
    val model   = ScriptedModel(_ => AssistantMessage("SECRET"))
    val l       = ToolLoop.build("assistant", "v1", model, set(Tools().echo), Seq(gate)).value
    val runtime = GraphRuntime(InMemoryCheckpointer())
    runtime.start(thread, l.graph, "go").awaited.value.failed

    runtime.recover(thread, l.graph).left.value shouldBe GraphError.NothingToRecover(thread.value)
    model.calls shouldBe 1
  }

  it should "not treat a cancellation from a boundary hook as a Block: the run fails and stays Running" in {
    val cancelling = middleware("cancelling", after = _ => Left(CancelledError("hook")))
    val l     = ToolLoop.build("assistant", "v1", ScriptedModel(summarise), set(Tools().echo), Seq(cancelling)).value
    val store = InMemoryCheckpointer()

    GraphRuntime(store).start(thread, l.graph, "go").awaited.value.failed

    store.latest(thread).value.value.checkpoint.status shouldBe CheckpointStatus.Running
  }

  it should "refuse a blank final answer at the model node, storing nothing, even when afterAgent leaves it unchanged" in {
    val blankAnswer: Vector[Message] => AssistantMessage = _ => AssistantMessage("  ")
    val passThrough                                      = middleware("pass", after = s => Right(s))
    Seq(Nil, Seq(passThrough)).foreach { stack =>
      val l             = ToolLoop.build("assistant", "v1", ScriptedModel(blankAnswer), set(Tools().echo), stack).value
      val result        = runInMemory(l.graph, "go")
      val (node, cause) = nodeFailure(result)
      node shouldBe NodeId("model")
      cause.message should include("Assistant message must have either content or tool calls")
      messagesOf(result.failed._1).collect { case a: AssistantMessage => a } shouldBe empty
    }
  }

  it should "offer and run a middleware's contributed tool, and refuse clashing or loop-owned declarations" in {
    val runs = new AtomicInteger()
    val notes = tool[Echo]("note", "text") { (a, _) =>
      runs.incrementAndGet(); text(s"noted ${a.text}")
    }
    val contributing = middleware("contributing", contributes = Vector(notes))
    val model        = ScriptedModel(calls(("n1", "note", ujson.Obj("text" -> "x"))), summarise)
    val tools        = Tools()
    val l            = ToolLoop.build("assistant", "v1", model, set(tools.echo), Seq(contributing)).value
    runInMemory(l.graph, "go").completed._2 shouldBe "done: n1=noted x"
    runs.get shouldBe 1
    model.toolNames.get(0) shouldBe Vector("echo", "note")

    val clashing = middleware("clashing", contributes = Vector(tool[Echo]("echo", "text")((a, _) => text(a.text))))
    val clash    = ToolLoop.build("assistant", "v1", ScriptedModel(summarise), set(tools.echo), Seq(clashing))
    clash.left.value shouldBe a[ValidationError]
    clash.left.value.message should include("echo")

    val owning = middleware("owning", declares = Set(Messages.key))
    val owned  = ToolLoop.build("assistant", "v1", ScriptedModel(summarise), set(tools.echo), Seq(owning))
    owned.left.value.message should include(
      "middleware 'owning' declares a key the loop owns ('tool-results', 'messages' or 'usage')"
    )
  }

  it should "commit a wrapper's update to a key its middleware declares" in {
    val audits = StateKey[Vector[String], String]("audits", Vector.empty)((seen, s) => Right(seen :+ s))
    val auditing = middleware(
      "auditing",
      declares = Set(audits),
      wrapTool = (request, _, next) =>
        next() match {
          case ToolOutcome.Success(content, update) =>
            ToolOutcome.Success(content, update.combine(StateUpdate.update(audits, request.call.id)))
          case other => other
        }
    )
    val model           = ScriptedModel(calls(("a1", "echo", ujson.Obj("text" -> "hi"))), summarise)
    val l               = ToolLoop.build("assistant", "v1", model, set(Tools().echo), Seq(auditing)).value
    val (state, answer) = runInMemory(l.graph, "go").completed
    answer shouldBe "done: a1=hi"
    state.get(audits).value shouldBe Vector("a1")
  }

  it should "cancel, not fail, the run when a model wrapper throws a wrapped interrupt" in {
    val interrupting = middleware(
      "interrupting",
      model = (_, _) => throw new RuntimeException("wrapped", new InterruptedException("stop"))
    )
    val model = ScriptedModel(summarise)
    val l     = ToolLoop.build("assistant", "v1", model, set(Tools().echo), Seq(interrupting)).value
    val ended =
      GraphRuntime(InMemoryCheckpointer()).start(thread, l.graph, "go", RunConfig().withRunId(RunId("run-1"))).awaited
    ended.value.failed._2 shouldBe a[GraphError.Cancelled]
    model.calls shouldBe 0
  }

  it should "fail the run as the model's own failure, not a middleware's, when the ModelStep throws" in {
    val throwing = new ModelStep {
      def next(messages: Vector[Message], tools: ToolSet): Result[AssistantMessage] =
        throw new IllegalStateException("model broke")
    }
    val l             = ToolLoop.build("assistant", "v1", throwing, set(Tools().echo), Seq(middleware("passing"))).value
    val (node, cause) = nodeFailure(runInMemory(l.graph, "go"))
    node shouldBe NodeId("model")
    cause should not be a[GraphError.MiddlewareFailed]
    cause.message should include("model broke")

    val interrupted = new ModelStep {
      def next(messages: Vector[Message], tools: ToolSet): Result[AssistantMessage] =
        throw new RuntimeException("wrapped", new InterruptedException("stop"))
    }
    val c = ToolLoop.build("assistant", "v1", interrupted, set(Tools().echo), Seq(middleware("passing"))).value
    GraphRuntime(InMemoryCheckpointer())
      .start(thread, c.graph, "go", RunConfig().withRunId(RunId("run-1")))
      .awaited
      .value
      .failed
      ._2 shouldBe a[GraphError.Cancelled]
  }

  it should "resume a middleware approval durably, through a rebuilt loop with a different compatible stack" in {
    val model = ScriptedModel(calls(("c1", "lookup", ujson.Obj("q" -> "x"))), summarise)
    val tools = Tools()
    val store = InMemoryCheckpointer()
    val first = ToolLoop.build("assistant", "v1", model, set(tools.all*), Seq(policy)).value
    val suspended =
      GraphRuntime(store)
        .start(thread, first.graph, "go", RunConfig().withRunId(RunId("run-1")))
        .awaited
        .value
        .suspended
    val Vector((id, request)) = first.requests(suspended).value
    request.source shouldBe ApprovalSource.Middleware(MiddlewareId("policy"))
    store.latest(thread).value.map(_.checkpoint.status) shouldBe Some(CheckpointStatus.Suspended)

    // a new process: the same policy, now inside a recorder and with a closing afterAgent
    val log     = new CopyOnWriteArrayList[String]()
    val signing = middleware("signing", after = a => Right(s"$a (signed)"))
    val rebuilt =
      ToolLoop.build("assistant", "v1", model, set(tools.all*), Seq(recorder("rec", log), policy, signing)).value
    val (state, answer) = GraphRuntime(store)
      .resume(
        thread,
        rebuilt.graph,
        rebuilt.answers(id -> ApprovalDecision.Approve),
        RunConfig().withRunId(RunId("run-2"))
      )
      .awaited
      .value
      .completed
    answer shouldBe "done: c1=found x (signed)"
    log.asScala.toVector shouldBe Vector("c1:true")
    Message.validateConversation(messagesOf(state).toList).value shouldBe (())
    store.latest(thread).value.map(_.checkpoint.status) shouldBe Some(CheckpointStatus.Completed)
  }

  "ModelStep.fromClient" should "offer the loop's tools to the client" in {
    val offered = new CopyOnWriteArrayList[Seq[String]]()
    val client = new LLMClient {
      override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
        offered.add(options.tools.map(_.name))
        Right(Completion("id", 0L, "hello", "model", AssistantMessage("hello", toolCalls = List.empty)))
      override def streamComplete(
        conversation: Conversation,
        options: CompletionOptions,
        onChunk: StreamedChunk => Unit
      ): Result[Completion] = complete(conversation, options)
      override def getContextWindow(): Int     = 4096
      override def getReserveCompletion(): Int = 512
    }
    val tools = Tools()
    val l     = ToolLoop.build("assistant", "v1", ModelStep.fromClient(client), set(tools.all*)).value
    runInMemory(l.graph, "go").completed._2 shouldBe "hello"
    offered.asScala.toVector shouldBe Vector(tools.all.map(_.spec.name))
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

  // ---- usage through the model step, a tool-requested handoff, and an empty input (#1328, design 4.13) ----

  /** A model that plays `turns`, reporting `usage` and a cost for each call through `complete`. */
  final private class BilledModel(turns: (Vector[Message] => AssistantMessage)*)(usages: TokenUsage*)
      extends ModelStep {
    private val calls = new AtomicInteger()
    def next(messages: Vector[Message], tools: ToolSet): Result[AssistantMessage] =
      complete(messages, tools).map(_.message)
    override def complete(messages: Vector[Message], tools: ToolSet): Result[ModelReply] = {
      val turn = calls.getAndIncrement()
      Right(
        ModelReply(turns(turn)(messages), "stub-model", usages.lift(turn), usages.lift(turn).map(_.totalTokens * 0.001))
      )
    }
  }

  it should "sum the usage and cost of every model call into the usage key" in {
    val model = new BilledModel(calls(("c1", "echo", ujson.Obj("text" -> "hi"))), summarise)(
      TokenUsage(10, 5, 15),
      TokenUsage(20, 8, 28)
    )
    val l          = ToolLoop.build("assistant", "v1", model, set(Tools().echo)).value
    val (state, _) = runInMemory(l.graph, "go").completed
    val usage      = state.get(ToolLoop.usage).value

    usage.requestCount shouldBe 2
    usage.inputTokens shouldBe 30L
    usage.outputTokens shouldBe 13L
    usage.totalCost.toDouble shouldBe (0.043 +- 1e-9)
    usage.byModel.keySet shouldBe Set("stub-model")
  }

  it should "count a model wrapper's repeated calls: each was billed" in {
    val model = new ModelStep {
      def next(messages: Vector[Message], tools: ToolSet): Result[AssistantMessage] =
        complete(messages, tools).map(_.message)
      override def complete(messages: Vector[Message], tools: ToolSet): Result[ModelReply] =
        Right(ModelReply(AssistantMessage("ok"), "stub-model", Some(TokenUsage(10, 5, 15)), None))
    }
    // the wrapper asks twice, as a validating retry would, and keeps the second answer
    val twice = middleware("twice", model = (request, next) => next(request).flatMap(_ => next(request)))
    val l     = ToolLoop.build("assistant", "v1", model, set(Tools().echo), Seq(twice)).value

    runInMemory(l.graph, "go").completed._1.get(ToolLoop.usage).value.requestCount shouldBe 2
  }

  it should "report no usage when the model reports none, leaving the key at its initial value" in {
    val l          = ToolLoop.build("assistant", "v1", ScriptedModel(summarise), set(Tools().echo)).value
    val (state, _) = runInMemory(l.graph, "go").completed
    state.isSet(ToolLoop.usage) shouldBe false
    state.get(ToolLoop.usage).value shouldBe UsageSummary()
  }

  /** A tool that asks for the handoff `id` with the call's `reason`. */
  private def handoffTool(id: String): AgentTool[Echo] =
    AgentTool(
      AgentToolSpec[Echo](s"handoff_to_$id", s"Hand off to $id", strings[Echo]("handoff", "text")),
      Set(ToolLoop.handoff)
    ) { (a, _) =>
      ToolOutcome.Success(ujson.Str("handing off"), StateUpdate.update(ToolLoop.handoff, HandoffRequest(id, a.text)))
    }

  it should "end the loop after the batch in which a tool asked for a handoff, without calling the model again" in {
    val model = ScriptedModel(calls(("h1", "handoff_to_physics", ujson.Obj("text" -> "needs physics"))), summarise)
    val l     = ToolLoop.build("assistant", "v1", model, set(handoffTool("physics"))).value

    val (state, answer) = runInMemory(l.graph, "why is the sky blue").completed

    answer shouldBe ""
    state.get(ToolLoop.handoff).value shouldBe Some(HandoffRequest("physics", "needs physics"))
    model.calls shouldBe 1
    // the batch's result was recorded before the loop ended
    messagesOf(state).last shouldBe a[ToolMessage]
  }

  it should "take the first handoff of a batch" in {
    val model = ScriptedModel(
      calls(
        ("h1", "handoff_to_physics", ujson.Obj("text" -> "first")),
        ("h2", "handoff_to_poetry", ujson.Obj("text" -> "second"))
      )
    )
    val l = ToolLoop.build("assistant", "v1", model, set(handoffTool("physics"), handoffTool("poetry"))).value

    runInMemory(l.graph, "go").completed._1.get(ToolLoop.handoff).value shouldBe Some(
      HandoffRequest("physics", "first")
    )
  }

  it should "continue a thread from its history on an empty input: no user message, no beforeAgent" in {
    val seen    = new CopyOnWriteArrayList[String]()
    val spy     = middleware("spy", before = text => { seen.add(text); Right(text) })
    val model   = ScriptedModel(_ => AssistantMessage("answered from history"))
    val l       = ToolLoop.build("assistant", "v1", model, set(Tools().echo), Seq(spy)).value
    val runtime = GraphRuntime(InMemoryCheckpointer())
    runtime
      .seed(
        thread,
        l.graph,
        StateUpdate.update(Messages.key, MessageUpdate.Append(StoredMessage("seed/user", UserMessage("question"))))
      )
      .value

    val (state, answer) = runtime.start(thread, l.graph, "").awaited.value.completed

    answer shouldBe "answered from history"
    model.seen.get(0) shouldBe Vector(UserMessage("question"))
    messagesOf(state) shouldBe Vector(UserMessage("question"), AssistantMessage("answered from history"))
    seen.size shouldBe 0
  }

  "ToolQuestionRequest" should "round-trip through JSON" in {
    val request = ToolQuestionRequest("m", ToolCall("c", "confirm", ujson.Obj("env" -> "prod")), ujson.Obj("p" -> 1))
    upickle.default.read[ToolQuestionRequest](upickle.default.write(request)) shouldBe request
    val approved = request.copy(approved = true)
    upickle.default.read[ToolQuestionRequest](upickle.default.write(approved)) shouldBe approved
  }
}
