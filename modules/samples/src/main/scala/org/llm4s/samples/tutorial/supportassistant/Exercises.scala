package org.llm4s.samples.tutorial.supportassistant

import org.llm4s.agent.guardrails.OutputGuardrail
import org.llm4s.llmconnect.LLMClient
import org.llm4s.metrics.{ Metric, MetricsCollector }
import org.llm4s.rag.RAG
import org.llm4s.samples.tutorial.supportassistant.Part7Serving.ChatResponse
import org.llm4s.schema.*
import org.llm4s.testkit.ScriptedLLMClient
import org.llm4s.toolapi.ToolFunction
import org.llm4s.types.Result
import upickle.default.ReadWriter

import scala.concurrent.duration.*

/**
 * The exercises of the "Build a support assistant" tutorial (`docs/tutorials/support-assistant/`). Each `???` is a
 * TODO: replace it with your code and run the part's spec, for example `sbt "samples/testOnly *Part1Spec"`. The
 * spec's "your exercise" cases are pending while the `???` is there, and fail until your code does what the page
 * asks. The worked solutions are in the `solutions` package, and the same spec checks them on every pull request.
 */
object Part1Exercise {

  /**
   * Exercise 1: like `Part1FirstCall.ask`, but the system message is `Part1FirstCall.instructions` followed by
   * `" Answer in <language>."`, so `askIn(client, "Where is my parcel?", "French")` asks for a French answer.
   */
  def askIn(client: LLMClient, question: String, language: String): Result[String] =
    ??? // TODO: build the conversation and call the client
}

// format: off
/** A refund request, as the model is asked to extract it from a customer's message. */
@description("A customer's request for a refund")
case class RefundRequest(
  @description("The order number, for example A-1001") orderId: String,
  @description("The amount to refund, in pounds") amount: Double,
  @description("Why the customer wants the refund, in one sentence") reason: String
) derives SchemaOf, ReadWriter
// format: on

object Part2Exercise {

  /**
   * Exercise 2: extract a [[RefundRequest]] from `message` with `completeStructuredOf`, and refuse an amount that is
   * not above zero with a `ValidationError` on the field `amount`.
   */
  def refundRequest(client: LLMClient, message: String): Result[RefundRequest] =
    ??? // TODO: ask for a RefundRequest, then check its amount
}

// format: off
@description("Which order to estimate the delivery of")
case class DeliveryQuery(@description("The order number, for example A-1001") orderId: String)
  derives SchemaOf, ReadWriter
// format: on

case class DeliveryEstimate(orderId: String, workingDays: Int) derives ReadWriter

object Part3Exercise {

  /**
   * Exercise 3: a tool named `delivery_estimate`, built with `Tool.of`, that answers from `Part3Tools.orders`: an
   * order that has `shipped` arrives in 2 working days, one still `processing` in 4, and an order number it does not
   * know is the error `There is no order <orderId>`.
   */
  def deliveryTool: Result[ToolFunction[DeliveryEstimate]] =
    ??? // TODO: build the tool with Tool.of, reading `Part3Tools.orders`
}

object Part4Exercise {

  /**
   * Exercise 4: the help-centre articles that answer `question`, best first and each once, without asking the model:
   * the `docId` of every passage `rag.query` retrieves. `sources(rag, "How long does express delivery take?")` starts
   * with `delivery`.
   */
  def sources(rag: RAG, question: String): Result[Seq[String]] =
    ??? // TODO: query the pipeline and read each passage's `docId`
}

object Part5Exercise {

  /**
   * Exercise 5: an output guardrail named `NoPromises` that refuses an answer containing the word "guarantee", in any
   * case, with a `ValidationError` on the field `answer`, and lets every other answer through unchanged.
   */
  def noPromises: OutputGuardrail =
    ??? // TODO: implement OutputGuardrail's `name` and `validate`
}

object Part6Exercise {

  /**
   * Exercise 6: a fake model for the refund question. The first call of a turn whose message mentions "refund", in
   * any case, calls the tool `refund_policy` with the topic `refund`; the call after that tool's result answers with
   * the policy's `text`, word for word. Any other conversation is unscripted.
   */
  def refundModel(): ScriptedLLMClient =
    ??? // TODO: ScriptedLLMClient.respondingTo, with the EndsWithUser and AfterToolResult extractors
}

object Part7Exercise {

  /** How long a reply took, by how its run ended: the timer exercise 7 records. */
  val ReplyDuration: Metric.Timer =
    Metric.timer("support.reply.duration", "How long a reply took", Seq(1.second, 5.seconds, 30.seconds), "status")

  /**
   * Exercise 7: like `Part7Serving.counted`, but records in [[ReplyDuration]] how long `respond` took, labelled with
   * the response's status, or `error` for a `Left`.
   */
  def timed(metrics: MetricsCollector)(respond: String => Result[ChatResponse]): String => Result[ChatResponse] =
    ??? // TODO: time the call with System.nanoTime and record it with metrics.observe
}
