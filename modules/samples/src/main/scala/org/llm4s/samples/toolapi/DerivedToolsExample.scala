package org.llm4s.samples.toolapi

import org.llm4s.agent.Agent
import org.llm4s.error.ValidationError
import org.llm4s.schema.*
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.types.Result
import upickle.default.ReadWriter

/**
 * Tools derived from an ordinary Scala service (#2326): `Tools.derive` makes one tool per public method of the
 * trait, named after the method and described by its Scaladoc, and an agent calls them.
 *
 * The model is a `ScriptedLLMClient` from `llm4s-testkit`, so the example needs no API key and no network: it asks
 * for a customer's orders, then answers.
 *
 * {{{
 * sbt "samples/runMain org.llm4s.samples.toolapi.DerivedToolsExample"
 * }}}
 */
object DerivedToolsExample {

  case class Order(id: String, totalCents: Long) derives ReadWriter
  case class Cancellation(orderId: String, refunded: Boolean) derives ReadWriter

  /** What the support agent may do for a customer. */
  trait CustomerService {

    /**
     * Orders placed by a customer, newest first.
     *
     * @param customerId the customer's id, for example C-1001
     * @param limit      at most this many orders
     */
    def findOrders(customerId: String, limit: Option[Int]): Result[List[Order]]

    /**
     * Cancel an order that has not shipped yet.
     *
     * @param orderId the order's id, for example O-17
     * @param reason  why the customer cancels, in their words
     */
    def cancelOrder(orderId: String, reason: String): Either[String, Cancellation]

    /** The connection pool: not for the model. */
    @notATool
    def poolSize: Int
  }

  object InMemoryCustomerService extends CustomerService {
    private val orders = Map("C-1001" -> List(Order("O-17", 4200), Order("O-12", 999)))

    def findOrders(customerId: String, limit: Option[Int]): Result[List[Order]] =
      orders
        .get(customerId)
        .map(found => limit.fold(found)(found.take))
        .toRight(ValidationError.invalid("customerId", s"no customer $customerId"))

    def cancelOrder(orderId: String, reason: String): Either[String, Cancellation] =
      if (orderId == "O-12") Left("O-12 has already shipped") else Right(Cancellation(orderId, refunded = true))

    def poolSize: Int = 4
  }

  def main(args: Array[String]): Unit = {
    val model = ScriptedLLMClient.sequence(
      Reply.toolCall("find_orders", """{"customerId": "C-1001", "limit": 1}"""),
      Reply.text("Your latest order is O-17, for 42.00.")
    )

    val outcome = for {
      tools <- Tools.derive[CustomerService](InMemoryCustomerService)
      _ = tools.foreach(tool => println(tool.toOpenAITool().render(indent = 2)))
      agent <- Agent
        .builder("support", model)
        .withTools(new ToolRegistry(tools))
        .withSystemPrompt("You help customers with their orders.")
        .build()
      result <- agent.run("What did I order last? I am C-1001.")
    } yield result

    outcome match {
      case Right(result) => println(s"\nAnswer: ${result.answer.getOrElse(result.status.toString)}")
      case Left(error)   => println(s"Failed: ${error.formatted}")
    }
  }
}
