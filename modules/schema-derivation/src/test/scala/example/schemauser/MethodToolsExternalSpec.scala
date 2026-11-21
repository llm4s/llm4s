package example.schemauser

import org.llm4s.schema.*
import org.llm4s.toolapi.ToolCallError
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Tools derived from methods in a package outside `org.llm4s`, as a user's are: the code the macros generate must
 * compile and run there (#2326).
 */
class MethodToolsExternalSpec extends AnyFlatSpec with Matchers {

  /** Converts temperatures. */
  trait Temperatures {

    /**
     * Celsius as Fahrenheit.
     *
     * @param celsius degrees Celsius
     */
    def toFahrenheit(celsius: Double): Double

    @description("Refuses a temperature below absolute zero")
    def check(kelvin: Double): Either[String, Boolean]
  }

  object Converter extends Temperatures {
    def toFahrenheit(celsius: Double): Double          = celsius * 9 / 5 + 32
    def check(kelvin: Double): Either[String, Boolean] = if (kelvin < 0) Left("below absolute zero") else Right(true)
  }

  "Tools derived outside org.llm4s" should "be described by Scaladoc and call the service" in {
    val tools = Tools.derive[Temperatures](Converter).fold(e => fail(e.formatted), identity)
    tools.map(_.name) shouldBe Seq("check", "to_fahrenheit")
    tools(1).description shouldBe "Celsius as Fahrenheit."
    tools(1).execute(ujson.Obj("celsius" -> 100)) shouldBe Right(ujson.Num(212))
    tools.head.execute(ujson.Obj("kelvin" -> -1)) shouldBe Left(
      ToolCallError.HandlerError("check", "below absolute zero")
    )
  }

  it should "build the tool of one method" in {
    val tool = Tool.fromMethod(Converter.toFahrenheit).fold(e => fail(e.formatted), identity)
    tool.name shouldBe "to_fahrenheit"
    tool.execute(ujson.Obj("celsius" -> 0)) shouldBe Right(ujson.Num(32))
  }
}
