package example.schemauser

import org.llm4s.toolapi.ToolCallError
import org.scalatest.exceptions.TestFailedException
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.ReadWriter

/**
 * A ScalaTest suite outside `org.llm4s`, as a user's is, that imports `org.llm4s.schema.*` inside its body, where a
 * wildcard import and an inherited member of the same name would be ambiguous: ScalaTest's `fail` and the scoped
 * tools' `reject` are both usable, without qualification.
 */
class ScopedToolInScalaTestSpec extends AnyFlatSpec with Matchers {
  import org.llm4s.schema.*

  case class Amount(cents: Long) derives SchemaOf, ReadWriter

  "A suite that imports org.llm4s.schema.*" should "call ScalaTest's fail and the scoped tools' reject" in {
    val tool = Tool
      .scoped[Amount, Long]("amount", "Checks an amount") { a =>
        if (a.cents <= 0) reject("amount must be positive")
        a.cents
      }
      .fold(e => fail(s"the tool was not built: ${e.formatted}"), identity)

    tool.execute(ujson.Obj("cents" -> 5)) shouldBe Right(ujson.Num(5))
    tool.execute(ujson.Obj("cents" -> 0)) shouldBe
      Left(ToolCallError.HandlerError("amount", "amount must be positive"))
    a[TestFailedException] should be thrownBy fail("ScalaTest's fail")
  }
}
