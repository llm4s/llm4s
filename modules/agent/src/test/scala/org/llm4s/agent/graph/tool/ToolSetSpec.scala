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

  it should "refuse a tool whose argument schema is not an object" in {
    val scalar = AgentTool(AgentToolSpec[String]("scalar", "Takes a string", Schema.string("s"))) { (_, _) =>
      ToolOutcome.Success(ujson.Null)
    }
    val listing = AgentTool.fromToolFunction(
      ToolFunction[Seq[String], String]("listing", "d", Schema.array("l", Schema.string("s")), _ => Right("x"))
    )
    val error = ToolSet.of(scalar, listing, tool("a")).left.value
    error shouldBe a[ValidationError]
    error.message should include("tool 'scalar': argument schema must be an object (type: object)")
    error.message should include("tool 'listing': argument schema must be an object (type: object)")
    (error.message should not).include("'a'")
  }

  it should "report every problem in one ValidationError, one violation per problem" in {
    val error = ToolSet.of(refusingValidator, badlyNamed("bad name"), tool("a"), tool("a")).left.value
    val violations = error match {
      case v: ValidationError => v.violations
      case other              => fail(s"expected a ValidationError, got $other")
    }
    violations.count(_.contains("invalid tool name")) shouldBe 1
    violations.count(_.contains("duplicate tool name")) shouldBe 1
    violations.count(_.contains("$.properties.query.format")) shouldBe 3
    violations.size shouldBe 5
    violations.foreach(v => error.message should include(v))
  }

  "ToolSet.toolFunctions" should "match the set's definitions, in order" in {
    val adapted = badlyNamed("echo")
    val set     = ToolSet.of(tool("a_tool"), adapted).value
    val fns     = set.toolFunctions
    fns.map(_.name) shouldBe Vector("a_tool", "echo")
    fns.map(_.description) shouldBe Vector("Tool a_tool", "d")
    fns.map(_.toOpenAITool(true)) shouldBe set.definitions
  }

  it should "return the original function for an adapted tool and a refusing stand-in otherwise" in {
    val original = ToolFunction[Map[String, Any], String]("echo", "d", Schema.`object`("o"), _ => Right("x"))
    val set      = ToolSet.of(tool("a_tool"), AgentTool.fromToolFunction(original)).value
    (set.toolFunctions(1) should be).theSameInstanceAs(original)
    val standIn = set.toolFunctions.head.execute(ujson.Obj("query" -> "q"))
    standIn.left.value.getFormattedMessage should include("executed by ToolLoop")
  }

  "ToolSet.empty" should "hold no tools" in {
    ToolSet.empty.tools shouldBe Vector.empty
    ToolSet.empty.definitions shouldBe Vector.empty
    ToolSet.empty.get("a") shouldBe None
  }
}
