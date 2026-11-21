package org.llm4s.prompt

/** Types the specs render. */
object Fixtures {

  enum Priority derives Promptable {
    case Low, High
  }

  final case class Ticket(id: String, customer: String, priority: Priority) derives Promptable

  final case class Line(sku: String, quantity: Int) derives Promptable

  final case class Order(
    id: Long,
    lines: List[Line],
    note: Option[String],
    tags: Set[String],
    totals: Map[String, BigDecimal]
  ) derives Promptable

  enum Shape derives Promptable {
    case Circle(radius: Double)
    case Square(side: Double)
    case Point
  }

  sealed trait Event derives Promptable
  object Event {
    final case class Opened(by: String)    extends Event
    case object Closed                     extends Event
    final case class Moved(to: Shape)      extends Event
    final case class Nested(inner: Ticket) extends Event
  }

  final case class Empty() derives Promptable

  /** A type with no `Promptable`. */
  final class Opaque

  /** A case class one of whose fields has no `Promptable`. */
  final case class HoldsOpaque(value: Opaque)
}
