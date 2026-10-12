package org.llm4s.agent.graph

import org.llm4s.agent.graph.GraphTestSupport.testRunContext
import org.llm4s.agent.graph.middleware.{ ModelRequest, ToolCallRequest }
import org.llm4s.agent.graph.tool.{ AgentToolFixtures, AgentToolSpec, ToolContext, ToolSet }
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.model.{ ToolCall, UserMessage }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * The run-facing types follow the growth-prone pattern (CLAUDE.md): a private constructor, a public
 * `apply` with the defaults, `with*` setters, and no public `copy`, so a field can be added after the
 * baseline without breaking a caller. Tool and call identifiers are typed.
 */
class GrowthProneTypesSpec extends AnyFlatSpec with Matchers {
  import AgentToolFixtures.*

  private val run   = testRunContext()
  private val state = ThreadState.empty(Map.empty)
  private val spec  = AgentToolSpec[Search]("search", "Searches", searchSchema)
  private val call  = ToolCall("call-1", "search", ujson.Obj("query" -> "x"))
  private val idem  = IdempotencyKey("key-1")

  "ToolCallId and ToolName" should "wrap and unwrap a string" in {
    ToolCallId("call-1").value shouldBe "call-1"
    ToolName("search").value shouldBe "search"
  }

  "ToolContext" should "default to not approved" in {
    ToolContext(run, ToolCallId("call-1"), idem, state).approved shouldBe false
  }

  it should "change one field per setter and leave the original alone" in {
    val original = ToolContext(run, ToolCallId("call-1"), idem, state)

    original.withApproved(true).approved shouldBe true
    original.withToolCallId(ToolCallId("call-2")).toolCallId shouldBe ToolCallId("call-2")
    original.withRun(testRunContext()).toolCallId shouldBe ToolCallId("call-1")
    original.withState(state).state shouldBe state
    original.withIdempotencyKey(IdempotencyKey("key-2")).idempotencyKey shouldBe IdempotencyKey("key-2")
    original.idempotencyKey shouldBe idem
    original.approved shouldBe false
    original.toolCallId shouldBe ToolCallId("call-1")
  }

  it should "have no public constructor and no public copy" in {
    val context = ToolContext(run, ToolCallId("call-1"), idem, state)
    assert(context.approved == false)

    scala.compiletime.testing
      .typeCheckErrors("new ToolContext(run, ToolCallId(\"x\"), idem, state, true)")
      .map(_.message)
      .mkString(" ") should include("cannot be accessed")
    scala.compiletime.testing
      .typeCheckErrors("context.copy(approved = true)")
      .map(_.message)
      .mkString(" ") should include("cannot be accessed")
  }

  "ModelRequest" should "default to no tools" in {
    ModelRequest(Vector.empty).tools shouldBe ToolSet.empty
  }

  it should "change one field per setter" in {
    val message  = UserMessage("hello")
    val original = ModelRequest(Vector.empty)

    original.withMessages(Vector(message)).messages shouldBe Vector(message)
    original.withTools(ToolSet.empty).messages shouldBe Vector.empty
    original.messages shouldBe Vector.empty
  }

  it should "have no public constructor and no public copy" in {
    val request = ModelRequest(Vector.empty)
    assert(request.messages.isEmpty)

    scala.compiletime.testing
      .typeCheckErrors("new ModelRequest(Vector.empty, ToolSet.empty)")
      .map(_.message)
      .mkString(" ") should include("cannot be accessed")
    scala.compiletime.testing
      .typeCheckErrors("request.copy(messages = Vector.empty)")
      .map(_.message)
      .mkString(" ") should include("cannot be accessed")
  }

  "ToolCallRequest" should "expose the call's id and tool name as typed values" in {
    val request = ToolCallRequest(spec, call)

    request.toolCallId shouldBe ToolCallId("call-1")
    request.toolName shouldBe ToolName("search")
  }

  it should "change one field per setter" in {
    val other   = ToolCall("call-2", "lookup", ujson.Obj())
    val request = ToolCallRequest(spec, call)

    request.withCall(other).toolCallId shouldBe ToolCallId("call-2")
    request.withSpec(spec).toolCallId shouldBe ToolCallId("call-1")
    request.call shouldBe call
  }

  it should "have no public constructor and no public copy" in {
    val request = ToolCallRequest(spec, call)
    assert(request.call == call)

    scala.compiletime.testing
      .typeCheckErrors("new ToolCallRequest(spec, call)")
      .map(_.message)
      .mkString(" ") should include("cannot be accessed")
    scala.compiletime.testing.typeCheckErrors("request.copy(call = call)").map(_.message).mkString(" ") should include(
      "cannot be accessed"
    )
  }

  "GraphError.ToolFailed" should "carry typed identifiers and render them in its message" in {
    val error = GraphError.ToolFailed(ToolName("search"), ToolCallId("call-1"), ValidationError("x", "boom"))

    error.tool shouldBe ToolName("search")
    error.toolCallId shouldBe ToolCallId("call-1")
    error.message shouldBe "Tool 'search' (call call-1) failed: Invalid x: boom"
  }

  it should "change one field per setter and leave the original alone" in {
    val cause    = ValidationError("x", "boom")
    val original = GraphError.ToolFailed(ToolName("search"), ToolCallId("call-1"), cause)

    original.withTool(ToolName("lookup")).tool shouldBe ToolName("lookup")
    original.withToolCallId(ToolCallId("call-2")).toolCallId shouldBe ToolCallId("call-2")
    original.withCause(ValidationError("y", "other")).cause.message shouldBe "Invalid y: other"
    original.tool shouldBe ToolName("search")
    original.cause shouldBe cause
  }

  it should "have no public constructor and no public copy" in {
    val error = GraphError.ToolFailed(ToolName("search"), ToolCallId("call-1"), ValidationError("x", "boom"))
    assert(error.tool == ToolName("search"))

    scala.compiletime.testing
      .typeCheckErrors("new GraphError.ToolFailed(ToolName(\"a\"), ToolCallId(\"b\"), ValidationError(\"x\", \"y\"))")
      .map(_.message)
      .mkString(" ") should include("cannot be accessed")
    scala.compiletime.testing
      .typeCheckErrors("error.copy(tool = ToolName(\"other\"))")
      .map(_.message)
      .mkString(" ") should include("cannot be accessed")
  }
}
