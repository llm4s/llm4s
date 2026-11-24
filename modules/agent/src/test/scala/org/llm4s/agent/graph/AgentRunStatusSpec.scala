package org.llm4s.agent.graph

import org.llm4s.testutil.TestTimeLimit

import org.llm4s.agent.{ Agent, AgentId, AgentRun, AgentStatus, AgentTracing }
import org.llm4s.agent.graph.toolloop.{ Messages, StoredMessage, TurnOutcome, TurnOutput }
import org.llm4s.error.{ NetworkError, ValidationError }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.*
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * `AgentRun.status` is the one derivation of a turn's status: `await` and tracing both map from it,
 * so tracing reports a turn `await` refuses as `failed`, never `completed`.
 */
class AgentRunStatusSpec extends AnyFlatSpec with TestTimeLimit with Matchers:

  private object Unused extends LLMClient:
    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
      Left(NetworkError("unused", None, "mock://llm"))
    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)
    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 1024

  private val loop = Agent.builder("assistant", Unused).build().fold(e => fail(e.message), identity).loop

  private def stateWith(messages: Message*): ThreadState =
    new ThreadState(
      Map(Messages.key.id -> Messages.key),
      Map(Messages.key.id -> messages.zipWithIndex.map((m, i) => StoredMessage(s"m$i", m)).toVector)
    )

  private def completed(state: ThreadState): RunResult[TurnOutput] =
    RunResult.Completed(state, TurnOutput(TurnOutcome.Completed, AgentId.unsafe("assistant")), 1)

  "AgentRun.status" should "refuse a completed turn without an assistant message, and tracing call it failed" in {
    val status = AgentRun.status(completed(stateWith(UserMessage("q"))), isBlock = false, loop)
    status should matchPattern { case Left(_: ValidationError) => }
    AgentTracing.ending(RunEvent.RunCompleted, status).name shouldBe "failed"
  }

  it should "complete a turn with its last assistant message, and tracing call it completed" in {
    val status =
      AgentRun.status(completed(stateWith(UserMessage("q"), AssistantMessage("a"))), isBlock = false, loop)
    status shouldBe Right(AgentStatus.Completed("a"))
    AgentTracing.ending(RunEvent.RunCompleted, status).name shouldBe "completed"
  }

  it should "map a turn that ended at the token budget to TokenLimitReached, and tracing call it token_limit_reached" in {
    val ended  = RunResult.Completed(stateWith(), TurnOutput(TurnOutcome.TokenLimitReached, AgentId.unsafe("a")), 1)
    val status = AgentRun.status(ended, isBlock = false, loop)
    status shouldBe Right(AgentStatus.TokenLimitReached)
    AgentTracing.ending(RunEvent.RunCompleted, status).name shouldBe "token_limit_reached"
  }

  it should "map a turn whose answer was cut off to Truncated with its last assistant message, and tracing call it truncated" in {
    val state  = stateWith(UserMessage("q"), AssistantMessage("half"))
    val ended  = RunResult.Completed(state, TurnOutput(TurnOutcome.Truncated, AgentId.unsafe("a")), 1)
    val status = AgentRun.status(ended, isBlock = false, loop)
    status shouldBe Right(AgentStatus.Truncated("half"))
    AgentTracing.ending(RunEvent.RunCompleted, status).name shouldBe "truncated"
  }

  "TaskRefusal.Truncated" should "describe a child's cut-off answer without its content, labelled truncated" in {
    import org.llm4s.agent.delegation.TaskRefusal
    TaskRefusal.Truncated.describe shouldBe "its answer was cut off at the model's output limit"
    TaskRefusal.label(TaskRefusal.Truncated) shouldBe "truncated"
  }

  "TurnState" should "round-trip a cut reply and a Truncated outcome, and read a turn stored before either as not cut" in {
    import org.llm4s.agent.graph.toolloop.TurnState
    val turn = TurnState(3, Some(TurnOutcome.Truncated), cut = true)
    upickle.default.read[TurnState](upickle.default.write(turn)) shouldBe turn
    upickle.default.read[TurnState]("""{"steps":2,"outcome":null}""") shouldBe TurnState(2, None)
  }

  "TurnOutcome.TokenLimitReached" should "round-trip through the turn's stored codec" in {
    val turn = org.llm4s.agent.graph.toolloop.TurnState(3, Some(TurnOutcome.TokenLimitReached))
    upickle.default.read[org.llm4s.agent.graph.toolloop.TurnState](upickle.default.write(turn)) shouldBe turn
  }
