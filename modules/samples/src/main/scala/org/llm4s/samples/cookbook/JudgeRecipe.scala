package org.llm4s.samples.cookbook

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.toolapi.Schema
import org.llm4s.types.Result
import upickle.default.ReadWriter

/** A judge's grade for one answer. */
final case class Verdict(score: Int, reason: String) derives ReadWriter {
  def passed: Boolean = score >= 4
}

/** Recipe: grade an answer against a reference answer with a second model call (LLM-as-judge). */
object JudgeRecipe extends RecipeApp {

  val info: RecipeInfo = RecipeInfo(
    id = "judge",
    title = "Evaluate an answer with a judge",
    summary = "Ask a model a question, then have a judge model grade the answer against a reference, 1 to 5.",
    mainClass = "org.llm4s.samples.cookbook.JudgeRecipe"
  )

  val Rubric: String =
    """You grade answers. Compare the answer with the reference answer, not with what you know.
      |5: says everything the reference says, and nothing that contradicts it. 4: a minor omission.
      |3: partly right. 2: mostly wrong. 1: wrong, or does not answer the question.""".stripMargin

  val verdictSchema = Schema
    .`object`[Verdict]("A grade for one answer")
    .withRequiredField("score", Schema.integer("The grade from the rubric, 1 to 5").withRange(Some(1), Some(5)))
    .withRequiredField("reason", Schema.string("One sentence explaining the grade"))

  def judge(judgeModel: LLMClient, question: String, reference: String, answer: String): Result[Verdict] = {
    val toGrade = s"Question: $question\nReference answer: $reference\nAnswer to grade: $answer"
    judgeModel.completeStructured[Verdict](
      Conversation(Seq(SystemMessage(Rubric), UserMessage(toGrade))),
      verdictSchema,
      CompletionOptions().withTemperature(0.0) // the same answer should get the same grade
    )
  }

  /** Answers `question` with `model` and grades the answer with `judgeModel`. */
  def evaluate(
    model: LLMClient,
    judgeModel: LLMClient,
    question: String,
    reference: String
  ): Result[(String, Verdict)] =
    for {
      answer  <- model.complete(Conversation(Seq(UserMessage(question)))).map(_.content)
      verdict <- judge(judgeModel, question, reference, answer)
    } yield (answer, verdict)

  /** Answers the question as the model; grades as the judge when it is given the rubric. */
  def script: ScriptedClient = new ScriptedClient((conversation, _) => {
    val judging = conversation.messages.exists(m => m.role == MessageRole.System && m.content == Rubric)
    Right(
      AssistantMessage(
        if (judging) """{"score": 4, "reason": "Gives the 25 days but leaves out the carry-over rule."}"""
        else "Employees get 25 days of paid vacation a year."
      )
    )
  })

  def demo(client: LLMClient): Result[String] =
    evaluate(
      client,
      client,
      "How much paid vacation do employees get?",
      "25 days a year; unused days carry over until 31 March."
    ).map((answer, v) => s"answer: $answer\nscore: ${v.score} (${if (v.passed) "pass" else "fail"}) - ${v.reason}")
}
