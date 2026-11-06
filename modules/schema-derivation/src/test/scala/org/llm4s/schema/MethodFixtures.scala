package org.llm4s.schema

import org.llm4s.error.ValidationError
import org.llm4s.types.Result
import upickle.default.ReadWriter

import scala.util.{ Failure, Success, Try }

/** Services whose methods are made into tools, shared by the method-tool specs. */
object MethodFixtures {

  case class Order(id: String, total: Double) derives ReadWriter
  case class Cancellation(orderId: String, refunded: Boolean) derives ReadWriter

  /** A customer's orders. */
  trait CustomerService {

    /**
     * Orders placed by a customer,
     * newest first.
     *
     * Only orders that were paid.
     *
     * @param customerId the customer's id,
     *                   for example C-1001
     * @param since      only orders on or after this day, as yyyy-mm-dd
     * @return the orders
     */
    def findOrders(customerId: String, since: Option[String]): Result[List[Order]]

    /**
     * Cancel an order that has not shipped yet.
     *
     * @param orderId not this text: the annotation wins
     */
    @toolName("cancel")
    def cancelOrder(@description("The order to cancel") orderId: String, reason: String): Either[String, Cancellation]

    /** Not this text: the annotation wins. */
    @description("How many orders are open")
    def openOrders(limit: Int): Try[Int]

    def ping(): String

    def status: String

    @notATool
    def internal(weights: Map[String, Int]): Int

    @notATool
    def ping(times: Int): String
  }

  /** The service the specs call: answers depend only on the arguments. */
  object LiveCustomerService extends CustomerService {
    def findOrders(customerId: String, since: Option[String]): Result[List[Order]] =
      if (customerId == "unknown") Left(ValidationError.invalid("customerId", "no such customer"))
      else Right(List(Order(s"$customerId-1", since.fold(10.0)(_ => 5.0))))
    def cancelOrder(orderId: String, reason: String): Either[String, Cancellation] =
      if (reason.isEmpty) Left("a reason is required") else Right(Cancellation(orderId, refunded = true))
    def openOrders(limit: Int): Try[Int] =
      if (limit < 0) Failure(new IllegalArgumentException("limit must not be negative")) else Success(limit.min(3))
    def ping(): String                           = "pong"
    def status: String                           = "ok"
    def internal(weights: Map[String, Int]): Int = weights.size
    def ping(times: Int): String                 = "pong" * times
  }

  /** The equivalent of `findOrders`' parameters as an argument case class, for `Tool.of`. */
  case class FindOrdersArgs(
    @description("the customer's id, for example C-1001") customerId: String,
    @description("only orders on or after this day, as yyyy-mm-dd") since: Option[String]
  ) derives SchemaOf,
        ReadWriter

  case class Address(street: String, city: String) derives SchemaOf, ReadWriter

  /** Every parameter shape the argument checks treat differently, as a method and as a case class. */
  case class Everything(
    count: Int,
    big: Long,
    ratio: Double,
    flag: Boolean,
    note: Option[String],
    tags: List[String],
    address: Address,
    amount: BigDecimal
  ) derives SchemaOf,
        ReadWriter

  object Shapes {
    def everything(
      count: Int,
      big: Long,
      ratio: Double,
      flag: Boolean,
      note: Option[String],
      tags: List[String],
      address: Address,
      amount: BigDecimal
    ): Everything = Everything(count, big, ratio, flag, note, tags, address, amount)
  }

  // ---- services the compile-error spec refuses

  trait Overloaded {
    def find(id: String): String
    def find(id: Int): String
  }

  trait TakesAMap {
    def score(weights: Map[String, Int]): Int
  }

  trait ByName {
    def later(value: => Int): Int
  }

  trait WithContext {
    def greet(name: String)(using prefix: String): String
  }

  trait Curried {
    def add(a: Int)(b: Int): Int
  }

  trait Polymorphic {
    def first[A](items: List[A]): String
  }

  trait Repeated {
    def sum(values: Int*): Int
  }

  trait Unwritable {
    def connection(url: String): java.io.InputStream
  }

  trait Clashing {
    def findOrders(id: String): String
    @toolName("find_orders")
    def lookup(id: String): String
  }

  trait BadName {
    @toolName("find orders")
    def findOrders(id: String): String
  }
}
