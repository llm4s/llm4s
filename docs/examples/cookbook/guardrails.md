---
layout: page
title: A guardrailed chatbot
parent: Cookbook
grand_parent: Examples
nav_order: 6
---

# A guardrailed chatbot
{: .no_toc }

Reject a bad request before the model sees it, and an over-long answer before the user does.
{: .fs-6 .fw-300 }

## The problem

A chatbot open to users needs limits on what goes in and what comes out. Input guardrails run before the model
sees the text, output guardrails on the final answer, and a request that fails an input guardrail never reaches the
model, so it costs nothing. Here the agent refuses requests over 200 characters or with a listed word, and answers
over 300 characters.

## The program

The whole program, [`GuardrailsRecipe.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/cookbook/GuardrailsRecipe.scala). `script` is the stand-in for a model that answers without a
network or a key; `demo` runs the recipe and returns what to print.

```scala
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
    title = "A guardrailed chatbot",
    summary = "Reject a bad request before the model sees it, and an over-long answer before the user does.",
    mainClass = "org.llm4s.samples.cookbook.GuardrailsRecipe"
  )

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
```

## Run it

```bash
sbt "samples/runMain org.llm4s.samples.cookbook.GuardrailsRecipe"          # scripted client, no API key
sbt "samples/runMain org.llm4s.samples.cookbook.GuardrailsRecipe --live"   # the provider your configuration names
```

The first command needs nothing but sbt. The second uses the section `llm4s.providers.provider` names; see
[running the samples](../../getting-started/configuration#running-the-samples). CI runs every recipe against its scripted client, and checks
that the program on this page is the source file, so what you read here compiles and works.

## Use a real provider

Run with `--live`, or build the agent on your provider's client. For checks code cannot do, such as "is this
answer on topic", the LLM-as-judge guardrails (`LLMSafetyGuardrail`, `LLMQualityGuardrail`, ...) in
`org.llm4s.agent.guardrails.builtin` take a client of their own, often a smaller, cheaper model. See
[Guardrails](../../guide/agents/guardrails).

## Pitfalls

- An output guardrail cannot save the cost of the call: it has already been made.
- A refused request is a completed run with a refusal, not an error: check `AgentResult.status`, as
  `AgentResults.answerOrStatus` does, rather than only the `Either`.
- Word lists are easy to get around ("h3ck"). Use them for obvious cases and an LLM guardrail for the rest.
- An LLM-as-judge guardrail adds a model call to every turn it checks.

[Back to the cookbook](../cookbook)
