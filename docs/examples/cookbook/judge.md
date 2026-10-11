---
layout: page
title: Evaluate an answer with a judge
parent: Cookbook
grand_parent: Examples
nav_order: 10
---

# Evaluate an answer with a judge
{: .no_toc }

Ask a model a question, then have a judge model grade the answer against a reference, from 1 to 5.
{: .fs-6 .fw-300 }

## The problem

You changed a prompt or a model and want to know whether answers got better. People grading every answer does
not scale; a second model can, given a rubric and a reference answer. The judge returns a `Verdict` (a score and a
reason) through `completeStructured`, at temperature 0 so that the same answer gets the same grade.

## The program

The whole program, [`JudgeRecipe.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/cookbook/JudgeRecipe.scala). `script` is the stand-in for a model that answers without a
network or a key; `demo` runs the recipe and returns what to print.

```scala
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
```

## Run it

```bash
sbt "samples/runMain org.llm4s.samples.cookbook.JudgeRecipe"          # scripted client, no API key
sbt "samples/runMain org.llm4s.samples.cookbook.JudgeRecipe --live"   # the provider your configuration names
```

The first command needs nothing but sbt. The second uses the section `llm4s.providers.provider` names; see
[running the samples](../../getting-started/configuration#running-the-samples). CI runs every recipe against its scripted client, and checks
that the program on this page is the source file, so what you read here compiles and works.

## Use a real provider

Run with `--live`, which uses one provider as both model and judge. In an evaluation, make the judge a separate,
strong model, from a second named section (`Llm4sConfig.provider("judge")`), and run `evaluate` over a set of
questions with reference answers. For RAG pipelines, the RAGAS metrics in `llm4s-rag` (faithfulness, answer
relevancy, context precision) are judges of this kind; see [RAG evaluation](../../guide/rag-evaluation).

## Pitfalls

- A judge is a model and can be wrong. Check a sample of its grades by hand before trusting the averages.
- Judges tend to favour longer answers, and answers in their own style; a judge from the same model as the answerer
  is lenient with it.
- Compare against a reference, not "what you know": without one, the judge grades its own beliefs.
- Keep the rubric and the judge model fixed while you compare. Scores from different judges do not compare.

[Back to the cookbook](../cookbook)
