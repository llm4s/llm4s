package example.ironuser

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import org.llm4s.schema.*
import org.llm4s.schema.iron.given
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.ReadWriter

/**
 * The module used from a package outside `org.llm4s`, as user code does: the inline given and its macro expand where
 * they are used, so nothing they reach may be visible only inside `org.llm4s`.
 */
final case class Order(
  @description("Quantity") quantity: Int :| Interval.Closed[1, 99],
  sku: String :| FixedLength[8]
) derives SchemaOf,
      ReadWriter

final case class Note(text: String :| Match["[a-z]+"])

object Notes {
  // A constraint the module refuses, described by a hand-written given instead, which is taken as it is
  given SchemaOf[String :| Match["[a-z]+"]] = SchemaOf.string("lower-case letters")
  given SchemaOf[Note]                      = SchemaOf.derived[Note]
}

class ExternalIronUseSpec extends AnyFlatSpec with Matchers {

  "A refined case class outside org.llm4s" should "be described with its bounds and read back" in {
    val schema = SchemaOf[Order].toJsonSchema()
    schema("properties")("quantity")("maximum") shouldBe ujson.Num(99)
    schema("properties")("sku")("minLength") shouldBe ujson.Num(8)
    schema("properties")("sku")("maxLength") shouldBe ujson.Num(8)
    upickle.default.read[Order]("""{"quantity":2,"sku":"ABCD1234"}""") shouldBe Order(2, "ABCD1234")
    intercept[Exception](upickle.default.read[Order]("""{"quantity":2,"sku":"ABC"}"""))
  }

  "A hand-written given of a refined type" should "be used instead of the module's" in {
    import Notes.given
    SchemaOf[Note].toJsonSchema()("properties")("text") shouldBe
      ujson.Obj("type" -> "string", "description" -> "lower-case letters")
  }
}
