---
layout: page
title: Extract structured data from an email
parent: Cookbook
grand_parent: Examples
nav_order: 2
---

# Extract structured data from an email
{: .no_toc }

Read an order change out of a free-text email into a case class with a list of line items.
{: .fs-6 .fw-300 }

## The problem

Emails carry structured requests in unstructured text: who is asking, which order, what products, by when. You
want those as values your code can act on, including a list whose length you do not know in advance. A nested schema
(an object with an array of objects) describes that, and `completeStructured` reads the reply into `OrderRequest`.

## The program

The whole program, [`EmailExtractionRecipe.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/cookbook/EmailExtractionRecipe.scala). `script` is the stand-in for a model that answers without a
network or a key; `demo` runs the recipe and returns what to print.

```scala
package org.llm4s.samples.cookbook

import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ AssistantMessage, Conversation, SystemMessage, UserMessage }
import org.llm4s.toolapi.Schema
import org.llm4s.types.Result
import upickle.default.ReadWriter

final case class LineItem(product: String, quantity: Int) derives ReadWriter

/** What a customer asked for in an email, as the model is asked to produce it. */
final case class OrderRequest(customer: String, orderNumber: String, items: Seq[LineItem], deliverBy: String)
    derives ReadWriter

/** Recipe: extract structured data, with a nested list, from an email; then check it against the email. */
object EmailExtractionRecipe extends RecipeApp {

  val info: RecipeInfo = RecipeInfo(
    id = "email-extraction",
    title = "Extract structured data from an email",
    summary = "Read an order request out of a free-text email into a case class with a list of line items.",
    mainClass = "org.llm4s.samples.cookbook.EmailExtractionRecipe"
  )

  val email: String =
    """From: Dana Whitfield <dana@harbour-cafe.example>
      |Subject: Re: order HC-2291
      |
      |Hi, could you change order HC-2291 to 12 bags of the house blend and 3 boxes of oat milk?
      |We need it by 14 November 2026 at the latest. Thanks, Dana (Harbour Cafe)""".stripMargin

  val orderSchema = Schema
    .`object`[OrderRequest]("An order request taken from an email")
    .withRequiredField("customer", Schema.string("The business that sent the email"))
    .withRequiredField("orderNumber", Schema.string("The order number exactly as written in the email"))
    .withRequiredField(
      "items",
      Schema.array(
        "Every product asked for",
        Schema
          .`object`[LineItem]("One product and how many")
          .withRequiredField("product", Schema.string("The product name"))
          .withRequiredField("quantity", Schema.integer("How many").withRange(Some(1), None))
      )
    )
    .withRequiredField("deliverBy", Schema.string("The latest delivery date as YYYY-MM-DD, or \"\" if none is given"))

  def extract(client: LLMClient, email: String): Result[OrderRequest] = {
    val prompt = Conversation(
      Seq(SystemMessage("Extract the order request from the email. Use only what the email says."), UserMessage(email))
    )
    for {
      order <- client.completeStructured[OrderRequest](prompt, orderSchema)
      // A model can produce a well-formed value that is not in the text: check what you can against the source.
      _ <- Either.cond(
        email.contains(order.orderNumber),
        (),
        ValidationError.invalid("orderNumber", s"'${order.orderNumber}' does not appear in the email")
      )
      _ <- Either.cond(order.items.nonEmpty, (), ValidationError.invalid("items", "the email asks for nothing"))
    } yield order
  }

  /** Answers with the JSON a model following the schema would produce for [[email]]. */
  def script: ScriptedClient = new ScriptedClient((_, _) =>
    Right(AssistantMessage("""{"customer": "Harbour Cafe", "orderNumber": "HC-2291", "deliverBy": "2026-11-14",
      |"items": [{"product": "house blend", "quantity": 12}, {"product": "oat milk", "quantity": 3}]}""".stripMargin))
  )

  def demo(client: LLMClient): Result[String] =
    extract(client, email).map { order =>
      val items = order.items.map(item => s"${item.quantity} x ${item.product}").mkString(", ")
      s"${order.customer}, order ${order.orderNumber}: $items, deliver by ${order.deliverBy}"
    }
}
```

## Run it

```bash
sbt "samples/runMain org.llm4s.samples.cookbook.EmailExtractionRecipe"          # scripted client, no API key
sbt "samples/runMain org.llm4s.samples.cookbook.EmailExtractionRecipe --live"   # the provider your configuration names
```

The first command needs nothing but sbt. The second uses the section `llm4s.providers.provider` names; see
[running the samples](../../getting-started/configuration#running-the-samples). CI runs every recipe against its scripted client, and checks
that the program on this page is the source file, so what you read here compiles and works.

## Use a real provider

Pass the client of your provider to `extract`, or run with `--live`. Long emails, threads and signatures cost
tokens: strip quoted history before you send the text if you only need the latest message. A provider that enforces
schemas (OpenAI, Gemini) returns well-formed JSON every time; the others are asked to.

## Pitfalls

- A well-formed value is not a true one. A model can produce a plausible order number that is not in the email,
  so the recipe checks it against the text. Check whatever you can against the source.
- Say what to do when a field is absent. The schema asks for `""` when there is no delivery date; without that the
  model invents one. (A required field is safer than an optional one: providers that enforce schemas in strict mode
  require every field.)
- Ask for dates in one format (`YYYY-MM-DD`) and parse them yourself; "next Friday" needs the date the email was sent.

[Back to the cookbook](../cookbook)
