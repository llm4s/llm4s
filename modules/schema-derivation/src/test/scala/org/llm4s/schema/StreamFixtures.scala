package org.llm4s.schema

import upickle.default.ReadWriter

/** The types `streamStructuredOf` is streamed into, and a reply for each. */
object StreamFixtures {

  enum Status derives SchemaOf, ReadWriter {
    case Draft, Paid
  }

  case class LineItem(sku: String, quantity: Int, price: BigDecimal) derives SchemaOf, ReadWriter

  case class Invoice(
    vendor: String,
    currency: String,
    items: List[LineItem],
    status: Status,
    note: Option[String],
    @upickle.implicits.key("total_cents") totalCents: Long,
    tags: Vector[String],
    lines: Seq[Int]
  ) derives SchemaOf,
        ReadWriter

  /** A reply with escapes, a `\u` escape, a surrogate pair, a `Long` beyond 2^53 and numbers of every form. */
  val invoiceJson: String =
    """{"vendor": "Acme \"Supplies\" Ltd\né😀", "currency": "GBP",
      | "items": [{"sku": "A-1", "quantity": 12, "price": "12.50"}, {"sku": "B-2", "quantity": 3, "price": "0.5"}],
      | "status": "Paid", "note": null, "total_cents": 9007199254740993, "tags": ["urgent", "q3"],
      | "lines": [1, -20, 300]}""".stripMargin

  val invoice: Invoice = Invoice(
    "Acme \"Supplies\" Ltd\né😀",
    "GBP",
    List(LineItem("A-1", 12, BigDecimal("12.50")), LineItem("B-2", 3, BigDecimal("0.5"))),
    Status.Paid,
    None,
    9007199254740993L,
    Vector("urgent", "q3"),
    Seq(1, -20, 300)
  )

  /** A sum type, which `streamStructuredOf` refuses at compile time. */
  enum Decision derives SchemaOf, ReadWriter {
    case Refund(amount: Double)
    case Escalate(team: String)
  }

  /** A recursive type, which `SchemaOf` refuses and `PartialOf` reads whole inside itself. */
  case class Tree(label: String, children: List[Tree]) derives ReadWriter

  /** A case class with a field type that has no uPickle reader. */
  class Opaque
  case class Holder(opaque: Opaque)
}
