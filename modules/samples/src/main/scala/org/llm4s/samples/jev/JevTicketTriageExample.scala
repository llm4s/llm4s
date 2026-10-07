package org.llm4s.samples.jev

import org.llm4s.jev.{ JevClient, JevQuestion, JevRequest, JevResponse }
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

/**
 * Where a support ticket goes next.
 *
 * The routing is ordinary code over Jev's typed answers: Jev answers narrow questions with probabilities and a
 * confidence, and the code decides what to do with them.
 */
sealed trait TicketRoute
object TicketRoute {

  /** A person looks at it now: Jev thinks it is urgent. */
  final case class Escalate(department: String, urgency: Double) extends TicketRoute

  /** The department's queue, in the order Jev's answers suggest. */
  final case class Queue(department: String) extends TicketRoute

  /** Jev was not sure which department, so a person decides. */
  final case class HumanTriage(bestGuess: String, confidence: Double) extends TicketRoute
}

/**
 * Triage a support ticket with Jev, TypeSafe's decision model.
 *
 * Jev is not a chat model, so this does not prompt and parse text. It asks three typed questions about the ticket in one
 * call, a Choice (which team?), a Noul (is it urgent?) and a Score (how frustrated is the customer?), and
 * [[JevTicketTriage.route]] turns the answers into a decision with thresholds you control in code.
 *
 * Run it with an API key in `TYPESAFE_API_KEY`:
 * {{{
 * sbt "samples/runMain org.llm4s.samples.jev.JevTicketTriageExample"
 * }}}
 *
 * The decision is a plain workflow step. To hand a ticket to a specialist agent, map a route to a
 * `org.llm4s.agent.Handoff` where the result is used; that needs an LLM provider as well, so this sample stops at
 * the decision.
 */
object JevTicketTriage {

  val UrgentAt: Double      = 0.8
  val MinConfidence: Double = 0.6
  val Departments: Seq[(String, String)] = Seq(
    "billing"   -> "Payments, invoicing, refunds",
    "technical" -> "Bugs, outages, integrations",
    "sales"     -> "Pricing, upgrades, new accounts"
  )

  /** The questions, all asked about the one ticket text. */
  def request(ticket: String): JevRequest =
    JevRequest(
      ticket,
      Map(
        "department"  -> JevQuestion.choice("Which team should handle this?", Departments*),
        "urgent"      -> JevQuestion.noul("Does this need immediate human attention?"),
        "frustration" -> JevQuestion.score("How frustrated is the customer?", "Calm", "Frustrated", "Very angry")
      )
    )

  /** The decision: the thresholds are code, so they can be tested and tuned without touching a prompt. */
  def route(response: JevResponse): Result[TicketRoute] =
    for {
      department  <- response.choice("department")
      urgent      <- response.noul("urgent")
      frustration <- response.score("frustration")
    } yield
      if (department.confidence < MinConfidence) TicketRoute.HumanTriage(department.choice, department.confidence)
      else if (urgent.probability >= UrgentAt || frustration.score >= 1.5)
        TicketRoute.Escalate(department.choice, urgent.probability)
      else TicketRoute.Queue(department.choice)

  /** Asks Jev about `ticket` and decides. */
  def triage(client: JevClient, ticket: String): Result[(TicketRoute, JevResponse)] =
    for {
      response <- client.evaluate(request(ticket))
      decision <- route(response)
    } yield (decision, response)
}

object JevTicketTriageExample {

  private val logger = LoggerFactory.getLogger(getClass)

  private val Tickets = Seq(
    "Help! My payouts have been failing for 3 days and my customers are angry.",
    "Could you tell me whether the Team plan includes SSO?",
    "Hi"
  )

  def main(args: Array[String]): Unit = {
    val outcome = for {
      client <- JevClient.fromConfig()
      results <- {
        val all = Tickets.map(ticket => JevTicketTriage.triage(client, ticket).map(ticket -> _))
        client.close()
        all.foldLeft[Result[Seq[(String, (TicketRoute, JevResponse))]]](Right(Vector.empty))((acc, r) =>
          acc.flatMap(a => r.map(a :+ _))
        )
      }
    } yield results

    outcome match {
      case Right(results) =>
        results.foreach { case (ticket, (route, response)) =>
          logger.info(s"""Ticket: "$ticket"""")
          logger.info(s"  route: $route   (model ${response.model}, ${response.usage.inputTokens} input tokens)")
        }
      case Left(error) =>
        logger.error(s"Jev triage failed: ${error.message}")
        logger.error("Set TYPESAFE_API_KEY (see docs/guide/jev.md) and run again.")
        sys.exit(1)
    }
  }
}
