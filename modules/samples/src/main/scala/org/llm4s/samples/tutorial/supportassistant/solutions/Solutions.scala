package org.llm4s.samples.tutorial.supportassistant.solutions

import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, SystemMessage, UserMessage }
import org.llm4s.samples.tutorial.supportassistant.{ DeliveryEstimate, DeliveryQuery, Part1FirstCall, RefundRequest }
import org.llm4s.samples.tutorial.supportassistant.Part3Tools.orders
import org.llm4s.schema.*
import org.llm4s.toolapi.ToolFunction
import org.llm4s.types.Result

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

  def deliveryTool: Result[ToolFunction[DeliveryQuery, DeliveryEstimate]] =
    Tool.of[DeliveryQuery, DeliveryEstimate]("delivery_estimate", "How many working days until an order arrives") {
      query =>
        orders.get(query.orderId).toRight(s"There is no order ${query.orderId}").map { order =>
          DeliveryEstimate(order.orderId, if (order.status == "shipped") 2 else 4)
        }
    }
}
