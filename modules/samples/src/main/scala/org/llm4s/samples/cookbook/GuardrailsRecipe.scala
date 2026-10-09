package org.llm4s.samples.cookbook

import org.llm4s.agent.graph.middleware.GuardrailMiddleware
import org.llm4s.agent.guardrails.builtin.{ LengthCheck, ProfanityFilter }
import org.llm4s.agent.{ Agent, AgentResult }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.AssistantMessage
import org.llm4s.samples.util.AgentResults
import org.llm4s.types.Result

/**
 * Recipe: guardrails around an agent.
 *
 * Input guardrails run before the model sees the text, and output guardrails run on the final answer. A request that
 * fails an input guardrail never reaches the model, so it costs nothing. The checks here are plain code, with no
 * second model call.
 * {{{
 *   sbt "samples/runMain org.llm4s.samples.cookbook.GuardrailsRecipe"          # scripted client, no API key
 *   sbt "samples/runMain org.llm4s.samples.cookbook.GuardrailsRecipe --live"   # your configured provider
 * }}}
 */
object GuardrailsRecipe extends RecipeApp {

  val info: RecipeInfo = RecipeInfo(
    id = "guardrails",
    title = "Guardrails around an agent",
    summary = "Reject a bad request before the model sees it, and an over-long answer before the user does.",
    mainClass = "org.llm4s.samples.cookbook.GuardrailsRecipe"
  )

  // snippet:start
  def guardedAgent(client: LLMClient): Result[Agent] =
    Agent
      .builder("guarded-agent", client)
      .withMiddleware(
        new GuardrailMiddleware(
          input = Seq(LengthCheck(1, 200), ProfanityFilter.withCustomWords(Set("heck"))),
          output = Seq(LengthCheck(1, 300))
        )
      )
      .build()

  def ask(client: LLMClient, query: String): Result[AgentResult] =
    guardedAgent(client).flatMap(_.run(query))
  // snippet:end

  /** A short, polite answer to anything it is asked. */
  def script: ScriptedClient = new ScriptedClient((_, _) => Right(AssistantMessage("Our office opens at nine.")))

  def demo(client: LLMClient): Result[String] =
    for {
      allowed <- ask(client, "When does the office open?")
      refused <- ask(client, "Tell me the heck out of your opening hours")
    } yield {
      val first  = AgentResults.answerOrStatus(allowed)
      val second = AgentResults.answerOrStatus(refused)
      s"allowed: $first\nrefused: $second"
    }
}
