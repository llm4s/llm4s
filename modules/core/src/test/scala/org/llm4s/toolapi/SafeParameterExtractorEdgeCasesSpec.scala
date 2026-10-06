package org.llm4s.toolapi

import org.llm4s.toolapi.ToolParameterError._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Edge cases of [[SafeParameterExtractor]] that the happy-path specs do not pin: type confusion between
 * JSON strings, numbers and booleans, empty and null values, optional vs required, and the key name in
 * every error message.
 */
class SafeParameterExtractorEdgeCasesSpec extends AnyFlatSpec with Matchers {

  private def extractor(json: String): SafeParameterExtractor = SafeParameterExtractor(ujson.read(json))

  // ---- strings

  "getString" should "return an empty string as a value, not an error" in {
    extractor("""{"name":""}""").getString("name") shouldBe Right("")
  }

  it should "name the key when it is missing, and list what is available" in {
    val message = extractor("""{"other":"x"}""").getString("name").left.getOrElse(fail("expected Left"))

    message should include("'name'")
    message should include("other")
  }

  it should "reject a number with a type mismatch naming the key and both types" in {
    extractor("""{"name":42}""").getStringEnhanced("name") shouldBe
      Left(TypeMismatch("name", "string", "number"))
  }

  it should "reject a boolean with a type mismatch naming the key and both types" in {
    extractor("""{"name":true}""").getStringEnhanced("name") shouldBe
      Left(TypeMismatch("name", "string", "boolean"))
  }

  it should "reject an object and an array with a type mismatch" in {
    extractor("""{"name":{"a":1}}""").getStringEnhanced("name") shouldBe
      Left(TypeMismatch("name", "string", "object"))
    extractor("""{"name":["a"]}""").getStringEnhanced("name") shouldBe
      Left(TypeMismatch("name", "string", "array"))
  }

  it should "reject null as null, which is not the same as missing" in {
    val e = extractor("""{"name":null}""")

    e.getStringEnhanced("name") shouldBe Left(NullParameter("name", "string"))
    e.getString("name").left.getOrElse(fail("expected Left")) should include("null")
  }

  it should "keep surrounding whitespace and non-ASCII text untouched" in {
    extractor("""{"name":"  héllo wörld  "}""").getString("name") shouldBe Right("  héllo wörld  ")
  }

  // ---- integers

  "getInt" should "return a valid integer" in {
    extractor("""{"n":42}""").getInt("n") shouldBe Right(42)
  }

  it should "return negative integers and zero" in {
    extractor("""{"a":-7,"b":0}""").getInt("a") shouldBe Right(-7)
    extractor("""{"a":-7,"b":0}""").getInt("b") shouldBe Right(0)
  }

  it should "not coerce a numeric string" in {
    extractor("""{"n":"123"}""").getIntEnhanced("n") shouldBe Left(TypeMismatch("n", "integer", "string"))
  }

  it should "not coerce a boolean" in {
    extractor("""{"n":true}""").getIntEnhanced("n") shouldBe Left(TypeMismatch("n", "integer", "boolean"))
  }

  // KNOWN BUG, not fixed here: getInt, getIntEnhanced and getOptionalInt are `_.numOpt.map(_.toInt)`, so
  // {"n": 3.14} returns Right(3) and {"n": 9223372036854775807} returns Right(-1), silently. A tool argument that is not an integer should be
  // refused (a TypeMismatch is the natural error; any Left satisfies these tests, so a fix that picks another
  // error still promotes them). When fixed these fail with "marked pendingUntilFixed but passed": remove the
  // wrapper then.
  it should "reject a fractional number instead of truncating it" in {
    pendingUntilFixed {
      extractor("""{"n":3.14}""").getIntEnhanced("n").isLeft shouldBe true
      extractor("""{"n":3.14}""").getInt("n").isLeft shouldBe true
      extractor("""{"n":3.14}""").getOptionalInt("n").isLeft shouldBe true
    }
  }

  it should "reject a number outside the Int range instead of wrapping it" in {
    pendingUntilFixed {
      extractor("""{"n":9223372036854775807}""").getIntEnhanced("n").isLeft shouldBe true
      extractor("""{"n":9223372036854775807}""").getInt("n").isLeft shouldBe true
      extractor("""{"n":9223372036854775807}""").getOptionalInt("n").isLeft shouldBe true
    }
  }

  it should "report null and a missing key differently" in {
    extractor("""{"n":null}""").getIntEnhanced("n") shouldBe Left(NullParameter("n", "integer"))
    extractor("""{}""").getIntEnhanced("n") shouldBe Left(MissingParameter("n", "integer", Nil))
  }

  it should "not coerce a numeric string in the string-error API either, and say what it got" in {
    val message = extractor("""{"n":"123"}""").getInt("n").left.getOrElse(fail("expected Left"))

    message should include("'n'")
    message should include("integer")
    message should include("string")
  }

  // ---- doubles

  "getDouble" should "return fractional, negative and integral numbers" in {
    extractor("""{"a":3.14,"b":-0.5,"c":2}""").getDouble("a") shouldBe Right(3.14)
    extractor("""{"a":3.14,"b":-0.5,"c":2}""").getDouble("b") shouldBe Right(-0.5)
    extractor("""{"a":3.14,"b":-0.5,"c":2}""").getDouble("c") shouldBe Right(2.0)
  }

  it should "not coerce a numeric string" in {
    extractor("""{"x":"3.14"}""").getDoubleEnhanced("x") shouldBe Left(TypeMismatch("x", "number", "string"))
  }

  // ---- booleans

  "getBoolean" should "return both JSON booleans" in {
    extractor("""{"t":true,"f":false}""").getBoolean("t") shouldBe Right(true)
    extractor("""{"t":true,"f":false}""").getBoolean("f") shouldBe Right(false)
  }

  it should "not coerce the strings \"true\" and \"false\"" in {
    extractor("""{"t":"true"}""").getBooleanEnhanced("t") shouldBe Left(TypeMismatch("t", "boolean", "string"))
    extractor("""{"f":"false"}""").getBooleanEnhanced("f") shouldBe Left(TypeMismatch("f", "boolean", "string"))
  }

  it should "not coerce 0 and 1" in {
    extractor("""{"t":1}""").getBooleanEnhanced("t") shouldBe Left(TypeMismatch("t", "boolean", "number"))
    extractor("""{"f":0}""").getBooleanEnhanced("f") shouldBe Left(TypeMismatch("f", "boolean", "number"))
  }

  it should "not coerce the strings \"true\" and \"false\" in the string-error API either" in {
    val message = extractor("""{"t":"true"}""").getBoolean("t").left.getOrElse(fail("expected Left"))

    message should include("boolean")
    message should include("string")
    extractor("""{"f":"false"}""").getBoolean("f").isLeft shouldBe true
  }

  // ---- optional parameters

  "optional extraction" should "return Some for a present value of every type" in {
    val e = extractor("""{"s":"v","i":5,"d":1.5,"b":false}""")

    e.getOptionalString("s") shouldBe Right(Some("v"))
    e.getOptionalInt("i") shouldBe Right(Some(5))
    e.getOptionalDouble("d") shouldBe Right(Some(1.5))
    e.getOptionalBoolean("b") shouldBe Right(Some(false))
  }

  it should "return None, not an error, for a missing key of every type" in {
    val e = extractor("""{}""")

    e.getOptionalString("s") shouldBe Right(None)
    e.getOptionalInt("i") shouldBe Right(None)
    e.getOptionalDouble("d") shouldBe Right(None)
    e.getOptionalBoolean("b") shouldBe Right(None)
  }

  it should "return Some for an empty string, which is a value" in {
    extractor("""{"s":""}""").getOptionalString("s") shouldBe Right(Some(""))
  }

  it should "return an error for a wrong type, even though the parameter is optional" in {
    extractor("""{"i":"5"}""").getOptionalInt("i") shouldBe Left(TypeMismatch("i", "integer", "string"))
    extractor("""{"d":"1.5"}""").getOptionalDouble("d") shouldBe Left(TypeMismatch("d", "number", "string"))
    extractor("""{"b":"true"}""").getOptionalBoolean("b") shouldBe Left(TypeMismatch("b", "boolean", "string"))
    extractor("""{"s":1}""").getOptionalString("s") shouldBe Left(TypeMismatch("s", "string", "number"))
  }

  it should "treat null as absent for every type" in {
    val e = extractor("""{"s":null,"i":null,"d":null,"b":null}""")

    e.getOptionalString("s") shouldBe Right(None)
    e.getOptionalInt("i") shouldBe Right(None)
    e.getOptionalDouble("d") shouldBe Right(None)
    e.getOptionalBoolean("b") shouldBe Right(None)
  }

  // ---- nested access

  "nested access" should "read a value by dotted path" in {
    extractor("""{"config":{"host":"localhost","port":8080}}""").getString("config.host") shouldBe Right("localhost")
    extractor("""{"config":{"host":"localhost","port":8080}}""").getInt("config.port") shouldBe Right(8080)
  }

  it should "chain getObject and getString" in {
    val e = extractor("""{"config":{"host":"localhost"}}""")

    val host = e.getObject("config").flatMap(obj => SafeParameterExtractor(obj).getString("host"))

    host shouldBe Right("localhost")
  }

  it should "put the full path in the message when a nested key is missing, and list that object's keys" in {
    val message =
      extractor("""{"config":{"host":"h"}}""").getString("config.port").left.getOrElse(fail("expected Left"))

    message should include("'config.port'")
    message should include("host")
  }

  it should "put the path of the missing intermediate object in the error" in {
    extractor("""{"a":{}}""").getStringEnhanced("a.b.c") shouldBe
      Left(MissingParameter("a.b", "object", Nil))
  }

  it should "report which parent is not an object when the path goes through a scalar" in {
    val error = extractor("""{"config":"text"}""").getStringEnhanced("config.host")

    error shouldBe Left(InvalidNesting("host", "config", "string"))
    val message = error.left.map(_.getMessage).left.getOrElse(fail("expected Left"))
    message should include("'host'")
    message should include("'config'")
    message should include("string")
  }

  it should "report a null parent on the way down" in {
    extractor("""{"config":null}""").getStringEnhanced("config.host") shouldBe
      Left(InvalidNesting("host", "config", "null"))
  }

  it should "report an array parent on the way down" in {
    extractor("""{"items":[1,2]}""").getStringEnhanced("items.first") shouldBe
      Left(InvalidNesting("first", "items", "array"))
  }

  it should "report a type mismatch when a getObject target is a scalar" in {
    extractor("""{"config":5}""").getObjectEnhanced("config") shouldBe
      Left(TypeMismatch("config", "object", "number"))
  }

  // ---- unknown keys

  "extraction" should "ignore keys it was not asked for" in {
    extractor("""{"wanted":"yes","unexpected":{"deep":[1,2,3]}}""").getString("wanted") shouldBe Right("yes")
  }

  it should "treat keys as case-sensitive" in {
    extractor("""{"Name":"x"}""").getStringEnhanced("name") shouldBe
      Left(MissingParameter("name", "string", List("Name")))
  }

  it should "list every available key when one is missing" in {
    val available = extractor("""{"zeta":1,"alpha":2,"mid":3}""").getStringEnhanced("nope") match {
      case Left(MissingParameter("nope", "string", keys)) => keys
      case other                                          => fail(s"expected MissingParameter, got $other")
    }

    available should contain theSameElementsAs Seq("alpha", "mid", "zeta")
  }

  // ---- validateRequired

  "validateRequired" should "report a null parameter and a missing one together, in the order asked" in {
    val errors = extractor("""{"a":null,"b":"x"}""")
      .validateRequired("a" -> "string", "c" -> "integer")
      .left
      .getOrElse(fail("expected Left"))

    errors.map {
      case NullParameter(path, _)       => s"null:$path"
      case MissingParameter(path, _, _) => s"missing:$path"
      case other                        => fail(s"unexpected error $other")
    } shouldBe List("null:a", "missing:c")
  }

  it should "accept parameters that are present" in {
    extractor("""{"a":"x","c":3}""").validateRequired("a" -> "string", "c" -> "integer") shouldBe Right(())
  }

  // KNOWN BUG, not fixed here: validateRequired passes `_ => Some(())` as the extractor, so it checks that a
  // parameter is present and not null but never its type, although its Scaladoc promises "have the correct
  // types". `validateRequired("age" -> "integer")` on {"age":"x"} returns Right(()). When it is fixed this test
  // fails with "marked pendingUntilFixed but passed": remove the wrapper then.
  it should "report a required parameter of the wrong type, as its Scaladoc promises" in {
    pendingUntilFixed {
      extractor("""{"age":"x"}""").validateRequired("age" -> "integer").isLeft shouldBe true
    }
  }
}
