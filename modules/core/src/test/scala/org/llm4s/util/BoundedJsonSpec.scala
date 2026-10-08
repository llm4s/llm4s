package org.llm4s.util

import org.llm4s.error.ValidationError
import org.llm4s.testutil.SmallStack
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * `BoundedJson.read` is the one way model-produced JSON is parsed. These tests pin the limit, what
 * the depth scan counts, and that a document far beyond the limit is refused without being parsed.
 * The deep cases run on a 1 MB stack so an overflow would be reported as a `Left(Error)`, not abort
 * the suite, and the outcome does not depend on the JVM's default stack size.
 */
final class BoundedJsonSpec extends AnyFlatSpec with Matchers {

  private def arrays(depth: Int): String = "[" * depth + "]" * depth

  "BoundedJson.read" should "parse ordinary JSON" in {
    BoundedJson.read("""{"a":[1,2,{"b":null}]}""") shouldBe Right(ujson.read("""{"a":[1,2,{"b":null}]}"""))
  }

  it should "report malformed JSON as a ValidationError on `json` carrying the parser's message" in {
    BoundedJson.read("{not json") match {
      case Left(e: ValidationError) =>
        e.field shouldBe "json"
        e.message should startWith("Invalid json: ")
        e.message.length should be > "Invalid json: ".length
      case other => fail(s"expected a ValidationError, got $other")
    }
  }

  it should "not mistake a parse error at index 429 or 401 for a rate limit or an authentication failure" in {
    // `DefaultErrorMapper` classifies any exception whose message contains "429" or "401", and the
    // parser's exception names the JSON path it stopped at (`$[429]`) - so a reply that goes wrong at
    // that element used to come back as a RateLimitError or an AuthenticationError.
    Seq(429, 401).foreach { index =>
      val malformed = "[" + "0," * index + "x]"
      BoundedJson.read(malformed) match {
        case Left(e: ValidationError) =>
          e.field shouldBe "json"
          e.message should include(s"$$[$index]")
        case other => fail(s"expected a ValidationError for a parse error at element $index, got $other")
      }
    }
  }

  it should "accept a document nested exactly 512 levels and refuse 513" in {
    BoundedJson.MaxDepth shouldBe 512
    BoundedJson.read(arrays(512)) shouldBe Right(ujson.read(arrays(512)))
    BoundedJson.read(arrays(513)) shouldBe Left(ValidationError("json", "JSON is nested more than 512 levels deep"))
  }

  it should "count arrays and objects together" in {
    val atLimit   = "[" * 256 + "{\"a\":" * 256 + "1" + "}" * 256 + "]" * 256
    val overLimit = "[" * 257 + "{\"a\":" * 256 + "1" + "}" * 256 + "]" * 257
    BoundedJson.read(atLimit).isRight shouldBe true
    BoundedJson.read(overLimit) shouldBe Left(BoundedJson.tooDeep())
  }

  it should "not count brackets inside string literals, including after an escaped quote" in {
    BoundedJson.read("[\"" + "[" * 1000 + "\"]") shouldBe Right(ujson.Arr("[" * 1000))
    BoundedJson.read("[\"a\\\"" + "[" * 1000 + "\"]") shouldBe Right(ujson.Arr("a\"" + "[" * 1000))
    // a string that ends at the quote following an escaped backslash
    BoundedJson.read("[\"\\\\\"," + "[" * 1000 + "]") shouldBe Left(BoundedJson.tooDeep())
  }

  it should "take a smaller limit" in {
    BoundedJson.read(arrays(3), maxDepth = 3).isRight shouldBe true
    BoundedJson.read(arrays(4), maxDepth = 3) shouldBe Left(BoundedJson.tooDeep(3))
  }

  it should "refuse a document far beyond the limit without parsing it, on a 1 MB stack" in {
    Seq("[" * 1000000, arrays(100000), "{\"a\":" * 100000).foreach { deep =>
      SmallStack.run(BoundedJson.read(deep)) shouldBe Right(Left(BoundedJson.tooDeep()))
    }
  }

  it should "refuse unbalanced text past the limit as too deep rather than malformed" in {
    // the scan refuses it before the parser could report the missing brackets
    BoundedJson.read("[" * 513) shouldBe Left(BoundedJson.tooDeep())
    // short of the limit, the parser reports the malformed text
    BoundedJson.read("[" * 3).left.map(_.message.contains("levels deep")) shouldBe Left(false)
  }

  "BoundedJson.exceedsDepth" should "answer by the same scan" in {
    BoundedJson.exceedsDepth(arrays(512)) shouldBe false
    BoundedJson.exceedsDepth(arrays(513)) shouldBe true
    BoundedJson.exceedsDepth("[\"" + "[" * 1000 + "\"]") shouldBe false
    BoundedJson.exceedsDepth(arrays(2), maxDepth = 1) shouldBe true
  }
}
