package org.llm4s.prompt

import org.llm4s.prompt.Fixtures.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.{ Instant, LocalDate }
import java.util.UUID

/** Golden strings: the rendering of every supported shape is fixed and deterministic. */
class PromptableSpec extends AnyFlatSpec with Matchers {

  private def render[A](a: A)(using p: Promptable[A]): String = p.render(a)

  "Promptable" should "write a string as it is on its own, and quoted inside a structure" in {
    render("ACME, Inc.") shouldBe "ACME, Inc."
    render(List("a, b", "say \"hi\"\\", "two\nlines\r")) shouldBe
      """["a, b", "say \"hi\"\\", "two\nlines\r"]"""
  }

  it should "write numbers, booleans, characters, dates and ids" in {
    render(42) shouldBe "42"
    render(42L) shouldBe "42"
    render(7.toShort) shouldBe "7"
    render(7.toByte) shouldBe "7"
    render(12.5) shouldBe "12.5"
    render(1.5f) shouldBe "1.5"
    render(BigInt(10).pow(20)) shouldBe "100000000000000000000"
    render(BigDecimal("12.50")) shouldBe "12.50"
    render(true) shouldBe "true"
    render('x') shouldBe "x"
    render(Instant.parse("2026-11-05T10:15:30Z")) shouldBe "2026-11-05T10:15:30Z"
    render(LocalDate.of(2026, 11, 5)) shouldBe "2026-11-05"
    val id = UUID.fromString("123e4567-e89b-12d3-a456-426614174000")
    render(id) shouldBe "123e4567-e89b-12d3-a456-426614174000"
  }

  it should "write an Option as its value or (none)" in {
    render(Option(3)) shouldBe "3"
    render(Option.empty[Int]) shouldBe "(none)"
    render(Option("text")) shouldBe "\"text\""
  }

  it should "write a Seq in order, and a Set and a Map sorted" in {
    render(Vector(3, 1, 2)) shouldBe "[3, 1, 2]"
    render(List.empty[Int]) shouldBe "[]"
    render(Set("pear", "apple", "fig", "kiwi", "plum")) shouldBe "[\"apple\", \"fig\", \"kiwi\", \"pear\", \"plum\"]"
    render(Map("b" -> 2, "a" -> 1, "c" -> 3, "e" -> 5, "d" -> 4)) shouldBe
      "{\"a\": 1, \"b\": 2, \"c\": 3, \"d\": 4, \"e\": 5}"
  }

  "derives Promptable" should "write a case class as its labelled fields" in {
    render(Ticket("T-42", "ACME", Priority.High)) shouldBe
      "Ticket(id: \"T-42\", customer: \"ACME\", priority: High)"
  }

  it should "write nested case classes, options and collections" in {
    val order = Order(
      7L,
      List(Line("A-1", 2), Line("B-2", 1)),
      None,
      Set("urgent", "b2b"),
      Map("net" -> BigDecimal("10.00"), "gross" -> BigDecimal("11.90"))
    )
    render(order) shouldBe
      "Order(id: 7, lines: [Line(sku: \"A-1\", quantity: 2), Line(sku: \"B-2\", quantity: 1)], note: (none), " +
      "tags: [\"b2b\", \"urgent\"], totals: {\"gross\": 11.90, \"net\": 10.00})"
    render(order) shouldBe render(order.copy(tags = Set("b2b", "urgent")))
  }

  it should "write an enum's cases, with and without fields" in {
    render(Priority.Low) shouldBe "Low"
    render[Shape](Shape.Circle(1.5)) shouldBe "Circle(radius: 1.5)"
    render[Shape](Shape.Square(2.0)) shouldBe "Square(side: 2.0)"
    render[Shape](Shape.Point) shouldBe "Point"
  }

  it should "write a sealed trait's cases, including nested sums and products" in {
    render[Event](Event.Opened("ann")) shouldBe "Opened(by: \"ann\")"
    render[Event](Event.Closed) shouldBe "Closed"
    render[Event](Event.Moved(Shape.Square(1.0))) shouldBe "Moved(to: Square(side: 1.0))"
    render[Event](Event.Nested(Ticket("T-1", "Bo", Priority.Low))) shouldBe
      "Nested(inner: Ticket(id: \"T-1\", customer: \"Bo\", priority: Low))"
  }

  it should "write a case class without fields as its name" in {
    render(Empty()) shouldBe "Empty"
  }

  "Promptable.instance" should "render the same on its own and nested" in {
    given Promptable[Opaque] = Promptable.instance(_ => "opaque")
    render(new Opaque) shouldBe "opaque"
    render(List(new Opaque)) shouldBe "[opaque]"
    Promptable[Opaque].renderNested(new Opaque) shouldBe "opaque"
  }
}
