package org.llm4s.agent.graph

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
class AgentRunStatusSpec extends AnyFlatSpec with Matchers:

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
    AgentTracing.label(RunEvent.RunCompleted, status) shouldBe "failed"
  }

  it should "complete a turn with its last assistant message, and tracing call it completed" in {
    val status =
      AgentRun.status(completed(stateWith(UserMessage("q"), AssistantMessage("a"))), isBlock = false, loop)
    status shouldBe Right(AgentStatus.Completed("a"))
    AgentTracing.label(RunEvent.RunCompleted, status) shouldBe "completed"
  }
