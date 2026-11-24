package org.llm4s.samples.tutorial.supportassistant.solutions

import org.llm4s.agent.guardrails.OutputGuardrail
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, SystemMessage, UserMessage }
import org.llm4s.metrics.MetricsCollector
import org.llm4s.samples.tutorial.supportassistant.{
  DeliveryEstimate,
  DeliveryQuery,
  Part1FirstCall,
  Part7Exercise,
  RefundRequest
}
import org.llm4s.samples.tutorial.supportassistant.Part3Tools.orders
import org.llm4s.samples.tutorial.supportassistant.Part7Serving.ChatResponse
import org.llm4s.rag.RAG
import org.llm4s.schema.*
import org.llm4s.testkit.{ AfterToolResult, EndsWithUser, Reply, ScriptedLLMClient }
import org.llm4s.toolapi.ToolFunction
import org.llm4s.types.Result

import scala.concurrent.duration.*

/** The solution to the tutorial's exercise 1 (`Part1Exercise.askIn`). */
object Part1Solution {

  def askIn(client: LLMClient, question: String, language: String): Result[String] = {
    val conversation = Conversation(
      Seq(SystemMessage(s"${Part1FirstCall.instructions} Answer in $language."), UserMessage(question))
    )
    client.complete(conversation, CompletionOptions()).map(_.content)
  }
}

/** The solution to the tutorial's exercise 2 (`Part2Exercise.refundRequest`). */
object Part2Solution {

  def refundRequest(client: LLMClient, message: String): Result[RefundRequest] =
    for {
      request <- client.completeStructuredOf[RefundRequest](
        Conversation(Seq(SystemMessage("Extract the refund request from this message."), UserMessage(message)))
      )
      _ <- Either.cond(request.amount > 0, (), ValidationError.invalid("amount", s"${request.amount} is not above 0"))
    } yield request
}

/** The solution to the tutorial's exercise 3 (`Part3Exercise.deliveryTool`). */
object Part3Solution {

  def deliveryTool: Result[ToolFunction[DeliveryEstimate]] =
    Tool.of[DeliveryQuery, DeliveryEstimate]("delivery_estimate", "How many working days until an order arrives") {
      query =>
        orders.get(query.orderId).toRight(s"There is no order ${query.orderId}").map { order =>
          DeliveryEstimate(order.orderId, if (order.status == "shipped") 2 else 4)
        }
    }
}

/** The solution to the tutorial's exercise 4 (`Part4Exercise.sources`). */
object Part4Solution {

  def sources(rag: RAG, question: String): Result[Seq[String]] =
    rag.query(question).map(_.flatMap(_.metadata.get("docId")).distinct)
}

/** The solution to the tutorial's exercise 5 (`Part5Exercise.noPromises`). */
object Part5Solution {

  def noPromises: OutputGuardrail = new OutputGuardrail {
    val name: String = "NoPromises"

    def validate(value: String): Result[String] =
      if (value.toLowerCase.contains("guarantee"))
        Left(ValidationError.invalid("answer", "an answer must not guarantee anything"))
      else Right(value)
  }
}

/** The solution to the tutorial's exercise 6 (`Part6Exercise.refundModel`). */
object Part6Solution {

  def refundModel(): ScriptedLLMClient =
    ScriptedLLMClient.respondingTo {
      case EndsWithUser(message) if message.toLowerCase.contains("refund") =>
        Reply.toolCall("refund_policy", """{"topic": "refund"}""")
      case AfterToolResult("refund_policy", result) =>
        Reply.text(ujson.read(result)("text").str)
    }
}

/** The solution to the tutorial's exercise 7 (`Part7Exercise.timed`). */
object Part7Solution {

  def timed(metrics: MetricsCollector)(respond: String => Result[ChatResponse]): String => Result[ChatResponse] =
    message => {
      val started  = System.nanoTime()
      val response = respond(message)
      val status   = response.fold(_ => "error", _.status)
      metrics.observe(Part7Exercise.ReplyDuration, Seq(status), (System.nanoTime() - started).nanos)
      response
    }
}
