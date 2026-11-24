package org.llm4s.agent

import org.llm4s.agent.AgentFixture._
import org.llm4s.agent.graph.{ GraphError, ThreadId }
import org.llm4s.agent.graph.middleware._
import org.llm4s.agent.graph.tool.{ ToolContent, ToolContext, ToolOutcome }
import org.llm4s.error.{ LLMError, ValidationError }
import org.llm4s.llmconnect.model.ToolMessage
import org.llm4s.testutil.TestTimeLimit
import org.llm4s.types.Result
import org.scalatest.LoneElement
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicBoolean

/**
 * How a middleware hook's failure reaches the caller (#2795, items 4 and 7): one model for every hook.
 *
 *   - A hook fails by value: a `Left` from a hook that returns `Result`, a `ToolOutcome.Fatal` from `wrapToolCall`,
 *     which returns what a tool returns. A throw is that value holding `GraphError.MiddlewareFailed`.
 *   - What a failure does depends on where the hook runs. At a turn's boundary (`beforeAgent`, `afterAgent`) it blocks
 *     the turn: nothing of it is kept, the thread stays usable and there is nothing to recover. Inside a step
 *     (`promptSections`, `wrapModelCall`, `wrapToolCall`) it fails the run, and `recover` runs the step again.
 *   - A block that is an outcome, not an error, is `block(reason)`: the turn ends `AgentStatus.Blocked` with the
 *     middleware's id, as a guardrail's block does with the guardrail's name.
 */
class AgentMiddlewareFailureSpec extends AnyFlatSpec with TestTimeLimit with Matchers with LoneElement {

  private enum Mode:
    case Fails, Throws, Blocks

  private val refused: LLMError = ValidationError("hook", "refused")

  /** Fails in `hook` as `mode` says, while `on`. */
  private class Failing(hook: String, mode: Mode) extends AgentMiddleware:
    val id: MiddlewareId = MiddlewareId("failing")
    val on               = new AtomicBoolean(true)

    private def failing[A](in: String)(ok: => Result[A]): Result[A] =
      if hook == in && on.get then
        mode match
          case Mode.Fails  => Left(refused)
          case Mode.Throws => throw new IllegalStateException("boom")
          case Mode.Blocks => block("not on a weekend")
      else ok

    override def beforeAgent(input: String, context: ModelContext): Result[BoundaryResult] =
      failing("beforeAgent")(Right(BoundaryResult(input)))
    override def afterAgent(answer: String, context: ModelContext): Result[BoundaryResult] =
      failing("afterAgent")(Right(BoundaryResult(answer)))
    override def promptSections(context: ModelContext): Result[Vector[PromptSection]] =
      failing("promptSections")(Right(Vector.empty))
    override def wrapModelCall(request: ModelRequest, context: ModelContext)(
      next: ModelRequest => Result[ModelResponse]
    ): Result[ModelResponse] = failing("wrapModelCall")(next(request))
    override def wrapToolCall(request: ToolCallRequest, context: ToolContext)(next: () => ToolOutcome): ToolOutcome =
      if hook == "wrapToolCall" && on.get then
        mode match
          case Mode.Fails  => ToolOutcome.Fatal(refused)
          case Mode.Throws => throw new IllegalStateException("boom")
          case Mode.Blocks => ToolOutcome.Error("Denied: not on a weekend")
      else next()

  private val echo = SpecTools.tool("echo")((t, _) => ToolOutcome.success(ToolContent.Json(ujson.Str(t.text))))

  /** An agent whose every turn calls `echo` once and then answers. */
  private def fixture(hook: String, mode: Mode): (Agent, Failing, ScriptedLLMClient) =
    val client = ScriptedLLMClient.of(
      CompletionFixture.withToolCall("echo", ujson.Obj("text" -> "x")),
      CompletionFixture.simple("first answer"),
      CompletionFixture.withToolCall("echo", ujson.Obj("text" -> "y")),
      CompletionFixture.simple("second answer")
    )
    val middleware = new Failing(hook, mode)
    val agent      = built(Agent.builder("assistant", client).withTools(SpecTools.set(echo)).withMiddleware(middleware))
    (agent, middleware, client)

  private val thread = ThreadId("t1")

  // ---- the turn's boundaries: a failure blocks the turn ----

  for hook <- Seq("beforeAgent", "afterAgent") do
    s"A Left from $hook" should "block the turn: the run returns it, and the thread needs no recover" in {
      val (agent, middleware, _) = fixture(hook, Mode.Fails)

      agent.run(thread, "q1").error shouldBe refused
      agent.recover(thread).error shouldBe a[GraphError.NothingToRecover]

      middleware.on.set(false)
      val next = agent.run(thread, "q2").value
      next.status shouldBe a[AgentStatus.Completed]
      // nothing of the blocked turn was kept: the history is the second turn alone
      next.messages should have size 4
    }

    s"A throw from $hook" should "block the turn as a Left does, with MiddlewareFailed naming the middleware" in {
      val (agent, middleware, _) = fixture(hook, Mode.Throws)

      agent.run(thread, "q1").error match
        case GraphError.MiddlewareFailed(name, thrown) =>
          name shouldBe "failing"
          thrown.getMessage shouldBe "boom"
        case other => fail(s"expected MiddlewareFailed, got $other")
      agent.recover(thread).error shouldBe a[GraphError.NothingToRecover]

      middleware.on.set(false)
      agent.run(thread, "q2").value.status shouldBe a[AgentStatus.Completed]
    }

    s"block(reason) from $hook" should "end the turn Blocked, naming the middleware: an outcome, not a Left" in {
      val (agent, middleware, _) = fixture(hook, Mode.Blocks)

      val blocked = agent.run(thread, "q1").value
      blocked.status shouldBe AgentStatus.Blocked("failing", "not on a weekend")
      blocked.answer shouldBe None
      blocked.messages shouldBe empty

      middleware.on.set(false)
      val next = agent.run(thread, "q2").value
      next.status shouldBe a[AgentStatus.Completed]
      next.messages should have size 4
    }

  // ---- inside a step: a failure fails the run, and recover runs the step again ----

  for hook <- Seq("promptSections", "wrapModelCall") do
    s"A Left from $hook" should "fail the run; recover runs the model step again" in {
      val (agent, middleware, client) = fixture(hook, Mode.Fails)

      cause(agent.run(thread, "q1").error) shouldBe refused
      client.callCount shouldBe 0
      agent.run(thread, "q2").error shouldBe a[GraphError.IncompleteRun]

      middleware.on.set(false)
      val recovered = agent.recover(thread).value
      recovered.status shouldBe AgentStatus.Completed("first answer")
      recovered.messages should have size 4
    }

    s"A throw from $hook" should "fail the run as a Left does, with MiddlewareFailed naming the middleware" in {
      val (agent, middleware, _) = fixture(hook, Mode.Throws)

      cause(agent.run(thread, "q1").error) shouldBe a[GraphError.MiddlewareFailed]
      agent.run(thread, "q2").error shouldBe a[GraphError.IncompleteRun]

      middleware.on.set(false)
      agent.recover(thread).value.status shouldBe AgentStatus.Completed("first answer")
    }

    s"block(reason) from $hook" should "be a failure there: only a boundary hook blocks a turn" in {
      val (agent, middleware, _) = fixture(hook, Mode.Blocks)

      cause(agent.run(thread, "q1").error) shouldBe GuardrailBlocked("failing", "not on a weekend")
      middleware.on.set(false)
      agent.recover(thread).value.status shouldBe AgentStatus.Completed("first answer")
    }

  // ---- around a tool call: Fatal is the wrapper's Left ----

  "ToolOutcome.Fatal from wrapToolCall" should "fail the run with ToolFailed; recover runs that call again" in {
    val (agent, middleware, client) = fixture("wrapToolCall", Mode.Fails)

    cause(agent.run(thread, "q1").error) match
      case failed: GraphError.ToolFailed =>
        failed.tool.value shouldBe "echo"
        failed.cause shouldBe refused
      case other => fail(s"expected ToolFailed, got $other")
    agent.run(thread, "q2").error shouldBe a[GraphError.IncompleteRun]

    middleware.on.set(false)
    agent.recover(thread).value.status shouldBe AgentStatus.Completed("first answer")
    // the model was not asked again for the call: recover ran the failed call, then the next model step
    client.callCount shouldBe 2
  }

  "A throw from wrapToolCall" should "fail the run with MiddlewareFailed naming the middleware" in {
    val (agent, middleware, _) = fixture("wrapToolCall", Mode.Throws)

    cause(agent.run(thread, "q1").error) match
      case GraphError.MiddlewareFailed(name, _) => name shouldBe "failing"
      case other                                => fail(s"expected MiddlewareFailed, got $other")

    middleware.on.set(false)
    agent.recover(thread).value.status shouldBe AgentStatus.Completed("first answer")
  }

  "ToolOutcome.Error from wrapToolCall" should "deny the call: the model sees the message and the run goes on" in {
    val (agent, _, _) = fixture("wrapToolCall", Mode.Blocks)

    val result = agent.run(thread, "q1").value
    result.status shouldBe AgentStatus.Completed("first answer")
    result.messages.collect { case t: ToolMessage => t.content }.loneElement should include("Denied: not on a weekend")
  }
}
