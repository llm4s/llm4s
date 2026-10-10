package org.llm4s.samples.cookbook

import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.model.{ AssistantMessage, MessageRole, ResponseFormat }
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Runs the judge recipe against scripted answers and grades. */
class JudgeRecipeSpec extends AnyFlatSpec with Matchers with EitherValues {

  private def grading(json: String) = new ScriptedClient((_, _) => Right(AssistantMessage(json)))

  "JudgeRecipe.evaluate" should "answer with the model, then grade the answer with the judge" in {
    val (model, judge) = (JudgeRecipe.script, JudgeRecipe.script)

    val (answer, verdict) = JudgeRecipe.evaluate(model, judge, "How much vacation?", "25 days").value

    answer should include("25 days")
    verdict shouldBe Verdict(4, "Gives the 25 days but leaves out the carry-over rule.")
    verdict.passed shouldBe true
    model.calls should have size 1
    judge.calls should have size 1
  }

  it should "show the judge the rubric, the question, the reference and the answer, at temperature 0, with a schema" in {
    val judge = JudgeRecipe.script

    JudgeRecipe.evaluate(JudgeRecipe.script, judge, "How much vacation?", "25 days a year").value

    val (conversation, options) = judge.calls.head
    conversation.messages.find(_.role == MessageRole.System).map(_.content) shouldBe Some(JudgeRecipe.Rubric)
    val toGrade = conversation.messages.filter(_.role == MessageRole.User).map(_.content).mkString
    Seq("How much vacation?", "25 days a year", "Employees get 25 days").foreach(toGrade should include(_))
    options.temperature shouldBe 0.0
    options.responseFormat.map(_.isInstanceOf[ResponseFormat.JsonSchema]) shouldBe Some(true)
  }

  "JudgeRecipe.judge" should "fail a low score" in {
    val verdict = JudgeRecipe.judge(grading("""{"score": 2, "reason": "Wrong number."}"""), "q", "25", "10").value

    verdict.passed shouldBe false
  }

  it should "refuse a grade that is not JSON or misses a field" in {
    JudgeRecipe.judge(grading("Looks good to me!"), "q", "r", "a").left.value shouldBe a[ValidationError]
    JudgeRecipe.judge(grading("""{"score": 5}"""), "q", "r", "a").left.value shouldBe a[ValidationError]
  }
}
