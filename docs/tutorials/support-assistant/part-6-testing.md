---
layout: page
title: "6. Testing It"
parent: Build a Support Assistant
grand_parent: Tutorials
nav_order: 6
description: "Support assistant tutorial, part 6: test the LLM4S agent offline with a scripted fake model from llm4s-testkit, then score its answers with a judge model."
search_title: "Support assistant tutorial 6: testing an LLM agent"
---

# Part 6: testing it
{: .no_toc }

Every part so far ran against a scripted client. This part makes that a habit you can rely on: a fake model that
answers from the conversation, unit tests that check what the agent sent as well as what it answered, and an
evaluation in which a second model judges the assistant's answers against what they should say.
{: .fs-6 .fw-300 }

The code is
[`Part6Testing.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/tutorial/supportassistant/Part6Testing.scala)
and the test
[`SupportAgentSpec.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/test/scala/org/llm4s/samples/tutorial/supportassistant/SupportAgentSpec.scala).

## Two kinds of test

A unit test must give the same result on every run, cost nothing and need no network, so it cannot call a real model.
It replaces the model with a fake from [`llm4s-testkit`](../../guide/testing-with-the-testkit) and checks *your* code:
that the agent offers the right tools, runs them, masks what it should, and handles a failing provider. It says
nothing about whether a real model gives good answers.

An evaluation asks that second question. It runs the assistant against a real model over a fixed set of questions and
scores each answer. Because the scores come from a model, they vary a little between runs; you watch the pass rate
over time rather than expect every case to pass.

## A fake model that reads the conversation

The parts before used `ScriptedLLMClient.sequence`, which gives its replies in order. That breaks as soon as the
agent's calls change order. `ScriptedLLMClient.respondingTo` chooses each reply from the conversation instead, and the
testkit's extractors say where in a turn the call is: `EndsWithUser` matches the first call of a turn,
`AfterToolResult` the call after a tool ran, with the tool's name and its result.

```scala
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
```

The fake looks up whichever order the customer names, and answers from what the tool really returned, so a test
exercises `lookup_order` too. A message that no rule matches is not answered: the call returns an
`UnscriptedCallError` naming it, so a test fails loudly instead of passing on a reply nobody wrote.

## A test of the agent

The test runs Part 5's `safeAgent` against the fake. The client records every call, so the test checks what was sent
as well as what came back:

```scala
import org.llm4s.agent.graph.GraphError
import org.llm4s.error.RateLimitError
import org.llm4s.samples.tutorial.supportassistant.Part5SafetyMemory.safeAgent
import org.llm4s.samples.tutorial.supportassistant.Part6Testing.fakeModel
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SupportAgentSpec extends AnyFlatSpec with Matchers {

  "The support agent" should "look the order up before it answers" in {
    val model = fakeModel()

    val result = safeAgent(model).flatMap(_.run("Where is my order A-1002?"))

    result.map(_.answer) shouldBe Right(Some("Your order is processing."))
    model.callCount shouldBe 2
    model.calls.head.options.tools.map(_.name) should contain("lookup_order")
  }

  it should "never send the customer's email address to the model" in {
    val model = fakeModel()

    safeAgent(model).flatMap(_.run("I'm jane@example.com. Where is A-1001?")).isRight shouldBe true

    (every(model.calls.flatMap(_.lastUserText)) should not).include("jane@example.com")
  }

  it should "fail, not answer, when the provider is down" in {
    val model = ScriptedLLMClient.sequence(Reply.failure(RateLimitError(ProviderId("openai"))))

    val result = safeAgent(model).flatMap(_.run("Where is my order A-1001?"))

    result.left.toOption should matchPattern { case Some(GraphError.NodeFailed(_, _, _: RateLimitError)) => }
  }
}
```

- `model.calls` holds each request: its messages and its options, the tools offered included. `lastUserText` is the
  latest customer message the model saw, after the input guardrails ran.
- `Reply.failure` makes the provider fail with a real error type. An agent wraps it in a `GraphError.NodeFailed` that
  names the failing step; the scripted error is its `cause`.

Run it, with no key and in well under a second:

```bash
sbt "samples/testOnly *SupportAgentSpec"
```

The [testkit guide](../../guide/testing-with-the-testkit) has the rest: checking that every scripted reply was used,
streaming, usage and the context window, and the same fakes from MUnit, weaver, cats-effect and ZIO Test.

## An evaluation with a judge

An evaluation set is questions with what a right answer says. A judge model reads each answer beside that reference
and scores it from 0 to 1. `LLMFactualityGuardrail` from Part 5's guardrails package is exactly that judge: it asks
its client to rate the answer against a reference text and passes it when the score reaches the threshold.

```scala
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
```

The guardrail refuses an answer with a `ValidationError` when the score is below the threshold, and also when the
judge's reply is not a number: either way the case fails. Any other error, such as the judge's provider being down,
stops the evaluation, because a verdict nobody gave is not a fail.

Run it. Scripted, both the assistant and the judge are fakes, so this only shows the mechanics; with `--live` the
configured model answers and judges:

```bash
sbt "samples/runMain org.llm4s.samples.tutorial.supportassistant.Part6Testing"
sbt "samples/runMain org.llm4s.samples.tutorial.supportassistant.Part6Testing --live"
```

A few rules make an evaluation worth its cost:

- **Judge with a different model**, ideally a stronger one: a model grading its own answers is lenient. The judge is
  just another `LLMClient`, from another named section of your configuration.
- **Keep it out of `sbt test`.** It costs tokens and its scores move; run it on a schedule or before a release, and
  track the pass rate.
- **Write the references from real tickets**, including the ones the assistant got wrong. A case that has never
  failed tells you little.

For RAG answers, the [RAG evaluation guide](../../guide/rag-evaluation) scores faithfulness, relevance and context
precision with the RAGAS metrics.

`Part6Spec` checks the fake against all three orders, the evaluation with a judge that passes everything, one that
scores low or cannot score, and one whose provider fails.

## Exercise 6: fake the refund question

Write `Part6Exercise.refundModel` in
[`Exercises.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/tutorial/supportassistant/Exercises.scala):
a `ScriptedLLMClient` built with `respondingTo`. The first call of a turn whose message mentions "refund", in any case,
calls the tool `refund_policy` with the topic `refund`; the call after that tool's result answers with the policy's
`text`, word for word (the result is the JSON of Part 3's `Policy`). Any other message has no rule. Then run

```bash
sbt "samples/testOnly *Part6Spec"
```

The spec runs `safeAgent` on your fake with "Can I get a REFUND for A-1001?" and expects Part 3's refund policy as the
answer, then checks that a question about an order is left unscripted. The solution is `Part6Solution` in the
[`solutions`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/tutorial/supportassistant/solutions/Solutions.scala)
package.

Previous: [Part 5, safety and memory](part-5-safety-memory). Next: [Part 7, serving and observing it](part-7-serving).
