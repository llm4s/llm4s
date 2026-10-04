package org.llm4s.agent.graph.middleware

import org.llm4s.agent.graph.*
import org.llm4s.agent.graph.tool.{ AgentTool, AgentToolFixtures, AgentToolSpec, ToolContext, ToolOutcome, ToolSet }
import org.llm4s.error.{ CancelledError, LLMError, ValidationError }
import org.llm4s.llmconnect.model.{ AssistantMessage, ToolCall, UserMessage }
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*

class MiddlewareStackSpec extends AnyFlatSpec with Matchers with EitherValues {
  import AgentToolFixtures._

  /** Logs `<id>:before` and `<id>:after` around `next` in every hook. */
  private class Rec(
    name: String,
    log: CopyOnWriteArrayList[String],
    after: Set[String] = Set.empty,
    before: Set[String] = Set.empty,
    contributes: Vector[AgentTool[?]] = Vector.empty
  ) extends AgentMiddleware:
    val id: MiddlewareId                       = MiddlewareId(name)
    override def runsAfter: Set[MiddlewareId]  = after.map(MiddlewareId(_))
    override def runsBefore: Set[MiddlewareId] = before.map(MiddlewareId(_))
    override def tools: Vector[AgentTool[?]]   = contributes
    override def beforeAgent(input: String, context: RunContext): Result[String] =
      log.add(s"$name:beforeAgent")
      Right(s"$input>$name")
    override def afterAgent(answer: String, context: RunContext): Result[String] =
      log.add(s"$name:afterAgent")
      Right(s"$answer<$name")
    override def wrapModelCall(request: ModelRequest, context: RunContext)(
      next: ModelRequest => Result[AssistantMessage]
    ): Result[AssistantMessage] =
      log.add(s"$name:before")
      val result = next(request.copy(messages = request.messages :+ UserMessage(name)))
      log.add(s"$name:after")
      result
    override def wrapToolCall(request: ToolCallRequest, context: ToolContext)(next: () => ToolOutcome): ToolOutcome =
      log.add(s"$name:before")
      val result = next()
      log.add(s"$name:after")
      result

  /** A middleware whose tool wrapper is `wrap`, logging what `next` returned as seen from outside. */
  private class Wrap(name: String)(wrap: (() => ToolOutcome) => ToolOutcome) extends AgentMiddleware:
    val id: MiddlewareId = MiddlewareId(name)
    override def wrapToolCall(request: ToolCallRequest, context: ToolContext)(next: () => ToolOutcome): ToolOutcome =
      wrap(next)

  private class Throwing(name: String, thrown: () => Throwable) extends AgentMiddleware:
    val id: MiddlewareId                                                         = MiddlewareId(name)
    override def beforeAgent(input: String, context: RunContext): Result[String] = throw thrown()
    override def afterAgent(answer: String, context: RunContext): Result[String] = throw thrown()
    override def wrapModelCall(request: ModelRequest, context: RunContext)(
      next: ModelRequest => Result[AssistantMessage]
    ): Result[AssistantMessage] = throw thrown()
    override def wrapToolCall(request: ToolCallRequest, context: ToolContext)(next: () => ToolOutcome): ToolOutcome =
      throw thrown()

  private val runContext: RunContext = GraphTestSupport.testRunContext()
  private val toolContext: ToolContext =
    ToolContext(runContext, "call-1", ThreadState.empty(Map.empty), approved = false)

  private val request = ToolCallRequest(
    AgentToolSpec[Search]("search", "Searches", searchSchema),
    ToolCall("call-1", "search", ujson.Obj("query" -> "x"))
  )
  private val modelRequest = ModelRequest(Vector.empty, ToolSet.of().value)

  private def tool(name: String): AgentTool[Search] =
    AgentTool(AgentToolSpec[Search](name, "Searches", searchSchema))((_, _) => ToolOutcome.Success(ujson.Str("ok")))

  private def stack(middleware: AgentMiddleware*): MiddlewareStack = MiddlewareStack.of(middleware*).value

  private def ids(s: MiddlewareStack): Vector[String] = s.ordered.map(_.id.value)

  private def newLog = new CopyOnWriteArrayList[String]()

  private def problems(result: Result[MiddlewareStack]): List[String] = result.left.value match
    case v: ValidationError => v.violations
    case other              => fail(s"not a ValidationError: $other")

  "MiddlewareStack.of" should "keep registration order when there are no constraints" in {
    val log = newLog
    ids(stack(Rec("a", log), Rec("b", log), Rec("c", log))) shouldBe Vector("a", "b", "c")
    ids(stack(Rec("c", log), Rec("a", log), Rec("b", log))) shouldBe Vector("c", "a", "b")
  }

  it should "reorder by runsAfter and runsBefore" in {
    val log = newLog
    ids(stack(Rec("a", log, after = Set("b")), Rec("b", log))) shouldBe Vector("b", "a")
    ids(stack(Rec("a", log), Rec("b", log, before = Set("a")))) shouldBe Vector("b", "a")
  }

  it should "break ties by registration order" in {
    val log = newLog
    ids(stack(Rec("a", log), Rec("b", log), Rec("c", log, after = Set("a")))) shouldBe Vector("a", "b", "c")
    ids(stack(Rec("c", log, after = Set("a")), Rec("b", log), Rec("a", log))) shouldBe Vector("b", "a", "c")
  }

  it should "accept an empty registration, and empty has no middleware" in {
    ids(stack()) shouldBe Vector.empty
    MiddlewareStack.empty.ordered shouldBe Vector.empty
  }

  it should "report every problem together in one ValidationError" in {
    val log = newLog
    val result = MiddlewareStack.of(
      Rec("bad id", log),
      Rec("a", log, after = Set("zz")),
      Rec("a", log),
      Rec("p", log, after = Set("q")),
      Rec("q", log, after = Set("p")),
      Rec("x", log, before = Set("y")),
      Rec("y", log, before = Set("z")),
      Rec("z", log, before = Set("x")),
      Rec("t1", log, contributes = Vector(tool("shared"))),
      Rec("t2", log, contributes = Vector(tool("shared")))
    )
    result.left.value.asInstanceOf[ValidationError].field shouldBe "middleware stack"
    problems(result) shouldBe List(
      "invalid middleware id 'bad id': must match [a-zA-Z0-9_-]{1,64}",
      "duplicate middleware id 'a'",
      "middleware 'a': runsAfter names unknown middleware 'zz'",
      "middleware cycle: p -> q -> p",
      "middleware cycle: x -> y -> z -> x",
      "tool 'shared' is contributed by more than one middleware: t1, t2"
    )
  }

  it should "report a runsBefore naming an unknown id, and an id of 65 characters" in {
    val log = newLog
    problems(MiddlewareStack.of(Rec("a", log, before = Set("nope")), Rec("b" * 65, log))) shouldBe List(
      s"invalid middleware id '${"b" * 65}': must match [a-zA-Z0-9_-]{1,64}",
      "middleware 'a': runsBefore names unknown middleware 'nope'"
    )
  }

  it should "report a tool one middleware contributes twice, listing each clashing owner once" in {
    val log = newLog
    problems(
      MiddlewareStack.of(
        Rec("a", log, contributes = Vector(tool("twin"), tool("twin"))),
        Rec("b", log, contributes = Vector(tool("shared"), tool("shared"))),
        Rec("c", log, contributes = Vector(tool("shared")))
      )
    ) shouldBe List(
      "tool 'twin' is contributed twice by middleware 'a'",
      "tool 'shared' is contributed twice by middleware 'b'",
      "tool 'shared' is contributed by more than one middleware: b, c"
    )
  }

  it should "collect contributed tools in stack order and union the writes" in {
    val log = newLog
    val k1  = StateKey.replace[Int]("k1", 0)
    val k2  = StateKey.replace[Int]("k2", 0)
    val withWrites = (name: String, keys: Set[StateKey[?, ?]], t: AgentTool[?]) =>
      new Rec(name, log, contributes = Vector(t)) { override def writes: Set[StateKey[?, ?]] = keys }
    val s = stack(withWrites("a", Set(k1), tool("one")), withWrites("b", Set(k1, k2), tool("two")))
    s.tools.map(_.spec.name) shouldBe Vector("one", "two")
    s.writes shouldBe Set(k1, k2)
  }

  "wrapToolCall" should "nest the first middleware outermost" in {
    val log = newLog
    val result = stack(Rec("a", log), Rec("b", log), Rec("c", log)).wrapToolCall(request, toolContext) { () =>
      log.add("tool")
      ToolOutcome.Success(ujson.Str("done"))
    }
    log.asScala.toList shouldBe List("a:before", "b:before", "c:before", "tool", "c:after", "b:after", "a:after")
    result shouldBe MiddlewareStack.ToolChainResult(ToolOutcome.Success(ujson.Str("done")), None)
  }

  it should "call the innermost function directly with no middleware" in {
    MiddlewareStack.empty.wrapToolCall(request, toolContext)(() => ToolOutcome.Error("e")).outcome shouldBe
      ToolOutcome.Error("e")
  }

  it should "short-circuit when a wrapper does not call next, and the outer wrapper sees its outcome" in {
    val log  = newLog
    val seen = newLog
    val a = new Wrap("a")(next =>
      val r = next()
      seen.add(r.toString)
      r
    )
    val b = new Wrap("b")(_ => ToolOutcome.Error("Denied: x"))
    val result = stack(a, b, Rec("c", log)).wrapToolCall(request, toolContext) { () =>
      log.add("tool")
      ToolOutcome.Success(ujson.Str("done"))
    }
    log.asScala.toList shouldBe Nil
    seen.asScala.toList shouldBe List(ToolOutcome.Error("Denied: x").toString)
    result.outcome shouldBe ToolOutcome.Error("Denied: x")
    result.raisedBy shouldBe None
  }

  it should "run the tool again when a wrapper retries, returning the second outcome" in {
    val calls = new java.util.concurrent.atomic.AtomicInteger(0)
    val retry = new Wrap("retry")(next =>
      next()
      next()
    )
    val result = stack(retry).wrapToolCall(request, toolContext) { () =>
      ToolOutcome.Success(ujson.Num(calls.incrementAndGet().toDouble))
    }
    calls.get shouldBe 2
    result.outcome shouldBe ToolOutcome.Success(ujson.Num(2))
  }

  "ToolChainResult.raisedBy" should "be None for the tool's own NeedsApproval" in {
    val log = newLog
    val result = stack(Rec("a", log), Rec("b", log)).wrapToolCall(request, toolContext)(() =>
      ToolOutcome.NeedsApproval("tool asks")
    )
    result shouldBe MiddlewareStack.ToolChainResult(ToolOutcome.NeedsApproval("tool asks"), None)
  }

  it should "name the wrapper that returned NeedsApproval without calling next, through an outer pass-through" in {
    val log    = newLog
    val b      = new Wrap("b")(_ => ToolOutcome.NeedsApproval("b asks"))
    val result = stack(Rec("a", log), b, Rec("c", log)).wrapToolCall(request, toolContext)(() => ToolOutcome.Error("x"))
    result shouldBe MiddlewareStack.ToolChainResult(ToolOutcome.NeedsApproval("b asks"), Some(MiddlewareId("b")))
    stack(b).wrapToolCall(request, toolContext)(() => ToolOutcome.Error("x")).raisedBy shouldBe Some(MiddlewareId("b"))
  }

  it should "name a wrapper that replaces its next's outcome with a new NeedsApproval" in {
    val a = new Wrap("a")(next =>
      next() match
        case ToolOutcome.NeedsApproval(reason) => ToolOutcome.NeedsApproval(s"a: $reason")
        case other                             => other
    )
    stack(a).wrapToolCall(request, toolContext)(() => ToolOutcome.NeedsApproval("tool")).raisedBy shouldBe
      Some(MiddlewareId("a"))
  }

  it should "keep the attribution of an earlier next result a wrapper returns after calling next again" in {
    // a calls next twice and returns the first outcome, which the inner layer raised
    val firstOfTwo = new Wrap("a")(next =>
      val first = next()
      next()
      first
    )
    val calls = new AtomicInteger(0)
    val toolAsksOnce: () => ToolOutcome =
      () => if calls.getAndIncrement() == 0 then ToolOutcome.NeedsApproval("tool asks") else ToolOutcome.Error("later")
    stack(firstOfTwo).wrapToolCall(request, toolContext)(toolAsksOnce) shouldBe
      MiddlewareStack.ToolChainResult(ToolOutcome.NeedsApproval("tool asks"), None)

    val bCalls = new AtomicInteger(0)
    val bAsksOnce =
      new Wrap("b")(next => if bCalls.getAndIncrement() == 0 then ToolOutcome.NeedsApproval("b asks") else next())
    stack(firstOfTwo, bAsksOnce).wrapToolCall(request, toolContext)(() => ToolOutcome.Error("x")) shouldBe
      MiddlewareStack.ToolChainResult(ToolOutcome.NeedsApproval("b asks"), Some(MiddlewareId("b")))
  }

  "a throwing tool wrapper" should "become Fatal(MiddlewareFailed), which the outer wrapper sees" in {
    val seen = newLog
    val boom = new IllegalStateException("boom")
    val a    = new Wrap("a")(next => { val r = next(); seen.add(r.toString); r })
    val result =
      stack(a, new Throwing("b", () => boom)).wrapToolCall(request, toolContext)(() => ToolOutcome.Error("x"))
    result.outcome shouldBe ToolOutcome.Fatal(GraphError.MiddlewareFailed("b", boom))
    seen.asScala.toList shouldBe List(ToolOutcome.Fatal(GraphError.MiddlewareFailed("b", boom)).toString)
    result.raisedBy shouldBe None
  }

  it should "become Fatal(CancelledError) when it throws a cancellation, restoring the interrupt flag" in {
    Seq[() => Throwable](() => new RuntimeException(new InterruptedException()), () => new InterruptedException())
      .foreach { thrown =>
        val result = stack(new Throwing("b", thrown)).wrapToolCall(request, toolContext)(() => ToolOutcome.Error("x"))
        Thread.interrupted() shouldBe true
        result.outcome match
          case ToolOutcome.Fatal(c: CancelledError) => c.message should include("middleware b")
          case other                                => fail(s"not a cancellation: $other")
      }
  }

  "wrapModelCall" should "nest the first middleware outermost and pass each rewritten request inward" in {
    val log    = newLog
    val answer = AssistantMessage("hi")
    val result = stack(Rec("a", log), Rec("b", log)).wrapModelCall(modelRequest, runContext) { req =>
      log.add(s"model:${req.messages.map(_.content).mkString(",")}")
      Right(answer)
    }
    result shouldBe Right(answer)
    log.asScala.toList shouldBe List("a:before", "b:before", "model:a,b", "b:after", "a:after")
  }

  it should "turn a throw into Left(MiddlewareFailed), which the outer wrapper sees" in {
    val log  = newLog
    val boom = new IllegalStateException("boom")
    val seen = newLog
    val a = new AgentMiddleware:
      val id: MiddlewareId = MiddlewareId("a")
      override def wrapModelCall(request: ModelRequest, context: RunContext)(
        next: ModelRequest => Result[AssistantMessage]
      ): Result[AssistantMessage] =
        val r = next(request)
        seen.add(r.toString)
        r
    val result = stack(a, new Throwing("b", () => boom)).wrapModelCall(modelRequest, runContext) { _ =>
      log.add("model")
      Right(AssistantMessage("hi"))
    }
    result shouldBe Left(GraphError.MiddlewareFailed("b", boom))
    seen.asScala.toList shouldBe List(Left(GraphError.MiddlewareFailed("b", boom)).toString)
    log.asScala.toList shouldBe Nil
  }

  it should "turn a thrown cancellation, a bare InterruptedException too, into Left(CancelledError) and set the interrupt flag" in {
    Seq[() => Throwable](() => new RuntimeException(new InterruptedException()), () => new InterruptedException())
      .foreach { thrown =>
        val result =
          stack(new Throwing("b", thrown)).wrapModelCall(modelRequest, runContext)(_ => Right(AssistantMessage("hi")))
        Thread.interrupted() shouldBe true
        result.left.value shouldBe a[CancelledError]
      }
  }

  "beforeAgent" should "run in stack order, threading the transformed input" in {
    val log = newLog
    stack(Rec("a", log), Rec("b", log), Rec("c", log)).beforeAgent("in", runContext) shouldBe Right("in>a>b>c")
    log.asScala.toList shouldBe List("a:beforeAgent", "b:beforeAgent", "c:beforeAgent")
  }

  it should "stop at the first Left" in {
    val log           = newLog
    val err: LLMError = ValidationError("input", "blocked")
    val blocking = new Rec("b", log) {
      override def beforeAgent(input: String, context: RunContext): Result[String] = Left(err)
    }
    stack(Rec("a", log), blocking, Rec("c", log)).beforeAgent("in", runContext) shouldBe Left(err)
    log.asScala.toList shouldBe List("a:beforeAgent")
  }

  it should "turn a throw into Left(MiddlewareFailed) and a cancellation into Left(CancelledError)" in {
    val boom = new IllegalStateException("boom")
    stack(new Throwing("b", () => boom)).beforeAgent("in", runContext) shouldBe
      Left(GraphError.MiddlewareFailed("b", boom))
    val cancelled = stack(new Throwing("b", () => new RuntimeException(new InterruptedException())))
      .beforeAgent("in", runContext)
    Thread.interrupted() shouldBe true
    cancelled.left.value shouldBe a[CancelledError]
  }

  "afterAgent" should "run in reverse stack order, threading the transformed answer" in {
    val log = newLog
    stack(Rec("a", log), Rec("b", log), Rec("c", log)).afterAgent("out", runContext) shouldBe Right("out<c<b<a")
    log.asScala.toList shouldBe List("c:afterAgent", "b:afterAgent", "a:afterAgent")
  }

  it should "turn a throw into Left(MiddlewareFailed)" in {
    val boom = new IllegalStateException("boom")
    stack(new Throwing("b", () => boom)).afterAgent("out", runContext) shouldBe
      Left(GraphError.MiddlewareFailed("b", boom))
  }

  "MiddlewareFailed.message" should "name the middleware and the cause's message" in {
    GraphError.MiddlewareFailed("audit", new IllegalStateException("boom")).message shouldBe
      "Middleware 'audit' failed: boom"
    GraphError.MiddlewareFailed("audit", new IllegalStateException()).message shouldBe
      "Middleware 'audit' failed: java.lang.IllegalStateException"
  }

  "MiddlewareId" should "round-trip through its ReadWriter as a plain string" in {
    upickle.default.write(MiddlewareId("approval")) shouldBe "\"approval\""
    upickle.default.read[MiddlewareId]("\"approval\"") shouldBe MiddlewareId("approval")
  }
}
