package org.llm4s.schema

import org.llm4s.schema.MethodFixtures.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * A method's tool checks a call's arguments exactly as `Tool.of` does for a case class with the same fields: the
 * same `ToolParameterError`s for missing, null and mistyped arguments, on shared fixtures (#2326).
 */
class DerivedToolArgumentsSpec extends AnyFlatSpec with Matchers {

  private def built[T](tool: org.llm4s.types.Result[T]): T =
    tool.fold(e => sys.error(s"could not build the tool: ${e.formatted}"), identity)

  private val fromMethod = built(Tool.fromMethod(Shapes.everything))
  private val fromClass  = built(Tool.of[Everything, Everything]("everything", "unused")(Right(_)))

  private val valid = ujson.Obj(
    "count"   -> 1,
    "big"     -> 5e12,
    "ratio"   -> 0.5,
    "flag"    -> true,
    "note"    -> "n",
    "tags"    -> ujson.Arr("a"),
    "address" -> ujson.Obj("street" -> "Main", "city" -> "Oslo"),
    "amount"  -> "12.50"
  )

  private def withField(key: String, value: ujson.Value): ujson.Value = {
    val arguments = ujson.Obj.from(valid.value)
    arguments(key) = value
    arguments
  }

  private def without(keys: String*): ujson.Value = ujson.Obj.from(valid.value.filterNot(kv => keys.contains(kv._1)))

  "A method's tool" should "derive the schema of the equivalent case class" in {
    fromMethod.schema.toJsonSchema(true)("properties") shouldBe fromClass.schema.toJsonSchema(true)("properties")
    fromMethod.schema.toJsonSchema(false)("required") shouldBe fromClass.schema.toJsonSchema(false)("required")
  }

  it should "answer valid arguments as the case class's tool does, with an Option absent or null" in {
    for (arguments <- List(valid, without("note"), withField("note", ujson.Null)))
      withClue(arguments.render()) {
        fromMethod.execute(arguments) shouldBe fromClass.execute(arguments)
        fromMethod.execute(arguments).isRight shouldBe true
      }
  }

  it should "report missing, null and mistyped arguments as Tool.of does" in {
    val cases = List(
      without("count"),
      without("count", "flag"),
      withField("count", ujson.Null),
      withField("count", "one"),
      withField("count", 1.5),
      withField("count", 1e10),
      withField("big", 1.5),
      withField("ratio", "half"),
      withField("flag", 1),
      withField("note", 7),
      withField("tags", "a"),
      withField("tags", ujson.Arr(1)),
      withField("tags", ujson.Arr(ujson.Null)),
      withField("address", "Main Street"),
      withField("address", ujson.Obj("street" -> "Main")),
      withField("address", ujson.Obj("street" -> "Main", "city" -> 3)),
      withField("amount", "abc"),
      withField("amount", 12.5),
      ujson.Str("everything"),
      ujson.Arr(1, 2),
      ujson.Null
    )
    for (arguments <- cases)
      withClue(arguments.render()) {
        fromMethod.execute(arguments) shouldBe fromClass.execute(arguments)
        fromMethod.execute(arguments).isLeft shouldBe true
      }
  }
}
