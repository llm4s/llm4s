package org.llm4s.schema

import org.llm4s.schema.ConstantFixtures.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * An annotation's text may be any constant string the compiler can work out, and one that is not is a compile
 * error rather than an annotation silently ignored (#2951).
 */
class AnnotationConstantsSpec extends AnyFlatSpec with Matchers {

  private def descriptions[A](using schema: SchemaOf[A]): Map[String, String] =
    schema
      .toJsonSchema(strict = true)("properties")
      .obj
      .map { case (name, property) =>
        name -> property("description").str
      }
      .toMap

  private def errors(found: List[scala.compiletime.testing.Error]): List[(String, Int)] =
    found.map(e => e.message -> e.column)

  private val accepted =
    "It is read at compile time, so it must be a string literal, a `final val` or `inline val` declared without a " +
      "type, a `+` of such constants, or an s\"...\" interpolation whose arguments are all such constants."

  "A field's @description" should "be read from each form of constant in the reproduction of #2951" in {
    descriptions[Probed] shouldBe Map(
      "literal"      -> "a literal",
      "concatenated" -> "two literals",
      "withConstant" -> "at most 750 characters",
      "interpolated" -> "at most 750 characters",
      "finalVal"     -> "from a final val",
      "inlineVal"    -> "from an inline val"
    )
  }

  it should "read escapes, several constants, a named argument and a typed one as the code would at run time" in {
    descriptions[Mixed] shouldBe Map(
      "escaped" -> (s"a tab\there, ${Limits.Text}: " + s"${Limits.Limit}${true}" + '!'),
      "named"   -> "named from an inline val",
      "typed"   -> "typed"
    )
    descriptions[Mixed].apply("escaped") shouldBe "a tab\there, from a final val: 750true!"
  }

  it should "be a compile error at the annotation, naming the field, when given a def" in {
    val code = "{ final case class Bad(ok: Int, @description(Limits.text) field: String); SchemaOf.derived[Bad] }"
    val found = scala.compiletime.testing.typeCheckErrors(
      "{ final case class Bad(ok: Int, @description(Limits.text) field: String); SchemaOf.derived[Bad] }"
    )
    errors(found) shouldBe List(
      (
        "Cannot derive SchemaOf[Bad]: the @description of field field is not a constant string: Limits.text. " + accepted,
        code.indexOf("@description") + 1
      )
    )
  }

  it should "be a compile error when given a val that is not final, or a final val with a declared type" in {
    val notFinal = scala.compiletime.testing.typeCheckErrors(
      "{ final case class Bad(@description(Limits.NotFinal) field: String); SchemaOf.derived[Bad] }"
    )
    notFinal.map(_.message) shouldBe List(
      "Cannot derive SchemaOf[Bad]: the @description of field field is not a constant string: Limits.NotFinal. " +
        accepted
    )
    val widened = scala.compiletime.testing.typeCheckErrors(
      "{ final case class Bad(@description(\"x\" + Limits.Widened) field: String); SchemaOf.derived[Bad] }"
    )
    widened.map(_.message).mkString should include("the @description of field field is not a constant string")
  }

  it should "be a compile error when it interpolates a value known only at run time" in {
    val found = scala.compiletime.testing.typeCheckErrors(
      "{ final case class Bad(@description(s\"at most ${Limits.count} characters\") field: String); " +
        "SchemaOf.derived[Bad] }"
    )
    found.map(_.message).mkString should include("the @description of field field is not a constant string")
    found.map(_.message).mkString should include(accepted)
  }

  it should "be a compile error when it uses an interpolator other than s" in {
    scala.compiletime.testing
      .typeCheckErrors(
        "{ final case class Bad(@description(f\"at most ${Limits.Limit}%d\") field: String); SchemaOf.derived[Bad] }"
      )
      .map(_.message)
      .mkString should include("is not a constant string")
  }

  it should "name the type and the field when the class is declared in another file" in {
    scala.compiletime.testing
      .typeCheckErrors("SchemaOf.derived[NonConstantFixtures.DefDescription]")
      .map(_.message)
      .mkString should include(
      "Cannot derive SchemaOf[org.llm4s.schema.NonConstantFixtures.DefDescription]: the @description of field " +
        "field is not a constant string: "
    )
  }

  "A type's @description" should "be read from a constant, for a case class and for the cases of a sum" in {
    val described = summon[SchemaOf[DescribedType]].toJsonSchema(strict = true)
    described("description").str shouldBe "A type from a final val"

    val cases = summon[SchemaOf[Choice]].toJsonSchema(strict = true)("anyOf").arr.map(_("description").str)
    cases.toList shouldBe List("Refund up to 750", "Escalate, from an inline val")
  }

  it should "be a compile error when it is not a constant, on a type and on a case" in {
    scala.compiletime.testing
      .typeCheckErrors("{ @description(Limits.text) final case class Bad(n: Int); SchemaOf.derived[Bad] }")
      .map(_.message) shouldBe List(
      "Cannot derive SchemaOf[Bad]: the @description of Bad is not a constant string: Limits.text. " + accepted
    )
    scala.compiletime.testing
      .typeCheckErrors("SchemaOf.derived[NonConstantFixtures.DefCase]")
      .map(_.message)
      .mkString should include(
      "Cannot derive SchemaOf[org.llm4s.schema.NonConstantFixtures.DefCase.Refund]: the @description of Refund is " +
        "not a constant string: "
    )
    scala.compiletime.testing
      .typeCheckErrors("SchemaOf.derived[NonConstantFixtures.DefMixed]")
      .map(_.message)
      .mkString should include("the @description of case Stop is not a constant string")
  }

  "A field's @upickle.implicits.key" should "name the property when it is a constant" in {
    val schema = summon[SchemaOf[ConstantKey]].toJsonSchema(strict = true)
    schema("properties").obj.keys.toList shouldBe List("user_name", "age")
    schema("required").arr.map(_.str).toList shouldBe List("user_name", "age")
  }

  it should "be a compile error when it is not a constant, as it is for uPickle" in {
    scala.compiletime.testing
      .typeCheckErrors(
        "{ final case class Bad(@upickle.implicits.key(Limits.text) field: String); SchemaOf.derived[Bad] }"
      )
      .map(_.message) shouldBe List(
      "Cannot derive SchemaOf[Bad]: the @key of field field is not a constant string: Limits.text. " + accepted
    )
    scala.compiletime.testing
      .typeCheckErrors("SchemaOf.derived[NonConstantFixtures.DefDiscriminator]")
      .map(_.message)
      .mkString should include("the @key of DefDiscriminator is not a constant string")
  }

  "A partial type" should "read a field under its constant key" in {
    val partial = summon[PartialOf[ConstantKey]]
    val read    = PartialJson.parseTree("""{"user_name": "ada", "age": 36}""").flatMap(partial.read)
    read.map(_.toString) shouldBe Some("(Some(ada),Some(36))")
  }

  it should "be a compile error when a key is not a constant" in {
    scala.compiletime.testing
      .typeCheckErrors(
        "{ final case class Bad(@upickle.implicits.key(Limits.text) field: String); PartialOf.derived[Bad] }"
      )
      .map(_.message) shouldBe List(
      "Cannot derive PartialOf[Bad]: the @key of field field is not a constant string: Limits.text. " + accepted
    )
  }

  "A tool derived from a method" should "take its name and descriptions from constants" in {
    val tools = Tools.derive[ConstantTools](new ConstantTools { def find(query: String): String = query })
    val tool  = tools.fold(error => fail(error.message), identity).head
    tool.name shouldBe "find_by_constant"
    tool.description shouldBe "Finds from a final val"
    tool.schema.toJsonSchema(true)("properties")("query")("description").str shouldBe "at most 750 characters"
  }

  it should "be a compile error when a @description or a @toolName is not a constant" in {
    def messages(found: List[scala.compiletime.testing.Error]): String = found.map(_.message).mkString("\n")

    messages(
      scala.compiletime.testing.typeCheckErrors(
        "Tools.derive[DefToolDescription](null.asInstanceOf[DefToolDescription])"
      )
    ) should include(
      "Cannot derive a tool from DefToolDescription.find: the @description of the method is not a constant string: "
    )
    messages(
      scala.compiletime.testing.typeCheckErrors(
        "Tools.derive[DefParameterDescription](null.asInstanceOf[DefParameterDescription])"
      )
    ) should include(
      "Cannot derive a tool from DefParameterDescription.find: the @description of parameter query is not a " +
        "constant string: "
    )
    val named = messages(
      scala.compiletime.testing.typeCheckErrors("Tools.derive[DefToolName](null.asInstanceOf[DefToolName])")
    )
    named should include(
      "Cannot derive a tool from DefToolName.find: the @toolName of the method is not a constant string: "
    )
    named should include(accepted)
  }
}
