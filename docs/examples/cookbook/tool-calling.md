---
layout: page
title: An agent that calls two tools
parent: Cookbook
grand_parent: Examples
nav_order: 5
---

# An agent that calls two tools
{: .no_toc }

Give an agent an exchange-rate tool of its own and the built-in calculator, and let the model chain them.
{: .fs-6 .fw-300 }

## The problem

The model cannot know today's exchange rate, and is unreliable at arithmetic. Give it tools: it asks for the
rate, the agent runs the tool and shows the model the result, then the model asks the calculator to multiply, and
answers from that. `ToolBuilder` defines a tool from a name, a description, a parameter schema and a handler that
returns `Either[String, A]`; the calculator comes from `llm4s-agent-tools`. The agent loops until the model answers
without asking for a tool.

## The program

The whole program, [`ToolCallingRecipe.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/cookbook/ToolCallingRecipe.scala). `script` is the stand-in for a model that answers without a
network or a key; `demo` runs the recipe and returns what to print.

```scala
package org.llm4s.samples.cookbook

import org.llm4s.agent.{ Agent, AgentResult }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ AssistantMessage, MessageRole, ToolCall }
import org.llm4s.samples.util.AgentResults
import org.llm4s.toolapi.builtin.core.CalculatorTool
import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolFunction, ToolRegistry }
import org.llm4s.types.Result
import upickle.default.ReadWriter

final case class ExchangeRate(from: String, to: String, rate: Double) derives ReadWriter

/** Recipe: an agent that calls two tools, one of its own and the built-in calculator, to answer one question. */
object ToolCallingRecipe extends RecipeApp {

  val info: RecipeInfo = RecipeInfo(
    id = "tool-calling",
    title = "An agent that calls two tools",
    summary = "Give an agent an exchange-rate tool and the calculator, and let the model chain them.",
    mainClass = "org.llm4s.samples.cookbook.ToolCallingRecipe"
  )

  val question: String = "How much is 120 US dollars in euros?"

  val rates: Map[(String, String), Double] = Map(("USD", "EUR") -> 0.92, ("EUR", "USD") -> 1.09)

  val exchangeRate: Result[ToolFunction[Map[String, Any], ExchangeRate]] =
    ToolBuilder[Map[String, Any], ExchangeRate](
      "exchange_rate",
      "Today's exchange rate from one currency to another",
      Schema
        .`object`[Map[String, Any]]("A currency pair")
        .withRequiredField("from", Schema.string("ISO 4217 code of the currency to convert from, e.g. USD"))
        .withRequiredField("to", Schema.string("ISO 4217 code of the currency to convert to, e.g. EUR"))
    ).withHandler { params =>
      for {
        from <- params.getString("from").map(_.toUpperCase)
        to   <- params.getString("to").map(_.toUpperCase)
        rate <- rates.get((from, to)).toRight(s"no rate from $from to $to")
      } yield ExchangeRate(from, to, rate)
    }.buildSafe()

  def run(client: LLMClient, question: String): Result[AgentResult] =
    for {
      rateTool   <- exchangeRate
      calculator <- CalculatorTool.toolSafe
      agent  <- Agent.builder("currency-agent", client).withTools(new ToolRegistry(Seq(rateTool, calculator))).build()
      result <- agent.run(question)
    } yield result

  /** Asks for the rate, then for the calculator with that rate, then answers with the calculator's result. */
  def script: ScriptedClient = new ScriptedClient((conversation, _) => {
    val outputs = conversation.messages.filter(_.role == MessageRole.Tool).map(m => ujson.read(m.content))
    def call(name: String, args: ujson.Obj) = AssistantMessage(None, Seq(ToolCall(s"call-$name", name, args)))
    Right(outputs.size match {
      case 0 => call("exchange_rate", ujson.Obj("from" -> "USD", "to" -> "EUR"))
      case 1 => call("calculator", ujson.Obj("operation" -> "multiply", "a" -> 120, "b" -> outputs(0)("rate").num))
      case _ => AssistantMessage(s"120 US dollars is ${outputs(1)("formatted").str} euros at today's rate.")
    })
  })

  def demo(client: LLMClient): Result[String] =
    run(client, question).flatMap(AgentResults.requireCompleted)
}
```

## Run it

```bash
sbt "samples/runMain org.llm4s.samples.cookbook.ToolCallingRecipe"          # scripted client, no API key
sbt "samples/runMain org.llm4s.samples.cookbook.ToolCallingRecipe --live"   # the provider your configuration names
```

The first command needs nothing but sbt. The second uses the section `llm4s.providers.provider` names; see
[running the samples](../../getting-started/configuration#running-the-samples). CI runs every recipe against its scripted client, and checks
that the program on this page is the source file, so what you read here compiles and works.

## Use a real provider

Run with `--live`, or build the agent on your provider's client. Tool calling needs a model that supports it
(GPT-4o, Claude, Gemini and most current models do; small local models often do not). Give the agent a step limit in
production with `Agent.builder(...).withMaxSteps(n)` so that a model that keeps asking for tools stops.

## Pitfalls

- The description and parameter descriptions are all the model knows about a tool. Say what it returns and in
  what units.
- A handler's `Left` is shown to the model as the tool's error, not raised: the model can try again or explain.
  The spec checks this for a currency pair with no rate.
- Tools run with your program's permissions. Validate arguments in the handler; never pass them to a shell or a
  query unchecked.
- Each tool round trip is another model call, with the whole thread so far: a chain of tools costs more than one
  answer.

[Back to the cookbook](../cookbook)
