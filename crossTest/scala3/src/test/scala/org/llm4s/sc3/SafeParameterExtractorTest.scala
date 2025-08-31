package org.llm4s.sc3

import org.llm4s.toolapi.SafeParameterExtractor
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ujson.*

import scala.language.strictEquality // Enable strict equality checking

class SafeParameterExtractorTest extends AnyFlatSpec with Matchers {

  // Add implicit CanEqual instances for ujson types
  given CanEqual[ujson.Arr, ujson.Arr]                                               = CanEqual.derived
  given CanEqual[ujson.Obj, ujson.Obj]                                               = CanEqual.derived
  given [T, U](using CanEqual[T, U]): CanEqual[Either[String, T], Either[String, U]] = CanEqual.derived
  given [T, U](using CanEqual[T, U]): CanEqual[Right[String, T], Right[String, U]]   = CanEqual.derived

  // Test fixtures
  val simpleJson = ujson.Obj(
    "stringValue" -> "test",
    "intValue"    -> 42,
    "doubleValue" -> 42.5,
    "boolValue"   -> true,
    "arrayValue"  -> ujson.Arr(1, 2, 3),
    "objectValue" -> ujson.Obj("key" -> "value")
  )

  val nestedJson = ujson.Obj(
    "level1" -> ujson.Obj(
      "string" -> "nested",
      "number" -> 100,
      "level2" -> ujson.Obj(
        "array"   -> ujson.Arr("a", "b", "c"),
        "boolean" -> false
      )
    )
  )

  val mixedTypesArray = ujson.Obj(
    "mixed" -> ujson.Arr(
      "string",
      42,
      true,
      ujson.Obj("key" -> "value"),
      ujson.Arr(1, 2, 3)
    )
  )

  "SafeParameterExtractor" should "extract string values correctly" in {
    val extractor = SafeParameterExtractor(simpleJson)

    // Success cases
    extractor.getString("stringValue") shouldBe Right("test")

    // Failure cases
    extractor.getString("nonexistent") shouldBe Left("Path 'nonexistent' not found: missing 'nonexistent' segment")
    extractor.getString("intValue") shouldBe Left("Value at 'intValue' is not of expected type 'string'")
  }

  it should "extract integer values correctly" in {
    val extractor = SafeParameterExtractor(simpleJson)

    // Success cases
    extractor.getInt("intValue") shouldBe Right(42)

    // Failure cases
    extractor.getInt("stringValue") shouldBe Left("Value at 'stringValue' is not of expected type 'integer'")
    extractor.getInt("doubleValue") shouldBe Right(42) // Should work for whole numbers
  }

  it should "extract double values correctly" in {
    val extractor = SafeParameterExtractor(simpleJson)

    // Success cases
    extractor.getDouble("doubleValue") shouldBe Right(42.5)
    extractor.getDouble("intValue") shouldBe Right(42.0)

    // Failure cases
    extractor.getDouble("stringValue") shouldBe Left("Value at 'stringValue' is not of expected type 'number'")
  }

  it should "extract boolean values correctly" in {
    val extractor = SafeParameterExtractor(simpleJson)

    // Success cases
    extractor.getBoolean("boolValue") shouldBe Right(true)

    // Failure cases
    extractor.getBoolean("stringValue") shouldBe Left("Value at 'stringValue' is not of expected type 'boolean'")
  }

  it should "extract array values correctly" in {
    val extractor = SafeParameterExtractor(simpleJson)

    // Success cases
    val expectedArr = ujson.Arr(1, 2, 3)
    extractor.getArray("arrayValue") shouldBe Right(expectedArr)

    // Failure cases
    extractor.getArray("stringValue") shouldBe Left("Value at 'stringValue' is not of expected type 'array'")
  }

  it should "extract object values correctly" in {
    val extractor = SafeParameterExtractor(simpleJson)

    // Success cases
    val expectedObj = ujson.Obj("key" -> "value")
    extractor.getObject("objectValue") shouldBe Right(expectedObj)

    // Failure cases
    extractor.getObject("stringValue") shouldBe Left("Value at 'stringValue' is not of expected type 'object'")
  }

  it should "handle nested paths correctly" in {
    val extractor = SafeParameterExtractor(nestedJson)

    // Success cases
    extractor.getString("level1.string") shouldBe Right("nested")
    extractor.getInt("level1.number") shouldBe Right(100)
    val expectedNestedArr = ujson.Arr("a", "b", "c")
    extractor.getArray("level1.level2.array") shouldBe Right(expectedNestedArr)
    extractor.getBoolean("level1.level2.boolean") shouldBe Right(false)

    // Failure cases
    extractor.getString("level1.nonexistent") shouldBe Left(
      "Path 'level1.nonexistent' not found: missing 'nonexistent' segment"
    )
    extractor.getString("level1.string.invalid") shouldBe Left(
      "Path 'level1.string.invalid': Expected object at 'level1.string' but found Str"
    )
  }

  it should "handle empty paths correctly" in {
    val extractor = SafeParameterExtractor(simpleJson)
    extractor.getString("") shouldBe Left("Path '' not found: missing '' segment")
  }

  it should "handle null values correctly" in {
    val jsonWithNull = ujson.Obj(
      "nullValue" -> ujson.Null
    )
    val extractor = SafeParameterExtractor(jsonWithNull)
    extractor.getString("nullValue") shouldBe Left("Value at 'nullValue' is not of expected type 'string'")
  }

  it should "handle special characters in paths" in {
    val jsonWithSpecialChars = ujson.Obj(
      "special.key" -> "value",
      "normal" -> ujson.Obj(
        "special.nested" -> "nested value"
      )
    )
    val extractor = SafeParameterExtractor(jsonWithSpecialChars)

    extractor.getString("special.key") shouldBe Left("Path 'special.key' not found: missing 'special' segment")
    extractor.getString("normal.special.nested") shouldBe Left(
      "Path 'normal.special.nested' not found: missing 'special' segment"
    )
  }
}
