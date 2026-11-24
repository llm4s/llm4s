package org.llm4s.samples.tutorial.supportassistant

import org.llm4s.testkit.{ Reply, ScriptedLLMClient }

/**
 * Part 5 of the "Build a support assistant" tutorial (`docs/tutorials/support-assistant/part-5-safety-memory.md`):
 * guardrails that mask personal data and refuse prompt injection, a reply for every way a run can end, and a
 * conversation that remembers the turn before.
 *
 * Each `// snippet:` region is a Scala block of the page, word for word; `Part5Spec` runs the code and compares the
 * regions with the page.
 * {{{
 *   sbt "samples/runMain org.llm4s.samples.tutorial.supportassistant.Part5SafetyMemory"          # scripted, no key
 *   sbt "samples/runMain org.llm4s.samples.tutorial.supportassistant.Part5SafetyMemory --live"   # your provider
 * }}}
 */
object Part5SafetyMemory {

  // format: off
  // snippet: guardrails
  import org.llm4s.agent.Agent
  import org.llm4s.agent.guardrails.{ InputGuardrail, OutputGuardrail }
  import org.llm4s.agent.guardrails.builtin.{ LengthCheck, PIIMasker, PromptInjectionDetector }
  import org.llm4s.llmconnect.LLMClient
  import org.llm4s.toolapi.ToolRegistry
  import org.llm4s.types.Result

  val inputGuardrails: Seq[InputGuardrail]   = Seq(LengthCheck(1, 2000), PromptInjectionDetector(), PIIMasker())
  val outputGuardrails: Seq[OutputGuardrail] = Seq(PIIMasker())

  def safeAgent(client: LLMClient): Result[Agent] =
    for {
      order  <- Part3Tools.orderTool
      policy <- Part3Tools.policyTool
      registry <- ToolRegistry.of(Seq(order, policy))
      agent <- Agent
        .builder("support", client)
        .withSystemPrompt("You are the support assistant of Acme Shop. Use the tools; never guess an order's status.")
        .withTools(registry)
        .withInputGuardrails(inputGuardrails*)
        .withOutputGuardrails(outputGuardrails*)
        .build()
    } yield agent
  // end snippet
  // format: on

  // snippet: reply
  import org.llm4s.agent.{ AgentResult, AgentStatus }

  val handOver: String = "Sorry, I cannot help with that message. A colleague from the support team will reply."

  def reply(result: AgentResult): String =
    result.status match {
      case AgentStatus.Completed(answer) => answer
      case AgentStatus.Blocked(_, _)     => handOver
      case _                             => s"$handOver (the run ended ${result.status})"
    }
  // end snippet

  // snippet: memory
  def conversation(client: LLMClient, first: String, followUp: String): Result[(String, String)] =
    for {
      agent <- safeAgent(client)
      turn1 <- agent.run(first)
      turn2 <- agent.continueConversation(turn1, followUp)
    } yield (reply(turn1), reply(turn2))
  // end snippet

  val first: String    = "Hi, I'm jane@example.com. Where is my order A-1001?"
  val followUp: String = "And when will it arrive?"

  /** The model this part's demo talks to when it runs without `--live`: a tool call and an answer per turn. */
  def scripted(): ScriptedLLMClient =
    ScriptedLLMClient.sequence(
      Reply.toolCall("lookup_order", """{"orderId": "A-1001"}"""),
      Reply.text("Order A-1001 has shipped."),
      Reply.text("A shipped order arrives in 2 working days, so A-1001 should be with you soon.")
    )

  def main(args: Array[String]): Unit =
    TutorialRunner.run(args, scripted()) { client =>
      conversation(client, first, followUp).map((answer1, answer2) => s"$answer1\n$answer2")
    }
}
