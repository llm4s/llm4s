package org.llm4s.samples.toolapi

import org.llm4s.agent.Agent
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.toolapi.builtin.BuiltinTools
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Compiles and runs the agent snippet of `docs/guide/builtin-tools.md`.
 *
 * `agent` and `agent-tools` are separate modules that do not depend on each other, so the tool snippets are
 * checked by `BuiltinToolsGuideSpec` in `agent-tools` and this one, which needs both, lives here. The client is a
 * script, not a model: it asks for the calculator once and then answers with what the tool returned, which shows
 * the registry built from a bundle really is what the agent runs its tool calls against.
 */
class BuiltinToolsGuideAgentSpec extends AnyFlatSpec with Matchers with EitherValues {

  private class CalculatorScript extends LLMClient {
    private var calls = 0

    private def reply(message: AssistantMessage): Completion =
      Completion(
        id = s"script-$calls",
        created = 0L,
        content = message.content,
        model = "script",
        message = message,
        toolCalls = message.toolCalls.toList
      )

    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
      calls += 1
      val toolOutput = conversation.messages.filter(_.role == MessageRole.Tool).lastOption.map(_.content)
      Right(toolOutput match {
        case None =>
          reply(
            AssistantMessage(
              contentOpt = None,
              toolCalls = Seq(
                ToolCall("call-1", "calculator", ujson.Obj("operation" -> "percentage", "a" -> 15, "b" -> 850))
              )
            )
          )
        case Some(output) => reply(AssistantMessage(s"The calculator returned $output"))
      })
    }

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)

    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 1024
  }

  "The agent snippet" should "run a tool call against a registry built from a bundle" in {
    val client = new CalculatorScript

    // The snippet of the guide, from here ...
    val result = for {
      tools <- BuiltinTools.coreSafe
      agent <- Agent.builder("calculator-agent", client).withTools(new ToolRegistry(tools)).build()
      state <- agent.run("What is 15% of 850?")
    } yield state
    // ... to here.

    val answer = result.value.answer.getOrElse(fail(s"the run did not complete: ${result.value.status}"))
    answer should include("127.5")
  }
}
