package org.llm4s.schema

import upickle.default.ReadWriter
import upickle.implicits.key

/** Sum types whose cases have fields (#2327), each with the `ReadWriter` uPickle derives for it. */
object SumFixtures {

  enum Team derives SchemaOf, ReadWriter { case Billing, Shipping }

  /** The issue's example: an enum whose cases have fields, some described. */
  @description("What the support bot decides to do")
  enum Action derives SchemaOf, ReadWriter {
    @description("Refund the customer") case Refund(amount: BigDecimal, reason: String)
    @description("Hand over to a human team") case Escalate(team: Team)
    case Reply(text: String)
  }

  /** A sealed trait mixing case classes, a renamed case, a case object and a case class without fields. */
  sealed trait Shape derives SchemaOf, ReadWriter
  case class Circle(radius: Double)                                          extends Shape
  @key("rect") case class Rectangle(width: Double, @key("h") height: Double) extends Shape
  @description("A single point") case object Dot                             extends Shape
  case class Blank()                                                         extends Shape

  /** A custom discriminator, `kind`, and custom case tags. */
  @key("kind") sealed trait Event derives SchemaOf, ReadWriter
  @key("click") case class Click(x: Int, y: Int)                       extends Event
  case class Typed(text: String, at: Option[Long], tags: List[String]) extends Event
  @key("idle") case object Idle                                        extends Event

  /** Sum types nested in a case class, directly, in a `List` and in an `Option`. */
  @description("A drawing")
  case class Drawing(title: String, shapes: List[Shape], highlight: Option[Shape], last: Event)
      derives SchemaOf,
        ReadWriter

  /** An enum whose cases hold a case class and another sum. */
  case class Money(amount: Int, currency: String) derives SchemaOf, ReadWriter
  enum Payment derives SchemaOf, ReadWriter {
    case Card(last4: String, charged: Money)
    case Voucher(code: String, next: Option[Action])
    case Free
  }

  /** Types only given to the compiler, to see what it says. */
  sealed trait Expr
  case class Num(n: Int)           extends Expr
  case class Add(l: Expr, r: Expr) extends Expr
  case class Neg(e: Option[Expr])  extends Expr
  sealed trait Generic
  case class Holds[A](a: A) extends Generic
  case class Plain(n: Int)  extends Generic
  sealed trait Outer
  case class Leaf(n: Int)    extends Outer
  sealed trait Inner         extends Outer
  case class Deep(s: String) extends Inner
}
