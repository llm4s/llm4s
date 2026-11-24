package org.llm4s.samples.agent

import org.llm4s.agent.graph.middleware.ApprovalMiddleware
import org.llm4s.agent.graph.tool.{ AgentTool, AgentToolSpec, ToolContent, ToolOutcome, ToolSet }
import org.llm4s.agent.{ Agent, AgentStatus }
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.model.{ AssistantMessage, ToolCall, ToolCallId, ToolMessage }
import org.llm4s.samples.cookbook.ScriptedClient
import org.llm4s.toolapi.Schema
import org.llm4s.types.Result
import upickle.default.ReadWriter

import java.util.concurrent.atomic.AtomicReference

/**
 * Shows the three answers a person can give to a tool call that needs approval - approve, reject and edit - and what
 * the model sees for each, using a scripted model, so it needs no API key, no network and no keyboard.
 *
 * A `send_payment` tool is held by an [[org.llm4s.agent.graph.middleware.ApprovalMiddleware ApprovalMiddleware]]. The
 * scripted model asks for three payments in one turn; the run ends `AgentStatus.Suspended`; the sample approves the
 * first, rejects the second with a reason and edits the amount of the third, then resumes the thread.
 *
 * Run with no API key or network access:
 * `sbt "samples/runMain org.llm4s.samples.agent.HumanReviewOfflineExample"`
 */
object HumanReviewOfflineExample {
  final case class SendPaymentArgs(to: String, amount: Int) derives ReadWriter

  final case class Payment(to: String, amount: Int)

  /**
   * What the sample did, for `main` to print and for the spec to check.
   *
   * @param pending      the tool calls the run was waiting on, one line each
   * @param decisions    the answer given to each, one line each
   * @param modelSaw     the tool results the model saw after the resume, in call order
   * @param payments     the payments the tool actually made
   * @param finalAnswer  the model's answer
   */
  final case class HumanReviewResult(
    pending: Vector[String],
    decisions: Vector[String],
    modelSaw: Vector[String],
    payments: Vector[Payment],
    finalAnswer: String
  )

  private val ToolName = "send_payment"

  // The three calls the model asks for in one turn, with the call ids the decisions are matched on.
  private val ApprovedCallId = ToolCallId("pay-alice")
  private val RejectedCallId = ToolCallId("pay-mallory")
  private val EditedCallId   = ToolCallId("pay-bob")

  private val RejectReason = "not on the payee list"
  private val EditedAmount = 50
  private val ReviewReason = "payments need review"
  private val UserRequest  = "Pay alice 20, mallory 1000 and bob 500."

  private def sendPayment(payments: AtomicReference[Vector[Payment]]): AgentTool[SendPaymentArgs] = {
    val spec = AgentToolSpec[SendPaymentArgs](
      ToolName,
      "Sends a payment and records it",
      Schema
        .`object`[SendPaymentArgs]("A payment")
        .withRequiredField("to", Schema.string("The payee"))
        .withRequiredField("amount", Schema.integer("The amount to send"))
    )

    AgentTool(spec) { (args, _) =>
      payments.updateAndGet(_ :+ Payment(args.to, args.amount))
      ToolOutcome.success(ToolContent.Json(ujson.Obj("paid" -> args.to, "amount" -> args.amount)))
    }
  }

  private def paymentCall(id: ToolCallId, to: String, amount: Int): ToolCall =
    ToolCall(id, ToolName, ujson.Obj("to" -> to, "amount" -> amount))

  /** Asks for the three payments, then answers with the tool results it saw. */
  private def scriptedClient: ScriptedClient = new ScriptedClient((conversation, callNumber) =>
    Right(
      if (callNumber == 1)
        AssistantMessage(
          None,
          Seq(
            paymentCall(ApprovedCallId, "alice", 20),
            paymentCall(RejectedCallId, "mallory", 1000),
            paymentCall(EditedCallId, "bob", 500)
          )
        )
      else {
        val seen = conversation.messages.collect { case message: ToolMessage => message.content }
        AssistantMessage(s"Done. The tool results were: ${seen.mkString("; ")}")
      }
    )
  )

  private def describe(call: ToolCall): String =
    s"$ToolName(${call.arguments("to").str}, ${call.arguments("amount").num.toInt})"

  def run(): Result[HumanReviewResult] = {
    val payments = new AtomicReference(Vector.empty[Payment])
    val client   = scriptedClient

    for {
      tools <- ToolSet.of(sendPayment(payments))
      agent <- Agent
        .builder("human-review-offline-example", client)
        .withToolSet(tools)
        .withMiddleware(new ApprovalMiddleware(request => Option.when(request.spec.name == ToolName)(ReviewReason)))
        .build()
      parked <- agent.run(UserRequest)
      approvals <- parked.status match {
        case AgentStatus.Suspended(suspension) if suspension.approvals.sizeIs == 3 => Right(suspension.approvals)
        case other => Left(ValidationError("human review example", s"expected three approvals, got $other"))
      }
      // Match each decision to its call by the call id, not by the order the approvals are listed in.
      decided = approvals.map { case (id, request) =>
        val call = request.call
        call.id match {
          case ApprovedCallId =>
            (parked.approve(id), s"approved ${describe(call)}")
          case RejectedCallId =>
            (parked.reject(id, RejectReason), s"rejected ${describe(call)}: $RejectReason")
          case _ =>
            val edited = ujson.Obj("to" -> call.arguments("to").str, "amount" -> EditedAmount)
            (parked.edit(id, edited), s"edited amount ${call.arguments("amount").num.toInt} -> $EditedAmount")
        }
      }
      pending = approvals.map { case (_, request) => s"${describe(request.call)}: ${request.reason}" }
      completed <- agent.resume(parked.threadId, decided.map(_._1).toMap)
      answer <- completed.answer.toRight(
        ValidationError("human review example", s"expected a final answer, got ${completed.status}")
      )
      modelSaw = client.calls.lastOption.toVector.flatMap { case (conversation, _) =>
        conversation.messages.collect { case message: ToolMessage => message.content }
      }
    } yield HumanReviewResult(
      pending = pending,
      decisions = decided.map(_._2),
      modelSaw = modelSaw,
      payments = payments.get,
      finalAnswer = answer
    )
  }

  def main(args: Array[String]): Unit =
    run() match {
      case Right(result) =>
        println("pending interrupts:")
        result.pending.foreach(line => println(s"  $line"))
        println("decisions:")
        result.decisions.foreach(line => println(s"  $line"))
        println("the model saw:")
        result.modelSaw.foreach(line => println(s"  $line"))
        println(s"payments made: ${result.payments.map(p => s"${p.to} ${p.amount}").mkString(", ")}")
        println(s"final answer: ${result.finalAnswer}")
      case Left(error) =>
        println(s"Human review example failed: ${error.formatted}")
        sys.exit(1)
    }
}
