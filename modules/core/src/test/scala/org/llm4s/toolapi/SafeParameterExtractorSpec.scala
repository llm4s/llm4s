package org.llm4s.toolapi

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SafeParameterExtractorSpec extends AnyFlatSpec with Matchers {

  // ---- simple mode: primitive types ----

  "SafeParameterExtractor.getString" should "return the string value" in {
    SafeParameterExtractor(ujson.Obj("x" -> ujson.Str("hello"))).getString("x") shouldBe Right("hello")
  }

  "SafeParameterExtractor.getInt" should "return the int value" in {
    SafeParameterExtractor(ujson.Obj("n" -> ujson.Num(42))).getInt("n") shouldBe Right(42)
  }

  it should "return Left for a wrong-type value" in {
    SafeParameterExtractor(ujson.Obj("n" -> ujson.Str("oops"))).getInt("n") shouldBe a[Left[_, _]]
  }

  "SafeParameterExtractor.getDouble" should "return the double value" in {
    SafeParameterExtractor(ujson.Obj("d" -> ujson.Num(3.14))).getDouble("d") shouldBe Right(3.14)
  }

  "SafeParameterExtractor.getBoolean" should "return the boolean value" in {
    SafeParameterExtractor(ujson.Obj("flag" -> ujson.Bool(true))).getBoolean("flag") shouldBe Right(true)
  }

  "SafeParameterExtractor.getArray" should "return an Arr for an array value" in {
    val arr = ujson.Arr(ujson.Num(1), ujson.Num(2))
    SafeParameterExtractor(ujson.Obj("items" -> arr)).getArray("items") shouldBe Right(arr)
  }

  it should "return Left when value is not an array" in {
    SafeParameterExtractor(ujson.Obj("items" -> ujson.Str("oops"))).getArray("items") shouldBe a[Left[_, _]]
  }

  "SafeParameterExtractor.getObject" should "return an Obj for an object value" in {
    val inner = ujson.Obj("k" -> ujson.Num(1))
    SafeParameterExtractor(ujson.Obj("inner" -> inner)).getObject("inner") shouldBe Right(inner)
  }

  it should "return Left when value is not an object" in {
    SafeParameterExtractor(ujson.Obj("inner" -> ujson.Num(5))).getObject("inner") shouldBe a[Left[_, _]]
  }

  // ---- enhanced mode: primitive types ----

  "SafeParameterExtractor.getStringEnhanced" should "return Right for a present string" in {
    SafeParameterExtractor(ujson.Obj("s" -> ujson.Str("hi"))).getStringEnhanced("s") shouldBe Right("hi")
  }

  it should "return MissingParameter when key is absent" in {
    val result = SafeParameterExtractor(ujson.Obj("other" -> ujson.Str("x"))).getStringEnhanced("s")
    result.left.toOption.get shouldBe a[ToolParameterError.MissingParameter]
  }

  it should "return NullParameter for a null value" in {
    val result = SafeParameterExtractor(ujson.Obj("s" -> ujson.Null)).getStringEnhanced("s")
    result.left.toOption.get shouldBe a[ToolParameterError.NullParameter]
  }

  it should "return TypeMismatch for the wrong type" in {
    val result = SafeParameterExtractor(ujson.Obj("s" -> ujson.Num(1))).getStringEnhanced("s")
    result.left.toOption.get shouldBe a[ToolParameterError.TypeMismatch]
  }

  "SafeParameterExtractor.getIntEnhanced" should "return Right for a numeric value" in {
    SafeParameterExtractor(ujson.Obj("n" -> ujson.Num(7))).getIntEnhanced("n") shouldBe Right(7)
  }

  "SafeParameterExtractor.getDoubleEnhanced" should "return Right for a double" in {
    SafeParameterExtractor(ujson.Obj("d" -> ujson.Num(2.5))).getDoubleEnhanced("d") shouldBe Right(2.5)
  }

  "SafeParameterExtractor.getBooleanEnhanced" should "return Right for a boolean" in {
    SafeParameterExtractor(ujson.Obj("b" -> ujson.Bool(false))).getBooleanEnhanced("b") shouldBe Right(false)
  }

  "SafeParameterExtractor.getArrayEnhanced" should "return Right for an array" in {
    val arr = ujson.Arr(ujson.Str("a"))
    SafeParameterExtractor(ujson.Obj("list" -> arr)).getArrayEnhanced("list") shouldBe Right(arr)
  }

  it should "return TypeMismatch for a non-array" in {
    val result = SafeParameterExtractor(ujson.Obj("list" -> ujson.Str("oops"))).getArrayEnhanced("list")
    result.left.toOption.get shouldBe a[ToolParameterError.TypeMismatch]
  }

  "SafeParameterExtractor.getObjectEnhanced" should "return Right for an object" in {
    val inner = ujson.Obj("a" -> ujson.Num(1))
    SafeParameterExtractor(ujson.Obj("inner" -> inner)).getObjectEnhanced("inner") shouldBe Right(inner)
  }

  it should "return TypeMismatch for a non-object" in {
    val result = SafeParameterExtractor(ujson.Obj("inner" -> ujson.Bool(true))).getObjectEnhanced("inner")
    result.left.toOption.get shouldBe a[ToolParameterError.TypeMismatch]
  }

  // ---- optional mode ----

  "SafeParameterExtractor.getOptionalString" should "return Some when present" in {
    SafeParameterExtractor(ujson.Obj("s" -> ujson.Str("hello"))).getOptionalString("s") shouldBe Right(Some("hello"))
  }

  it should "return None when key is absent" in {
    SafeParameterExtractor(ujson.Obj()).getOptionalString("s") shouldBe Right(None)
  }

  it should "return None for a null value" in {
    SafeParameterExtractor(ujson.Obj("s" -> ujson.Null)).getOptionalString("s") shouldBe Right(None)
  }

  it should "return TypeMismatch for the wrong type" in {
    val result = SafeParameterExtractor(ujson.Obj("s" -> ujson.Num(1))).getOptionalString("s")
    result.left.toOption.get shouldBe a[ToolParameterError.TypeMismatch]
  }

  "SafeParameterExtractor.getOptionalInt" should "return Some when present" in {
    SafeParameterExtractor(ujson.Obj("n" -> ujson.Num(3))).getOptionalInt("n") shouldBe Right(Some(3))
  }

  it should "return None when absent" in {
    SafeParameterExtractor(ujson.Obj()).getOptionalInt("n") shouldBe Right(None)
  }

  "SafeParameterExtractor.getOptionalDouble" should "return Some when present" in {
    SafeParameterExtractor(ujson.Obj("d" -> ujson.Num(1.1))).getOptionalDouble("d") shouldBe Right(Some(1.1))
  }

  it should "return None when absent" in {
    SafeParameterExtractor(ujson.Obj()).getOptionalDouble("d") shouldBe Right(None)
  }

  "SafeParameterExtractor.getOptionalBoolean" should "return Some when present" in {
    SafeParameterExtractor(ujson.Obj("flag" -> ujson.Bool(true))).getOptionalBoolean("flag") shouldBe Right(Some(true))
  }

  it should "return None when absent" in {
    SafeParameterExtractor(ujson.Obj()).getOptionalBoolean("flag") shouldBe Right(None)
  }

  // ---- validateRequired ----

  "SafeParameterExtractor.validateRequired" should "return Right when all requirements are satisfied" in {
    val ex = SafeParameterExtractor(ujson.Obj("name" -> ujson.Str("Alice"), "age" -> ujson.Num(30)))
    ex.validateRequired("name" -> "string", "age" -> "integer") shouldBe Right(())
  }

  it should "return Left with one error when a parameter is missing" in {
    val ex     = SafeParameterExtractor(ujson.Obj("name" -> ujson.Str("Alice")))
    val result = ex.validateRequired("name" -> "string", "score" -> "number")
    (result.left.toOption.get should have).length(1)
  }

  it should "collect all errors from multiple missing parameters" in {
    val ex     = SafeParameterExtractor(ujson.Obj())
    val result = ex.validateRequired("a" -> "string", "b" -> "integer", "c" -> "boolean")
    (result.left.toOption.get should have).length(3)
  }

  // ---- companion object ----

  "SafeParameterExtractor.enhanced" should "construct an extractor that retrieves values" in {
    val params = ujson.Obj("x" -> ujson.Str("y"))
    SafeParameterExtractor.enhanced(params).getString("x") shouldBe Right("y")
  }

  // ---- edge cases in path navigation ----

  "SafeParameterExtractor" should "return InvalidNesting when root is null and path is non-empty" in {
    val result = SafeParameterExtractor(ujson.Null).getStringEnhanced("key")
    result.left.toOption.get shouldBe a[ToolParameterError.InvalidNesting]
  }

  it should "return InvalidNesting when navigating into a non-object intermediate value" in {
    val result = SafeParameterExtractor(ujson.Obj("list" -> ujson.Arr())).getStringEnhanced("list.item")
    result.left.toOption.get shouldBe a[ToolParameterError.InvalidNesting]
  }

  it should "return MissingParameter when an intermediate path segment is absent" in {
    val params = ujson.Obj("a" -> ujson.Obj("b" -> ujson.Str("v")))
    val result = SafeParameterExtractor(params).getStringEnhanced("a.c.d")
    result.left.toOption.get shouldBe a[ToolParameterError.MissingParameter]
  }

  it should "navigate a dot-separated path to a deeply nested value" in {
    val params = ujson.Obj("a" -> ujson.Obj("b" -> ujson.Obj("c" -> ujson.Str("deep"))))
    SafeParameterExtractor(params).getString("a.b.c") shouldBe Right("deep")
  }
}
