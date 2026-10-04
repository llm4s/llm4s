package org.llm4s.agent.graph.tool

import org.llm4s.error.ValidationError
import org.llm4s.toolapi.{ Schema, ToolFunction }
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ToolSetSpec extends AnyFlatSpec with Matchers with EitherValues {
  import AgentToolFixtures._

  private def tool(name: String): AgentTool[Search] =
    AgentTool(AgentToolSpec[Search](name, s"Tool $name", searchSchema))((_, _) => ToolOutcome.Success(ujson.Null))

  private def badlyNamed(name: String): AgentTool[ujson.Value] =
    AgentTool.fromToolFunction(
      ToolFunction[Map[String, Any], String](name, "d", Schema.`object`("o"), _ => Right("x"))
    )

  private val refusingValidator = new ToolArgumentValidator {
    def unsupported(schema: ujson.Value): Vector[String]                      = Vector("$.properties.query.format")
    def validate(schema: ujson.Value, arguments: ujson.Value): Vector[String] = Vector.empty
  }

  "ToolSet.of" should "build a set, look tools up by name and list definitions in order" in {
    val (a, b) = (tool("b_tool"), tool("a_tool"))
    val set    = ToolSet.of(a, b).value
    set.tools shouldBe Vector(a, b)
    set.get("a_tool") shouldBe Some(b)
    set.get("missing") shouldBe None
    set.definitions shouldBe Vector(a.spec.toolDefinition, b.spec.toolDefinition)
    set.validator shouldBe ToolArgumentValidator.default
  }

  it should "use the validator it is given" in {
    val permissive = new ToolArgumentValidator {
      def unsupported(schema: ujson.Value): Vector[String]                      = Vector.empty
      def validate(schema: ujson.Value, arguments: ujson.Value): Vector[String] = Vector.empty
    }
    ToolSet.of(permissive, tool("a")).value.validator shouldBe permissive
  }

  it should "refuse an invalid name" in {
    val error = ToolSet.of(badlyNamed("bad name")).left.value
    error shouldBe a[ValidationError]
    error.message should include("'bad name'")
    error.message should include("invalid tool name")
  }

  it should "refuse duplicate names" in {
    val error = ToolSet.of(tool("a"), tool("a")).left.value
    error.message should include("'a'")
    error.message should include("duplicate tool name")
  }

  it should "refuse a schema keyword the validator does not support" in {
    val error = ToolSet.of(refusingValidator, tool("a")).left.value
    error.message should include("'a'")
    error.message should include("$.properties.query.format")
  }

  it should "report every problem in one ValidationError, one per line" in {
    val error = ToolSet.of(refusingValidator, badlyNamed("bad name"), tool("a"), tool("a")).left.value
    error shouldBe a[ValidationError]
    val lines = error.message.split("\n").toVector
    lines.count(_.contains("invalid tool name")) shouldBe 1
    lines.count(_.contains("duplicate tool name")) shouldBe 1
    lines.count(_.contains("$.properties.query.format")) shouldBe 3
    lines.size shouldBe 5
  }

  "ToolSet.empty" should "hold no tools" in {
    ToolSet.empty.tools shouldBe Vector.empty
    ToolSet.empty.definitions shouldBe Vector.empty
    ToolSet.empty.get("a") shouldBe None
  }
}
