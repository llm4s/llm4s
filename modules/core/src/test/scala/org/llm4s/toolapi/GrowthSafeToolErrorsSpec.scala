package org.llm4s.toolapi

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * `ToolCallError.UnknownFunction` and `ToolParameterError.MissingParameter` are growth-prone (#2382): built through
 * `apply`, changed through `with*`; `new` and `copy` are private. Pattern matching and their JSON are unchanged.
 */
class GrowthSafeToolErrorsSpec extends AnyFlatSpec with Matchers {

  import ToolCallError.UnknownFunction
  import ToolParameterError.MissingParameter

  private def inaccessible(errors: List[scala.compiletime.testing.Error]): Unit =
    errors.map(_.message).mkString(" ") should include("cannot be accessed")

  "UnknownFunction" should "default availableTools to empty" in {
    UnknownFunction("t").availableTools shouldBe Seq.empty
    UnknownFunction("t") shouldBe UnknownFunction("t", Seq.empty)
  }

  it should "change each field through its setter" in {
    val e = UnknownFunction("t").withToolName("u").withAvailableTools(Seq("a", "b"))
    e shouldBe UnknownFunction("u", Seq("a", "b"))
    e.getMessage should include("Available tools: [a, b].")
    e.withAvailableTools(Seq.empty) shouldBe UnknownFunction("u")
  }

  it should "still match positionally and serialize as before" in {
    val e: ToolCallError = UnknownFunction("t", Seq("a"))
    e match {
      case UnknownFunction(name, available) =>
        name shouldBe "t"
        available shouldBe Seq("a")
      case other => fail(s"unexpected $other")
    }
    val json = ToolCallErrorJson.toJson(e)
    json("errorType").str shouldBe "unknown_function"
    json("toolName").str shouldBe "t"
  }

  it should "have no public constructor and no public copy" in {
    val e = UnknownFunction("t")
    assert(e.toolName == "t")
    inaccessible(scala.compiletime.testing.typeCheckErrors("""new ToolCallError.UnknownFunction("t", Seq.empty)"""))
    inaccessible(scala.compiletime.testing.typeCheckErrors("""e.copy(toolName = "u")"""))
  }

  "MissingParameter" should "default availableParameters to empty" in {
    MissingParameter("p", "string").availableParameters shouldBe Nil
    MissingParameter("p", "string") shouldBe MissingParameter("p", "string", Nil)
  }

  it should "change each field through its setter" in {
    val e = MissingParameter("p", "string")
      .withParameterName("q")
      .withExpectedType("integer")
      .withAvailableParameters(List("a", "b"))
    e shouldBe MissingParameter("q", "integer", List("a", "b"))
    e.getMessage shouldBe "required parameter 'q' (type: integer) is missing (available: a, b)"
    e.withAvailableParameters(Nil) shouldBe MissingParameter("q", "integer")
  }

  it should "still match positionally and serialize as before" in {
    val e: ToolParameterError = MissingParameter("p", "string", List("a"))
    e match {
      case MissingParameter(name, expected, available) =>
        name shouldBe "p"
        expected shouldBe "string"
        available shouldBe List("a")
      case other => fail(s"unexpected $other")
    }
    val json = ToolCallErrorJson.parameterErrorToJson(e)
    json("kind").str shouldBe "missing_parameter"
    json("availableParameters").arr.map(_.str).toList shouldBe List("a")
  }

  it should "have no public constructor and no public copy" in {
    val e = MissingParameter("p", "string")
    assert(e.parameterName == "p")
    inaccessible(
      scala.compiletime.testing.typeCheckErrors("""new ToolParameterError.MissingParameter("p", "string", Nil)""")
    )
    inaccessible(scala.compiletime.testing.typeCheckErrors("""e.copy(parameterName = "q")"""))
  }
}
