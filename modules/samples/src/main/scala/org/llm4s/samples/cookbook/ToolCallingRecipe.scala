package org.llm4s.samples.cookbook

import org.llm4s.agent.{ Agent, AgentResult }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ AssistantMessage, MessageRole, ToolCall }
import org.llm4s.samples.util.AgentResults
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.toolapi.builtin.BuiltinTools
import org.llm4s.types.Result

/**
 * Recipe: an agent that calls a tool.
 *
 * The agent is given the built-in calculator, the model asks for it, the library runs it, and the model answers from
 * what it returned. Run it with
 * {{{
 *   sbt "samples/runMain org.llm4s.samples.cookbook.ToolCallingRecipe"          # scripted client, no API key
 *   sbt "samples/runMain org.llm4s.samples.cookbook.ToolCallingRecipe --live"   # your configured provider
 * }}}
 */
object ToolCallingRecipe extends RecipeApp {

  val info: RecipeInfo = RecipeInfo(
    id = "tool-calling",
    title = "An agent that calls a tool",
    summary = "Give an agent the built-in calculator and let it decide when to use it.",
    mainClass = "org.llm4s.samples.cookbook.ToolCallingRecipe"
  )

  val question: String = "What is 15% of 850?"

  // snippet:start
  def run(client: LLMClient, question: String): Result[AgentResult] =
    for {
      tools  <- BuiltinTools.coreSafe
      agent  <- Agent.builder("calculator-agent", client).withTools(new ToolRegistry(tools)).build()
      result <- agent.run(question)
    } yield result
  // snippet:end

  /** The first call asks for the calculator; the second answers with what the tool returned. */
  def script: ScriptedClient = new ScriptedClient((conversation, _) => {
    val toolOutput = conversation.messages.filter(_.role == MessageRole.Tool).lastOption.map(_.content)
    Right(toolOutput match {
      case None =>
        AssistantMessage(
          contentOpt = None,
          toolCalls = Seq(
            ToolCall("call-1", "calculator", ujson.Obj("operation" -> "percentage", "a" -> 15, "b" -> 850))
          )
        )
      case Some(output) => AssistantMessage(s"The calculator returned $output")
    })
  })

  def demo(client: LLMClient): Result[String] =
    run(client, question).flatMap(AgentResults.requireCompleted)
}
