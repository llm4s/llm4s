package org.llm4s.samples.jev

import org.llm4s.jev.*
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class JevTicketTriageSpec extends AnyFlatSpec with Matchers with EitherValues {

  private def response(
    department: String = "billing",
    departmentConfidence: Double = 0.9,
    urgent: Double = 0.1,
    frustration: Double = 0.2
  ): JevResponse =
    JevResponse(
      "jev-1.13.0",
      Map(
        "department" -> ChoiceAnswer(
          department,
          Map("billing" -> 0.8, "technical" -> 0.15, "sales" -> 0.05),
          departmentConfidence
        ),
        "urgent"      -> NoulAnswer(urgent),
        "frustration" -> ScoreAnswer(frustration, Seq(ScoreLevel(0, "Calm", 1.0)), 0.9)
      ),
      JevUsage(10, 2)
    )

  "The ticket triage" should "ask the three questions, with the team options, in one request" in {
    val request = JevTicketTriage.request("My payouts fail")

    request.questions.keySet shouldBe Set("department", "urgent", "frustration")
    request.questions("department") shouldBe a[JevQuestion.Choice]
    request.questions("department").asInstanceOf[JevQuestion.Choice].options.map(_._1) shouldBe
      Seq("billing", "technical", "sales")
  }

  it should "queue a calm, non-urgent ticket with its department" in {
    JevTicketTriage.route(response()).value shouldBe TicketRoute.Queue("billing")
  }

  it should "escalate an urgent ticket" in {
    JevTicketTriage.route(response(urgent = 0.95)).value shouldBe TicketRoute.Escalate("billing", 0.95)
  }

  it should "escalate a very frustrated customer even when the ticket does not look urgent" in {
    JevTicketTriage.route(response(urgent = 0.1, frustration = 1.8)).value shouldBe a[TicketRoute.Escalate]
  }

  it should "leave a ticket to a person when Jev is not sure which department it belongs to" in {
    JevTicketTriage.route(response(departmentConfidence = 0.4, urgent = 0.99)).value shouldBe
      TicketRoute.HumanTriage("billing", 0.4)
  }

  it should "treat the thresholds as inclusive at urgency and exclusive below confidence" in {
    JevTicketTriage.route(response(urgent = JevTicketTriage.UrgentAt)).value shouldBe a[TicketRoute.Escalate]
    JevTicketTriage
      .route(response(departmentConfidence = JevTicketTriage.MinConfidence))
      .value shouldBe a[TicketRoute.Queue]
  }

  it should "fail, not guess, when an answer is missing" in {
    val incomplete = JevResponse("m", Map("urgent" -> NoulAnswer(0.5)), JevUsage(1, 1))

    JevTicketTriage.route(incomplete).isLeft shouldBe true
  }
}
