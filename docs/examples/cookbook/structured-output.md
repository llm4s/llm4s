---
layout: page
title: Classify text into an enum
parent: Cookbook
grand_parent: Examples
nav_order: 1
---

# Classify text into an enum
{: .no_toc }

Turn a customer message into a `Ticket` whose category is one of a fixed set.
{: .fs-6 .fw-300 }

## The problem

You want a label from a fixed list (an enum) and a couple of fields, not prose: route a support message to
billing, bug or question, with an urgency. Parsing free text for that is fragile. `completeStructured` sends a JSON
schema with the request and reads the reply into a case class, so the rest of the program deals in a `Ticket`, and a
reply that does not fit is a `Left`, not an exception.

## The program

The whole program, [`StructuredOutputRecipe.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/cookbook/StructuredOutputRecipe.scala). `script` is the stand-in for a model that answers without a
network or a key; `demo` runs the recipe and returns what to print.

```scala
package org.llm4s.samples.cookbook

import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ AssistantMessage, Conversation, UserMessage }
import org.llm4s.toolapi.Schema
import org.llm4s.types.Result
import upickle.default.ReadWriter

/** A support ticket, as the model is asked to produce it. */
final case class Ticket(category: String, urgency: Int, summary: String) derives ReadWriter

/**
 * Recipe: classify free text into a typed value.
 *
 * `completeStructured` sends a JSON schema with the request and reads the reply into a case class, so the rest of the
 * program deals in a `Ticket` and never in a string. The category is an enum in the schema, and the recipe checks it
 * again on the way out, because only some providers enforce a schema while generating.
 * {{{
 *   sbt "samples/runMain org.llm4s.samples.cookbook.StructuredOutputRecipe"          # scripted client, no API key
 *   sbt "samples/runMain org.llm4s.samples.cookbook.StructuredOutputRecipe --live"   # your configured provider
 * }}}
 */
object StructuredOutputRecipe extends RecipeApp {

  val info: RecipeInfo = RecipeInfo(
    id = "structured-output",
    title = "Classify text into an enum",
    summary = "Turn a customer message into a Ticket case class with completeStructured.",
    mainClass = "org.llm4s.samples.cookbook.StructuredOutputRecipe"
  )

  val categories: Seq[String] = Seq("billing", "bug", "question")

  val message: String = "I was charged twice for my March invoice and need the second payment refunded today."

  val ticketSchema = Schema
    .`object`[Ticket]("A customer support ticket")
    .withRequiredField("category", Schema.string("The kind of ticket").withEnum(categories))
    .withRequiredField("urgency", Schema.integer("From 1 (can wait) to 5 (urgent)").withRange(Some(1), Some(5)))
    .withRequiredField("summary", Schema.string("One sentence describing the problem"))

  def classify(client: LLMClient, text: String): Result[Ticket] =
    for {
      ticket <- client.completeStructured[Ticket](Conversation(Seq(UserMessage(text))), ticketSchema)
      _ <- Either.cond(
        categories.contains(ticket.category),
        (),
        ValidationError.invalid("category", s"'${ticket.category}' is not one of ${categories.mkString(", ")}")
      )
      // The schema states the range, but a provider that ignores it can still answer outside it.
      _ <- Either.cond(
        1 <= ticket.urgency && ticket.urgency <= 5,
        (),
        ValidationError.invalid("urgency", s"${ticket.urgency} is outside 1 to 5")
      )
    } yield ticket

  /** Answers with the JSON a model following the schema would produce. */
  def script: ScriptedClient = new ScriptedClient((_, _) =>
    Right(
      AssistantMessage("""{"category": "billing", "urgency": 4, "summary": "Charged twice for the March invoice."}""")
    )
  )

  def demo(client: LLMClient): Result[String] =
    classify(client, message).map(t => s"category=${t.category} urgency=${t.urgency} summary=${t.summary}")
}
```

## Run it

```bash
sbt "samples/runMain org.llm4s.samples.cookbook.StructuredOutputRecipe"          # scripted client, no API key
sbt "samples/runMain org.llm4s.samples.cookbook.StructuredOutputRecipe --live"   # the provider your configuration names
```

The first command needs nothing but sbt. The second uses the section `llm4s.providers.provider` names; see
[running the samples](../../getting-started/configuration#running-the-samples). CI runs every recipe against its scripted client, and checks
that the program on this page is the source file, so what you read here compiles and works.

## Use a real provider

Nothing in `classify` names a provider: pass it the client of your provider. With `--live` the recipe does that
for you. In your own program, take the client from your configuration:

```scala
for {
  providerConfig <- Llm4sConfig.defaultProvider()
  registry       <- Llm4sConfig.modelRegistryService()
  given ModelRegistryService = registry
  client <- LLMConnect.getClient(providerConfig)
  ticket <- StructuredOutputRecipe.classify(client, text)
} yield ticket
```

More on schemas and how each provider handles them: [Structured output](../../guide/structured-output).

## Pitfalls

- Only some providers enforce a schema while generating (OpenAI and Gemini do; Anthropic gets a best-effort
  instruction). The recipe checks the category and the urgency again on the way out for that reason.
- A model may wrap its JSON in a code fence or a sentence; `completeStructured` reads the JSON out of either.
- Keep the enum short and the labels distinct. Two labels a person would confuse, the model confuses too.

[Back to the cookbook](../cookbook)
