---
layout: page
title: "2. Typed Answers"
parent: Build a Support Assistant
grand_parent: Tutorials
nav_order: 2
description: "Support assistant tutorial, part 2: classify a customer's message into a typed Ticket case class with a derived JSON schema and completeStructuredOf."
search_title: "Support assistant tutorial 2: typed LLM answers in Scala"
---

# Part 2: typed answers
{: .no_toc }

Before anyone answers a message, the shop wants to know what it is about and how urgent it is, so that billing
problems reach the billing team first. This part asks the model for a `Ticket`, a case class, instead of text.
{: .fs-6 .fw-300 }

The code is
[`Part2TypedAnswers.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/tutorial/supportassistant/Part2TypedAnswers.scala).
It uses [`llm4s-schema-derivation`](../../guide/derived-schemas), which the samples already depend on.

## Describe the answer as a type

`derives SchemaOf` gives a type its JSON schema; `derives ReadWriter` (uPickle) reads the model's JSON into it. The
`@description` annotations go into the schema, where the model reads them.

```scala
import org.llm4s.schema.*
import upickle.default.ReadWriter

enum Category derives SchemaOf, ReadWriter {
  case Billing, Delivery, Returns, Other
}

@description("A customer's message to the support team, classified")
case class Ticket(
  @description("What the message is about") category: Category,
  @description("From 1 (can wait) to 5 (urgent)") urgency: Int,
  @description("The order number, for example A-1001, if the message gives one") orderId: Option[String],
  @description("The problem in one sentence") summary: String
) derives SchemaOf, ReadWriter
```

`Category` is an enum, so the schema limits the field to its four case names. `orderId` is an `Option`: the model may
send `null` when the customer gave no order number.

## Ask for a Ticket

`completeStructuredOf[Ticket]` sends the derived schema with the request and reads the reply into a `Ticket`. The
provider enforces the schema where it can (OpenAI, Gemini), and gets it as an instruction where it cannot.

```scala
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ Conversation, SystemMessage, UserMessage }
import org.llm4s.types.Result

def classify(client: LLMClient, message: String): Result[Ticket] =
  client.completeStructuredOf[Ticket](
    Conversation(Seq(SystemMessage("Classify this message to Acme Shop's support team."), UserMessage(message)))
  )
```

A reply that is not a `Ticket`, such as an unknown category, is a `Left`, never a half-filled value.

## Check what the schema cannot promise

The descriptions say the urgency is from 1 to 5, but a provider that only reads the schema as an instruction can still
answer 9. Check the values your program relies on before you use them:

```scala
import org.llm4s.error.ValidationError

def checked(ticket: Ticket): Result[Ticket] =
  Either.cond(
    1 <= ticket.urgency && ticket.urgency <= 5,
    ticket,
    ValidationError.invalid("urgency", s"${ticket.urgency} is outside 1 to 5")
  )

def triage(client: LLMClient, message: String): Result[Ticket] =
  classify(client, message).flatMap(checked)
```

Run the part:

```bash
sbt "samples/runMain org.llm4s.samples.tutorial.supportassistant.Part2TypedAnswers"
sbt "samples/runMain org.llm4s.samples.tutorial.supportassistant.Part2TypedAnswers --live"
```

## Exercise 2: extract a refund request

When a message asks for a refund, the refunds team needs the order, the amount and the reason. `RefundRequest` is
already declared, with its schema, in
[`Exercises.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/tutorial/supportassistant/Exercises.scala).
Write `Part2Exercise.refundRequest(client, message)`: ask for a `RefundRequest` with `completeStructuredOf`, and refuse
an amount that is not above zero with `ValidationError.invalid("amount", ...)`. Then run

```bash
sbt "samples/testOnly *Part2Spec"
```

The solution is `Part2Solution` in the
[`solutions`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/tutorial/supportassistant/solutions/Solutions.scala)
package.

Previous: [Part 1, a first call](part-1-first-call). Next: [Part 3, tools and an agent](part-3-tools).
