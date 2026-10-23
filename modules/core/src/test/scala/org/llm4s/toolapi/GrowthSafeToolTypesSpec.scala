package org.llm4s.toolapi

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * The tool schemas, `PropertyDefinition`, `SafeParameterExtractor`, `ToolCallRequest` and `ToolFunction` are
 * growth-prone (#2381): built through `apply`, changed through `with*`; `new` and `copy` are private.
 */
class GrowthSafeToolTypesSpec extends AnyFlatSpec with Matchers {

  private def inaccessible(errors: List[scala.compiletime.testing.Error]): Unit =
    errors.map(_.message).mkString(" ") should include("cannot be accessed")

  "StringSchema" should "default everything but the description" in {
    StringSchema("s") shouldBe Schema.string("s")
    val s = StringSchema("s")
    s.enumValues shouldBe None
    s.minLength shouldBe None
    s.maxLength shouldBe None
  }

  it should "change each field through its setter" in {
    val s = StringSchema("s").withDescription("d").withEnum(Seq("a", "a", "b")).withMinLength(1).withMaxLength(5)
    s shouldBe StringSchema("d", Some(Seq("a", "b")), Some(1), Some(5))
    s.withEnum(None).withMinLength(None).withMaxLength(None) shouldBe StringSchema("d")
    s.withEnum(Some(Seq("x", "x"))).withMinLength(Some(2)).withMaxLength(Some(3)) shouldBe
      StringSchema("d", Some(Seq("x")), Some(2), Some(3))
  }

  it should "have no public constructor and no public copy" in {
    val s = StringSchema("s")
    assert(s.description == "s")
    inaccessible(scala.compiletime.testing.typeCheckErrors("""new StringSchema("s", None, None, None)"""))
    inaccessible(scala.compiletime.testing.typeCheckErrors("""s.copy(description = "d")"""))
  }

  "NumberSchema" should "default everything but the description" in {
    NumberSchema("n") shouldBe NumberSchema("n", false, None, None, None, None, None)
  }

  it should "change each field through its setter" in {
    val n = NumberSchema("n")
      .withDescription("d")
      .withIsInteger(true)
      .withMinimum(1.0)
      .withMaximum(9.0)
      .withExclusiveMinimum(0.0)
      .withExclusiveMaximum(10.0)
      .withMultipleOf(Some(0.5))
    n shouldBe NumberSchema("d", true, Some(1.0), Some(9.0), Some(0.0), Some(10.0), Some(0.5))
    n.withIsInteger(false)
      .withMinimum(None)
      .withMaximum(None)
      .withExclusiveMinimum(None)
      .withExclusiveMaximum(None)
      .withMultipleOf(None) shouldBe NumberSchema("d")
    n.withMinimum(Some(2.0))
      .withMaximum(Some(3.0))
      .withExclusiveMinimum(Some(1.0))
      .withExclusiveMaximum(Some(4.0)) shouldBe
      NumberSchema("d", true, Some(2.0), Some(3.0), Some(1.0), Some(4.0), Some(0.5))
  }

  it should "have no public constructor and no public copy" in {
    val n = NumberSchema("n")
    assert(n.description == "n")
    inaccessible(
      scala.compiletime.testing.typeCheckErrors("""new NumberSchema("n", false, None, None, None, None, None)""")
    )
    inaccessible(scala.compiletime.testing.typeCheckErrors("""n.copy(description = "d")"""))
  }

  "IntegerSchema" should "default everything but the description" in {
    IntegerSchema("i") shouldBe IntegerSchema("i", None, None, None, None, None)
  }

  it should "change each field through its setter" in {
    val i = IntegerSchema("i")
      .withDescription("d")
      .withMinimum(1)
      .withMaximum(9)
      .withExclusiveMinimum(0)
      .withExclusiveMaximum(10)
      .withMultipleOf(Some(3))
    i shouldBe IntegerSchema("d", Some(1), Some(9), Some(0), Some(10), Some(3))
    i.withMinimum(None)
      .withMaximum(None)
      .withExclusiveMinimum(None)
      .withExclusiveMaximum(None)
      .withMultipleOf(None) shouldBe IntegerSchema("d")
    i.withMinimum(Some(2)).withMaximum(Some(3)).withExclusiveMinimum(Some(1)).withExclusiveMaximum(Some(4)) shouldBe
      IntegerSchema("d", Some(2), Some(3), Some(1), Some(4), Some(3))
  }

  it should "have no public constructor and no public copy" in {
    val i = IntegerSchema("i")
    assert(i.description == "i")
    inaccessible(scala.compiletime.testing.typeCheckErrors("""new IntegerSchema("i", None, None, None, None, None)"""))
    inaccessible(scala.compiletime.testing.typeCheckErrors("""i.copy(description = "d")"""))
  }

  "BooleanSchema" should "change its description through its setter" in {
    val b = BooleanSchema("b")
    b.withDescription("d") shouldBe BooleanSchema("d")
    inaccessible(scala.compiletime.testing.typeCheckErrors("""new BooleanSchema("b")"""))
    inaccessible(scala.compiletime.testing.typeCheckErrors("""b.copy(description = "d")"""))
  }

  "ArraySchema" should "default the constraints" in {
    ArraySchema("a", Schema.string("s")) shouldBe ArraySchema("a", Schema.string("s"), None, None, false)
  }

  it should "change each field through its setter" in {
    val a = ArraySchema("a", Schema.string("s"))
      .withDescription("d")
      .withMinItems(1)
      .withMaxItems(4)
      .withUniqueItems()
    a shouldBe ArraySchema("d", Schema.string("s"), Some(1), Some(4), true)
    a.withMinItems(None).withMaxItems(None).withUniqueItems(false) shouldBe ArraySchema("d", Schema.string("s"))
    a.withMinItems(Some(2)).withMaxItems(Some(3)) shouldBe ArraySchema("d", Schema.string("s"), Some(2), Some(3), true)

    val ints: ArraySchema[Int] = a.withItemSchema(Schema.integer("i"))
    ints shouldBe ArraySchema("d", Schema.integer("i"), Some(1), Some(4), true)
  }

  it should "have no public constructor and no public copy" in {
    val a = ArraySchema("a", Schema.string("s"))
    assert(a.description == "a")
    inaccessible(
      scala.compiletime.testing.typeCheckErrors("""new ArraySchema("a", Schema.string("s"), None, None, false)""")
    )
    inaccessible(scala.compiletime.testing.typeCheckErrors("""a.copy(description = "d")"""))
  }

  "PropertyDefinition" should "default to required" in {
    PropertyDefinition("p", Schema.string("s")).required shouldBe true
  }

  it should "change each field through its setter" in {
    val p = PropertyDefinition("p", Schema.string("s")).withName("q").withRequired(false)
    p shouldBe PropertyDefinition("q", Schema.string("s"), required = false)
    val q: PropertyDefinition[Boolean] = p.withSchema(Schema.boolean("b"))
    q shouldBe PropertyDefinition("q", Schema.boolean("b"), required = false)
    inaccessible(scala.compiletime.testing.typeCheckErrors("""new PropertyDefinition("p", Schema.string("s"), true)"""))
    inaccessible(scala.compiletime.testing.typeCheckErrors("""p.copy(name = "r")"""))
  }

  "ObjectSchema" should "default to no additional properties" in {
    ObjectSchema[Unit]("o", Seq.empty).additionalProperties shouldBe false
  }

  it should "change each field through its setter" in {
    val prop = Schema.property("p", Schema.string("s"))
    val o    = Schema.`object`[Unit]("o").withDescription("d").withProperties(Seq(prop)).withAdditionalProperties(true)
    o shouldBe ObjectSchema[Unit]("d", Seq(prop), additionalProperties = true)
    o.toJsonSchema(strict = false)("additionalProperties") shouldBe ujson.Bool(true)
    inaccessible(scala.compiletime.testing.typeCheckErrors("""new ObjectSchema[Unit]("o", Seq.empty, false)"""))
    inaccessible(scala.compiletime.testing.typeCheckErrors("""o.copy(description = "e")"""))
  }

  "SafeParameterExtractor" should "default to no call" in {
    SafeParameterExtractor(ujson.Obj()).call shouldBe None
  }

  it should "change each field through its setter" in {
    val call = ToolCallInfo(toolCallId = "call_1", idempotencyKey = "k" * 64)
    val e    = SafeParameterExtractor(ujson.Obj()).withParams(ujson.Obj("a" -> 1)).withCall(call)
    e shouldBe SafeParameterExtractor(ujson.Obj("a" -> 1), Some(call))
    e.getInt("a") shouldBe Right(1)
    e.withCall(None) shouldBe SafeParameterExtractor(ujson.Obj("a" -> 1))
    e.withCall(Some(call)).call shouldBe Some(call)
    inaccessible(scala.compiletime.testing.typeCheckErrors("""new SafeParameterExtractor(ujson.Obj(), None)"""))
    inaccessible(scala.compiletime.testing.typeCheckErrors("""e.copy(call = None)"""))
  }

  "ToolCallRequest" should "change each field through its setter" in {
    val r = ToolCallRequest("f", ujson.Obj()).withFunctionName("g").withArguments(ujson.Obj("x" -> 1))
    r shouldBe ToolCallRequest("g", ujson.Obj("x" -> 1))
    inaccessible(scala.compiletime.testing.typeCheckErrors("""new ToolCallRequest("f", ujson.Obj())"""))
    inaccessible(scala.compiletime.testing.typeCheckErrors("""r.copy(functionName = "h")"""))
  }

  "ToolFunction" should "change each field through its setter" in {
    val schema = Schema.`object`[Map[String, Any]]("p").withProperty(Schema.property("x", Schema.string("x")))
    val tool   = ToolFunction[Map[String, Any], String]("t", "desc", schema, _ => Right("one"))

    val changed = tool.withName("u").withDescription("other").withHandler(_ => Right("two"))
    changed.name shouldBe "u"
    changed.description shouldBe "other"
    changed.schema shouldBe schema
    changed.execute(ujson.Obj("x" -> "v")) shouldBe Right(ujson.Str("two"))

    val retyped: ToolFunction[Boolean, String] = changed.withSchema(Schema.boolean("b"))
    retyped.schema shouldBe Schema.boolean("b")
    retyped.name shouldBe "u"
    retyped.execute(ujson.Obj()) shouldBe Right(ujson.Str("two"))
  }

  it should "have no public constructor and no public copy" in {
    val tool = ToolFunction[Boolean, String]("t", "d", Schema.boolean("b"), _ => Right("x"))
    assert(tool.name == "t")
    inaccessible(
      scala.compiletime.testing.typeCheckErrors(
        """new ToolFunction[Boolean, String]("t", "d", Schema.boolean("b"), _ => Right("x"))"""
      )
    )
    inaccessible(scala.compiletime.testing.typeCheckErrors("""tool.copy(name = "u")"""))
  }
}
