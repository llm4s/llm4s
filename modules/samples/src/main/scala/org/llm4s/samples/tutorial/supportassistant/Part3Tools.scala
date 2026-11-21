package org.llm4s.samples.tutorial.supportassistant

import org.llm4s.testkit.{ Reply, ScriptedLLMClient }

/**
 * Part 3 of the "Build a support assistant" tutorial (`docs/tutorials/support-assistant/part-3-tools.md`): two typed
 * tools, an order lookup and the refund policy, and an agent that uses them.
 *
 * Each `// snippet:` region is a Scala block of the page, word for word; `Part3Spec` runs the code and compares the
 * regions with the page.
 * {{{
 *   sbt "samples/runMain org.llm4s.samples.tutorial.supportassistant.Part3Tools"          # scripted, no key
 *   sbt "samples/runMain org.llm4s.samples.tutorial.supportassistant.Part3Tools --live"   # your provider
 * }}}
 */
object Part3Tools {

  // format: off
  // snippet: order-tool
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
  // end snippet
  // format: on

  // snippet: policy-tool
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
  // end snippet

  // snippet: agent
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
  // end snippet

  val question: String = "Where is my order A-1001, and can I still get a refund?"

  /** The model this part's demo talks to when it runs without `--live`: two tool calls, then the answer. */
  def scripted(): ScriptedLLMClient =
    ScriptedLLMClient.sequence(
      Reply.toolCall("lookup_order", """{"orderId": "A-1001"}"""),
      Reply.toolCall("refund_policy", """{"topic": "refund"}"""),
      Reply.text(
        "Order A-1001 has shipped. A refund goes back to your original payment method within 5 working days."
      )
    )

  def main(args: Array[String]): Unit =
    TutorialRunner.run(args, scripted())(client => answer(client, question))
}
