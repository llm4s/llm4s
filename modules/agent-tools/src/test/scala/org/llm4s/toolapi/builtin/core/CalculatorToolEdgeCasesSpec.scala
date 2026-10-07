package org.llm4s.toolapi.builtin.core

import org.llm4s.toolapi.SafeParameterExtractor
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.Locale
import scala.util.Using

/**
 * Edge cases of [[CalculatorTool]] that `CoreToolsSpec` does not cover: the operations it skips (abs, min,
 * max, modulo), the error paths, how operation names and operands are read, and how results are rendered.
 *
 * Every expectation here was observed by running the tool, not assumed. A result that is not a finite number
 * is an error (as division by zero is), and the formatted text does not depend on the JVM's default locale.
 */
class CalculatorToolEdgeCasesSpec extends AnyFlatSpec with Matchers {

  /** Locales whose number formatting differs from the root locale's: comma separator, non-ASCII digits. */
  private val formatLocales: Seq[Locale] = Seq(
    Locale.GERMANY,
    Locale.FRANCE,
    Locale.forLanguageTag("ar-SA"),
    Locale.forLanguageTag("th-TH-u-nu-thai"),
    Locale.forLanguageTag("hi-IN-u-nu-deva")
  )

  private def tool = CalculatorTool.toolSafe.fold(e => fail(s"Tool creation failed: ${e.formatted}"), identity)

  /** Runs `body` with the default FORMAT locale replaced, and restores it even if `body` throws. */
  private def withFormatLocale[A](locale: Locale)(body: => A): A = {
    val original = Locale.getDefault(Locale.Category.FORMAT)
    Locale.setDefault(Locale.Category.FORMAT, locale)
    Using.resource(new AutoCloseable {
      override def close(): Unit = Locale.setDefault(Locale.Category.FORMAT, original)
    })(_ => body)
  }

  private def params(operation: String, a: Double, b: Option[Double]): ujson.Obj = {
    val p = ujson.Obj("operation" -> operation, "a" -> a)
    b.foreach(v => p("b") = v)
    p
  }

  private def calc(operation: String, a: Double, b: Option[Double] = None): Either[String, CalculatorResult] =
    tool.handler(SafeParameterExtractor(params(operation, a, b)))

  private def raw(p: ujson.Value): Either[String, CalculatorResult] =
    tool.handler(SafeParameterExtractor(p))

  private def ok(operation: String, a: Double, b: Option[Double] = None): CalculatorResult =
    calc(operation, a, b).fold(err => fail(s"Expected Right for $operation($a, $b) but got Left: $err"), identity)

  private def failure(result: Either[String, CalculatorResult]): String =
    result.fold(identity, r => fail(s"Expected Left but got Right: $r"))

  // ---- operations the shared spec skips

  "CalculatorTool abs" should "return the magnitude of a negative number" in {
    ok("abs", -3.5).result shouldBe 3.5
  }

  it should "leave a positive number and zero unchanged" in {
    ok("abs", 2.0).result shouldBe 2.0
    ok("abs", 0.0).result shouldBe 0.0
  }

  it should "ignore a second operand it does not use" in {
    val withB = ok("abs", -3.0, Some(99.0))
    withB.result shouldBe 3.0
    withB.expression shouldBe "abs(-3.0)"
  }

  "CalculatorTool min and max" should "pick the smaller and the larger operand, whichever side it is on" in {
    ok("min", 4.0, Some(9.0)).result shouldBe 4.0
    ok("min", 9.0, Some(4.0)).result shouldBe 4.0
    ok("max", 4.0, Some(9.0)).result shouldBe 9.0
    ok("max", 9.0, Some(4.0)).result shouldBe 9.0
  }

  it should "handle negative and equal operands" in {
    ok("max", -4.0, Some(-9.0)).result shouldBe -4.0
    ok("min", -4.0, Some(-9.0)).result shouldBe -9.0
    ok("min", 4.0, Some(4.0)).result shouldBe 4.0
    ok("max", 4.0, Some(4.0)).result shouldBe 4.0
  }

  "CalculatorTool modulo" should "return the remainder, including a fractional one" in {
    ok("modulo", 10.0, Some(3.0)).result shouldBe 1.0
    ok("modulo", 5.5, Some(2.0)).result shouldBe 1.5
    ok("modulo", 9.0, Some(3.0)).result shouldBe 0.0
  }

  it should "refuse to divide by zero, whichever sign the zero has" in {
    val positive = failure(calc("modulo", 10.0, Some(0.0)))
    positive.toLowerCase should include("modulo")
    positive.toLowerCase should include("zero")
    failure(calc("modulo", 10.0, Some(-0.0))).toLowerCase should include("zero")
  }

  // ---- error paths of the operations the shared spec does cover

  "CalculatorTool divide" should "refuse a negative zero divisor too" in {
    failure(calc("divide", 10.0, Some(-0.0))).toLowerCase should include("zero")
  }

  "CalculatorTool sqrt" should "accept zero and keep full precision in the result" in {
    ok("sqrt", 0.0).result shouldBe 0.0
    ok("sqrt", 2.0).result shouldBe math.sqrt(2.0)
  }

  it should "refuse any negative number, however small, but not negative zero" in {
    failure(calc("sqrt", -1e-9)).toLowerCase should include("negative")
    failure(calc("sqrt", -0.5)).toLowerCase should include("negative")
    ok("sqrt", -0.0).result shouldBe 0.0
  }

  "CalculatorTool power" should "handle a zero base and exponent, and a negative exponent" in {
    ok("power", 0.0, Some(0.0)).result shouldBe 1.0
    ok("power", 2.0, Some(-2.0)).result shouldBe 0.25
  }

  // ---- the second operand

  private def binaryOperations: Seq[String] =
    Seq("add", "subtract", "multiply", "divide", "power", "percentage", "min", "max", "modulo")

  "CalculatorTool" should "require a second operand for every binary operation, naming the operation" in {
    binaryOperations.foreach { operation =>
      val message = failure(calc(operation, 1.0))
      withClue(s"$operation: ") {
        message should include(operation)
        message should include("operand")
      }
    }
  }

  it should "need no second operand for the unary operations" in {
    ok("sqrt", 9.0).result shouldBe 3.0
    ok("abs", -9.0).result shouldBe 9.0
  }

  it should "not treat a second operand of the wrong type as a valid one" in {
    raw(ujson.Obj("operation" -> "add", "a" -> 5.0, "b" -> "x")).isLeft shouldBe true
    raw(ujson.Obj("operation" -> "add", "a" -> 5.0, "b" -> ujson.Null)).isLeft shouldBe true
  }

  // ---- the operation name

  it should "read the operation name case-insensitively" in {
    Seq("add", "ADD", "Add", "aDd").foreach(name => ok(name, 1.0, Some(2.0)).result shouldBe 3.0)
    ok("SQRT", 9.0).result shouldBe 3.0
    ok("Modulo", 10.0, Some(3.0)).result shouldBe 1.0
  }

  it should "echo the operation in lower case in the expression, whatever case was sent" in {
    ok("ADD", 1.0, Some(2.0)).expression shouldBe "1.0 + 2.0"
    ok("SQRT", 9.0).expression shouldBe "sqrt(9.0)"
  }

  it should "reject an unknown or empty operation, naming it and listing what is supported" in {
    val unknown = failure(calc("frobnicate", 1.0, Some(2.0)))
    unknown should include("frobnicate")
    binaryOperations.foreach(operation => unknown should include(operation))
    unknown should include("sqrt")
    unknown should include("abs")

    failure(calc("", 1.0, Some(2.0))) should include("Unknown operation")
  }

  // ---- parameters

  it should "report a missing operation or first operand by name" in {
    failure(raw(ujson.Obj("a" -> 1.0, "b" -> 1.0))) should include("operation")
    failure(raw(ujson.Obj("operation" -> "add", "b" -> 1.0))) should include("'a'")
  }

  it should "report wrongly typed and null parameters by name, without computing anything" in {
    failure(raw(ujson.Obj("operation" -> "add", "a" -> "5", "b" -> 1.0))) should include("'a'")
    failure(raw(ujson.Obj("operation" -> "add", "a" -> ujson.Null, "b" -> 1.0))) should include("'a'")
    failure(raw(ujson.Obj("operation" -> 5.0, "a" -> 1.0, "b" -> 1.0))) should include("operation")
  }

  it should "ignore parameters it does not know when called through the JSON entry point" in {
    val json = ujson.Obj("operation" -> "add", "a" -> 1.0, "b" -> 2.0, "unrelated" -> 7)
    val out  = tool.execute(json).fold(e => fail(s"Expected Right but got Left: $e"), identity)
    out("result").num shouldBe 3.0
  }

  it should "return a JSON object with the expression, the numeric result and the formatted text" in {
    val out = tool
      .execute(ujson.Obj("operation" -> "add", "a" -> 2.0, "b" -> 3.0))
      .fold(e => fail(s"Expected Right but got Left: $e"), identity)
    out.obj.keySet shouldBe Set("expression", "result", "formatted")
    out("expression").str shouldBe "2.0 + 3.0"
    out("result").num shouldBe 5.0
    out("formatted").str shouldBe "5"
  }

  it should "reject arguments that are not a JSON object" in {
    tool.execute(ujson.Str("add")).isLeft shouldBe true
  }

  // ---- the expression echoed back to the model

  it should "describe each operation in its expression" in {
    val expected = Seq(
      ("add", 5.0, Some(3.0), "5.0 + 3.0"),
      ("subtract", 5.0, Some(3.0), "5.0 - 3.0"),
      ("multiply", 5.0, Some(3.0), "5.0 * 3.0"),
      ("divide", 6.0, Some(3.0), "6.0 / 3.0"),
      ("power", 2.0, Some(3.0), "2.0 ^ 3.0"),
      ("sqrt", 16.0, None, "sqrt(16.0)"),
      ("percentage", 15.0, Some(200.0), "15.0% of 200.0"),
      ("abs", -4.0, None, "abs(-4.0)"),
      ("min", 4.0, Some(9.0), "min(4.0, 9.0)"),
      ("max", 4.0, Some(9.0), "max(4.0, 9.0)"),
      ("modulo", 10.0, Some(3.0), "10.0 mod 3.0")
    )
    expected.foreach { case (operation, a, b, text) =>
      withClue(s"$operation: ")(ok(operation, a, b).expression shouldBe text)
    }
  }

  // ---- rendering of the result

  "CalculatorTool results" should "render a whole number without a decimal point" in {
    ok("add", 5.0, Some(3.0)).formatted shouldBe "8"
    ok("subtract", 3.0, Some(10.0)).formatted shouldBe "-7"
    ok("abs", 2.0).formatted shouldBe "2"
  }

  it should "round to six decimal places and drop trailing zeros" in {
    ok("sqrt", 2.0).formatted shouldBe "1.414214"
    ok("divide", 1.0, Some(3.0)).formatted shouldBe "0.333333"
    ok("divide", 2.0, Some(3.0)).formatted shouldBe "0.666667"
    ok("power", 2.0, Some(-2.0)).formatted shouldBe "0.25"
    ok("percentage", 12.5, Some(10.0)).formatted shouldBe "1.25"
  }

  it should "keep the full-precision value in the result when the formatted text is rounded" in {
    val sum = ok("add", 0.1, Some(0.2))
    sum.result shouldBe 0.30000000000000004
    sum.formatted shouldBe "0.3"
  }

  it should "render values beyond the range of a Long without an exponent" in {
    ok("multiply", 1e10, Some(1e10)).formatted shouldBe "100000000000000000000"
    ok("multiply", 1e11, Some(1e11)).formatted shouldBe "10000000000000000000000"
  }

  it should "render a value below the rounding precision as zero and negative zero as zero" in {
    ok("add", 1e-7, Some(0.0)).formatted shouldBe "0"
    ok("multiply", -1.0, Some(0.0)).formatted shouldBe "0"
  }

  // ---- formatting does not depend on the default locale

  "CalculatorTool formatting" should "not depend on the JVM's default locale" in {
    formatLocales.foreach { locale =>
      withFormatLocale(locale) {
        withClue(s"$locale: ") {
          ok("divide", 1.0, Some(2.0)).formatted shouldBe "0.5"
          ok("divide", 1.0, Some(3.0)).formatted shouldBe "0.333333"
          ok("divide", -2.0, Some(3.0)).formatted shouldBe "-0.666667"
          ok("sqrt", 2.0).formatted shouldBe "1.414214"
          ok("add", 1234567.891, Some(0.0)).formatted shouldBe "1234567.891"
          ok("add", 5.0, Some(3.0)).formatted shouldBe "8"
        }
      }
    }
  }

  it should "keep dropping trailing zeros in every locale" in {
    formatLocales.foreach { locale =>
      withFormatLocale(locale) {
        withClue(s"$locale: ") {
          ok("divide", 1.0, Some(4.0)).formatted shouldBe "0.25"
          ok("percentage", 12.5, Some(10.0)).formatted shouldBe "1.25"
          ok("divide", 7.0, Some(2.0)).formatted shouldBe "3.5"
        }
      }
    }
  }

  it should "leave the numeric result and the expression untouched by the locale" in {
    withFormatLocale(Locale.GERMANY) {
      val r = ok("divide", 1.0, Some(2.0))
      r.result shouldBe 0.5
      r.expression shouldBe "1.0 / 2.0"
    }
  }

  it should "render the smallest representable step and small negatives" in {
    ok("multiply", 1e-6, Some(1.0)).formatted shouldBe "0.000001"
    ok("multiply", -1e-6, Some(1.0)).formatted shouldBe "-0.000001"
    ok("add", 123456789.123456, Some(0.0)).formatted shouldBe "123456789.123456"
  }

  // ---- results that are not finite numbers are errors

  "CalculatorTool non-finite results" should "be reported as errors, as division by zero and sqrt of a negative are" in {
    failure(calc("power", 10.0, Some(400.0))) should include("finite")
    failure(calc("power", -8.0, Some(1.0 / 3.0))) should include("finite")
    failure(calc("multiply", 1e200, Some(1e200))) should include("finite")
  }

  it should "cover overflow in every operation that can overflow" in {
    val overflowing = Seq(
      ("add", Double.MaxValue, Some(Double.MaxValue)),
      ("subtract", -Double.MaxValue, Some(Double.MaxValue)),
      ("multiply", 1e300, Some(1e300)),
      ("divide", 1.0, Some(1e-320)),
      ("power", 10.0, Some(309.0)),
      ("percentage", Double.MaxValue, Some(200.0))
    )
    overflowing.foreach { case (operation, a, b) =>
      withClue(s"$operation($a, $b): ")(failure(calc(operation, a, b)) should include("finite"))
    }
  }

  it should "reject an undefined result" in {
    failure(calc("power", -1.0, Some(0.5))) should include("finite")
    failure(calc("power", -8.0, Some(1.0 / 3.0))) should include("finite")
  }

  it should "accept the largest finite results" in {
    ok("add", Double.MaxValue, Some(0.0)).result shouldBe Double.MaxValue
    ok("multiply", Double.MaxValue, Some(1.0)).result shouldBe Double.MaxValue
    ok("subtract", -Double.MaxValue, Some(0.0)).result shouldBe -Double.MaxValue
    ok("power", 10.0, Some(308.0)).result shouldBe 1e308
    ok("percentage", 1e307, Some(100.0)).result shouldBe 1e307
  }

  it should "accept the smallest results without turning them into errors" in {
    ok("divide", 1.0, Some(1e300)).result shouldBe 1e-300
    ok("multiply", 1e-300, Some(1e-300)).result shouldBe 0.0
    ok("power", 0.0, Some(0.0)).result shouldBe 1.0
  }

  it should "report overflow through the tool's entry point as an error, not as the text Infinity" in {
    val reply = tool.execute(params("power", 10.0, Some(400.0)))
    reply.isLeft shouldBe true
    reply.left.map(_.getMessage).left.getOrElse("") should include("finite")
  }

  it should "still return the other errors unchanged" in {
    failure(calc("divide", 1.0, Some(0.0))) should include("zero")
    failure(calc("sqrt", -1.0)) should include("negative")
  }
}
