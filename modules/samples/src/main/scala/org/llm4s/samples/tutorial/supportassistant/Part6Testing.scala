package org.llm4s.samples.tutorial.supportassistant

/**
 * Part 6 of the "Build a support assistant" tutorial (`docs/tutorials/support-assistant/part-6-testing.md`): a fake
 * model that answers from the conversation, a test of the agent against it (`SupportAgentSpec`), and an evaluation
 * whose answers a judge model scores.
 *
 * Each `// snippet:` region is a Scala block of the page, word for word, as is the `// snippet:` region of
 * `SupportAgentSpec` in the tests; `Part6Spec` runs the code and compares the regions with the page.
 * {{{
 *   sbt "samples/runMain org.llm4s.samples.tutorial.supportassistant.Part6Testing"          # scripted, no key
 *   sbt "samples/runMain org.llm4s.samples.tutorial.supportassistant.Part6Testing --live"   # your provider
 * }}}
 */
object Part6Testing {

  // snippet: fake-model
  import org.llm4s.testkit.{ AfterToolResult, EndsWithUser, Reply, ScriptedLLMClient }

  val OrderNumber = "([A-Z]-[0-9]+)".r.unanchored
  val Status      = """"status":"([a-z]+)"""".r.unanchored

  def fakeModel(): ScriptedLLMClient =
    ScriptedLLMClient.respondingTo {
      case EndsWithUser(OrderNumber(orderId)) =>
        Reply.toolCall("lookup_order", s"""{"orderId": "$orderId"}""")
      case AfterToolResult("lookup_order", Status(status)) =>
        Reply.text(s"Your order is $status.")
      case AfterToolResult("lookup_order", _) =>
        Reply.text("I cannot find that order. Please check the number.")
    }
  // end snippet

  // snippet: eval
  import org.llm4s.agent.guardrails.builtin.LLMFactualityGuardrail
  import org.llm4s.error.ValidationError
  import org.llm4s.llmconnect.LLMClient
  import org.llm4s.types.Result

  case class EvalCase(question: String, reference: String)

  case class Verdict(question: String, answer: String, passed: Boolean)

  val evalCases: Seq[EvalCase] = Seq(
    EvalCase("Where is my order A-1001?", "Order A-1001 has shipped."),
    EvalCase("Where is my order A-1002?", "Order A-1002 is still processing: it has not shipped yet."),
    EvalCase("Where is my order Z-9?", "There is no order Z-9.")
  )

  def judged(judge: LLMClient, evalCase: EvalCase, answer: String): Result[Boolean] =
    LLMFactualityGuardrail(judge, evalCase.reference, threshold = 0.8).validate(answer) match {
      case Right(_)                 => Right(true)
      case Left(_: ValidationError) => Right(false)
      case Left(error)              => Left(error)
    }

  def evaluate(assistant: LLMClient, judge: LLMClient, cases: Seq[EvalCase]): Result[Seq[Verdict]] =
    Part5SafetyMemory.safeAgent(assistant).flatMap { agent =>
      cases.foldLeft[Result[Vector[Verdict]]](Right(Vector.empty)) { (verdicts, evalCase) =>
        for {
          done   <- verdicts
          result <- agent.run(evalCase.question)
          answer = Part5SafetyMemory.reply(result)
          passed <- judged(judge, evalCase, answer)
        } yield done :+ Verdict(evalCase.question, answer, passed)
      }
    }
  // end snippet

  /** The judge this part's demo asks when it runs without `--live`: it scores every answer 0.9. */
  def scriptedJudge(): ScriptedLLMClient = ScriptedLLMClient.always(Reply.text("0.9"))

  def report(verdicts: Seq[Verdict]): String = {
    val lines = verdicts.map(v => s"${if (v.passed) "PASS" else "FAIL"}  ${v.question}  ->  ${v.answer}")
    (lines :+ s"${verdicts.count(_.passed)} of ${verdicts.size} passed").mkString("\n")
  }

  def main(args: Array[String]): Unit =
    TutorialRunner.run(args, fakeModel()) { assistant =>
      val judge = if (args.contains("--live")) assistant else scriptedJudge()
      evaluate(assistant, judge, evalCases).map(report)
    }
}
