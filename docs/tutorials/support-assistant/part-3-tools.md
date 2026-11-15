---
layout: page
title: "3. Tools and an Agent"
parent: Build a Support Assistant
grand_parent: Tutorials
nav_order: 3
description: "Support assistant tutorial, part 3: typed tools that look up an order and the refund policy, and an LLM4S agent that calls them before it answers."
search_title: "Support assistant tutorial 3: tools and an agent in Scala"
---

# Part 3: tools and an agent
{: .no_toc }

"Where is my order?" has an answer the model cannot know. This part gives the assistant two tools, an order lookup
and the refund policy, and an agent that decides when to call them.
{: .fs-6 .fw-300 }

The code is
[`Part3Tools.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/tutorial/supportassistant/Part3Tools.scala).

## A typed tool

`Tool.of` builds a tool from a function over a case class: the parameters' schema is derived from `OrderQuery`, the
call's arguments are read into it before your function runs, and the result is written as JSON. A `Left` is the error
the model sees, so it can tell the customer the order number looks wrong.

```scala
import org.llm4s.schema.*
import org.llm4s.toolapi.ToolFunction
import org.llm4s.types.Result
import upickle.default.ReadWriter

@description("Which order to look up")
case class OrderQuery(@description("The order number, for example A-1001") orderId: String)
  derives SchemaOf, ReadWriter

case class OrderStatus(orderId: String, status: String, total: Double) derives ReadWriter

val orders: Map[String, OrderStatus] = Map(
  "A-1001" -> OrderStatus("A-1001", "shipped", 42.50),
  "A-1002" -> OrderStatus("A-1002", "processing", 18.00)
)

val orderTool: Result[ToolFunction[OrderQuery, OrderStatus]] =
  Tool.of[OrderQuery, OrderStatus]("lookup_order", "The status and total of a customer's order") { query =>
    orders.get(query.orderId).toRight(s"There is no order ${query.orderId}")
  }
```

A real shop would read its order database here; the map keeps the tutorial offline.

## A second tool

```scala
@description("Which policy the customer is asking about")
case class PolicyQuery(@description("One of: refund, return, exchange") topic: String) derives SchemaOf, ReadWriter

case class Policy(topic: String, text: String) derives ReadWriter

val policies: Map[String, String] = Map(
  "refund"   -> "Refunds go back to the original payment method within 5 working days.",
  "return"   -> "Items can be returned within 30 days of delivery, unused and in their packaging.",
  "exchange" -> "Exchanges are free within 30 days of delivery."
)

val policyTool: Result[ToolFunction[PolicyQuery, Policy]] =
  Tool.of[PolicyQuery, Policy]("refund_policy", "Acme Shop's policy on refunds, returns and exchanges") { query =>
    val topic = query.topic.toLowerCase
    policies
      .get(topic)
      .map(Policy(topic, _))
      .toRight(s"No policy on ${query.topic}: ask for refund, return or exchange")
  }
```

## An agent that uses them

An agent sends the question with the tools' schemas, runs each tool the model calls, sends the results back, and
repeats until the model answers. You build it once and run it per question:

```scala
import org.llm4s.agent.Agent
import org.llm4s.llmconnect.LLMClient
import org.llm4s.toolapi.ToolRegistry

def supportAgent(client: LLMClient): Result[Agent] =
  for {
    order  <- orderTool
    policy <- policyTool
    agent <- Agent
      .builder("support", client)
      .withSystemPrompt("You are the support assistant of Acme Shop. Use the tools; never guess an order's status.")
      .withTools(new ToolRegistry(Seq(order, policy)))
      .build()
  } yield agent

def answer(client: LLMClient, question: String): Result[String] =
  for {
    agent  <- supportAgent(client)
    result <- agent.run(question)
  } yield result.answer.getOrElse(s"The run ended without an answer: ${result.status}")
```

`result.answer` is the final answer when the run completed. A run can also end blocked by a guardrail or at its step
limit; `result.status` says which, and Part 5 adds the guardrails.

Run the part. The scripted model calls `lookup_order`, then `refund_policy`, then answers:

```bash
sbt "samples/runMain org.llm4s.samples.tutorial.supportassistant.Part3Tools"
sbt "samples/runMain org.llm4s.samples.tutorial.supportassistant.Part3Tools --live"
```

`Part3Spec` checks more than the answer: that both tools were offered to the model, and that the tool results the
agent sent back contain the order's status and the policy.

## Exercise 3: a delivery estimate

Write `Part3Exercise.deliveryTool` in
[`Exercises.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/tutorial/supportassistant/Exercises.scala):
a tool named `delivery_estimate`, built with `Tool.of` over the declared `DeliveryQuery` and `DeliveryEstimate`, that
answers from `Part3Tools.orders`. An order that has `shipped` arrives in 2 working days, one still `processing` in 4,
and an unknown order number is the error `There is no order <orderId>`. Then run

```bash
sbt "samples/testOnly *Part3Spec"
```

For a bonus, add your tool to `supportAgent` and ask "When will A-1002 arrive?" with `--live`. The solution is
`Part3Solution` in the
[`solutions`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/tutorial/supportassistant/solutions/Solutions.scala)
package.

Previous: [Part 2, typed answers](part-2-typed-answers). The next parts (help-centre answers with RAG, safety and
memory, testing, serving) are tracked in [#2292](https://github.com/llm4s/llm4s/issues/2292).
