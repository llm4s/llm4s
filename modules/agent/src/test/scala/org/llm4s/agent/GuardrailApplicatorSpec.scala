package org.llm4s.agent

import org.llm4s.agent.guardrails.{ InputGuardrail, OutputGuardrail }
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.model.{ AssistantMessage, Conversation, UserMessage }
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Unit tests for [[GuardrailApplicator]] — all tests are pure (no mocks, no LLM). */
class GuardrailApplicatorSpec extends AnyFlatSpec with Matchers {

  // ── Stub guardrails ──────────────────────────────────────────────────────────

  private def passingInputGuardrail(guardName: String): InputGuardrail = new InputGuardrail {
    val name                                    = guardName
    def validate(value: String): Result[String] = Right(value)
  }

  private def failingInputGuardrail(guardName: String, errorMsg: String): InputGuardrail = new InputGuardrail {
    val name = guardName
    def validate(value: String): Result[String] =
      Left(ValidationError.invalid("test", errorMsg))
  }

  private def passingOutputGuardrail(guardName: String): OutputGuardrail = new OutputGuardrail {
    val name                                    = guardName
    def validate(value: String): Result[String] = Right(value)
  }

  private def failingOutputGuardrail(guardName: String, errorMsg: String): OutputGuardrail = new OutputGuardrail {
    val name = guardName
    def validate(value: String): Result[String] =
      Left(ValidationError.invalid("test", errorMsg))
  }

  // ── Helper: minimal AgentState ───────────────────────────────────────────────

  private def stateWithLastMessage(content: String): AgentState = {
    val msg = AssistantMessage(content, Seq.empty)
    AgentState(
      conversation = Conversation(Seq(UserMessage("hello"), msg)),
      tools = ToolRegistry.empty
    )
  }

  private def stateWithNoAssistantMessage: AgentState =
    AgentState(
      conversation = Conversation(Seq(UserMessage("hello"))),
      tools = ToolRegistry.empty
    )

  private def stateWithMultipleAssistantMessages: AgentState = {
    val first  = AssistantMessage("intermediate answer", Seq.empty)
    val second = AssistantMessage("final answer", Seq.empty)
    AgentState(
      conversation = Conversation(Seq(UserMessage("q"), first, UserMessage("q2"), second)),
      tools = ToolRegistry.empty
    )
  }

  // ── validateInput ────────────────────────────────────────────────────────────

  "GuardrailApplicator.validateInput" should "pass unchanged when guardrail seq is empty" in {
    val result = GuardrailApplicator.validateInput("hello world", Seq.empty)
    result shouldBe Right("hello world")
  }

  it should "pass through when all guardrails succeed" in {
    val guards = Seq(passingInputGuardrail("a"), passingInputGuardrail("b"))
    val result = GuardrailApplicator.validateInput("test query", guards)
    result shouldBe Right("test query")
  }

  it should "return Left when the first guardrail fails" in {
    val guards = Seq(
      failingInputGuardrail("blocker", "blocked by first"),
      passingInputGuardrail("second")
    )
    val result = GuardrailApplicator.validateInput("bad input", guards)
    result shouldBe a[Left[_, _]]
  }

  it should "return Left when any guardrail fails (all-mode)" in {
    val guards = Seq(
      passingInputGuardrail("first"),
      failingInputGuardrail("blocker", "blocked by second")
    )
    val result = GuardrailApplicator.validateInput("bad input", guards)
    result shouldBe a[Left[_, _]]
  }

  it should "include the failure reason in the error" in {
    val guards = Seq(failingInputGuardrail("g", "profanity detected"))
    GuardrailApplicator.validateInput("bad word", guards) match {
      case Left(err) => err.message should include("profanity detected")
      case Right(_)  => fail("Expected Left but got Right")
    }
  }

  // ── validateOutput ───────────────────────────────────────────────────────────

  "GuardrailApplicator.validateOutput" should "pass state unchanged when guardrail seq is empty" in {
    val state  = stateWithLastMessage("some response")
    val result = GuardrailApplicator.validateOutput(state, Seq.empty)
    result shouldBe Right(state)
  }

  it should "return Right(state) when output guardrail passes" in {
    val state  = stateWithLastMessage("valid response")
    val guards = Seq(passingOutputGuardrail("pass"))
    val result = GuardrailApplicator.validateOutput(state, guards)
    result shouldBe Right(state)
  }

  it should "return Left when output guardrail rejects" in {
    val state  = stateWithLastMessage("bad response")
    val guards = Seq(failingOutputGuardrail("reject", "content policy violated"))
    val result = GuardrailApplicator.validateOutput(state, guards)
    result shouldBe a[Left[_, _]]
  }

  it should "validate empty string when no assistant message is present" in {
    // A length-based output guardrail that requires at least 5 chars should fail on ""
    val minLengthGuardrail = new OutputGuardrail {
      val name = "min-length"
      def validate(value: String): Result[String] =
        if (value.length >= 5) Right(value)
        else Left(ValidationError.invalid("min-length", s"Too short: '${value}'"))
    }
    val state  = stateWithNoAssistantMessage
    val result = GuardrailApplicator.validateOutput(state, Seq(minLengthGuardrail))
    result shouldBe a[Left[_, _]]
  }

  it should "validate only the LAST assistant message when multiple are present" in {
    val capturedValues = scala.collection.mutable.ListBuffer[String]()

    val capturingGuardrail = new OutputGuardrail {
      val name = "capture"
      def validate(value: String): Result[String] = {
        capturedValues += value
        Right(value)
      }
    }

    val state = stateWithMultipleAssistantMessages
    GuardrailApplicator.validateOutput(state, Seq(capturingGuardrail))

    capturedValues should have size 1
    capturedValues.head shouldBe "final answer"
  }

  it should "include the failure reason from output validation in the error" in {
    val guards = Seq(failingOutputGuardrail("g", "JSON parse failed"))
    val state  = stateWithLastMessage("not json")
    GuardrailApplicator.validateOutput(state, guards) match {
      case Left(err) => err.message should include("JSON parse failed")
      case Right(_)  => fail("Expected Left but got Right")
    }
  }
}
