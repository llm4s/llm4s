package org.llm4s.agent.guardrails.builtin

import org.llm4s.error.ValidationError
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.concurrent.duration._

/**
 * What `SimpleValidatorSpec` does not pin for [[ProfanityFilter]]: very large inputs, the shape of the error it
 * returns, and words that merely contain a listed word.
 *
 * The default list is deliberately tiny (`badword`, `inappropriate`), and matching is on whole whitespace-separated
 * tokens, so obfuscated spellings such as `f.u.c.k` are not detectable by this class; that is a design decision and
 * is not asserted here.
 */
class ProfanityFilterSpec extends AnyFlatSpec with Matchers {

  /** A generous bound, only there so that a pathological (quadratic) scan fails the test instead of hanging the build. */
  private val Bound = 60.seconds

  private def withinBound[A](body: => A): A = {
    given ExecutionContext = ExecutionContext.global
    Await.result(Future(body), Bound)
  }

  // A document of about 1.4 MB: 200,000 clean words.
  private lazy val largeClean: String = Seq.fill(200000)("lorem").mkString(" ")

  "ProfanityFilter on a very large input" should "pass clean text through untouched" in {
    val result = withinBound(new ProfanityFilter().validate(largeClean))

    // The very same string comes back, not a copy that was trimmed or rewritten.
    result.toOption.exists(_ eq largeClean) shouldBe true
  }

  it should "still find a bad word at the very end" in {
    withinBound(new ProfanityFilter().validate(largeClean + " badword")).isLeft shouldBe true
  }

  it should "still find a bad word in the middle" in {
    val half = Seq.fill(100000)("lorem").mkString(" ")
    withinBound(new ProfanityFilter().validate(half + " badword " + half)).isLeft shouldBe true
  }

  it should "handle a single token of a million characters" in {
    val token = "a" * 1000000
    withinBound(new ProfanityFilter().validate(token).isRight) shouldBe true
  }

  it should "find a bad word after a million characters of whitespace" in {
    val padded = " " * 1000000 + "badword"
    withinBound(new ProfanityFilter().validate(padded)).isLeft shouldBe true
  }

  it should "find a custom word in a large input in case-sensitive mode too" in {
    val filter = ProfanityFilter.caseSensitive(Set("Forbidden"))
    withinBound(filter.validate(largeClean + " Forbidden")).isLeft shouldBe true
    withinBound(filter.validate(largeClean + " forbidden")).isRight shouldBe true
  }

  "ProfanityFilter's error" should "be a ValidationError about the input" in {
    new ProfanityFilter().validate("this is badword text") match {
      case Left(e: ValidationError) => e.field shouldBe "input"
      case other                    => fail(s"expected Left(ValidationError), got $other")
    }
  }

  it should "be the same for every kind of match, so it says nothing about which word matched" in {
    val filter    = ProfanityFilter.withCustomWords(Set("hell"))
    val byDefault = filter.validate("a badword here").left.toOption
    val byCustom  = filter.validate("go to hell now").left.toOption

    byDefault shouldBe defined
    byCustom shouldBe defined
    byDefault.map(_.message) shouldBe byCustom.map(_.message)
  }

  "ProfanityFilter on ordinary business and technical text" should "not flag words that contain a listed word" in {
    // Stand-in list: `hell` is listed, `hello`, `shell` and `Hellenic` merely contain it.
    val filter = ProfanityFilter.withCustomWords(Set("hell"))
    val email  = "Hello team, please open a shell and run the Hellenic office report before the Scunthorpe meeting."

    filter.validate(email) shouldBe Right(email)
    filter.validate("go to hell now").isLeft shouldBe true
  }

  it should "pass a technical query containing code and punctuation" in {
    val query = "SELECT name, email FROM users WHERE id = 42; -- why does a NullPointerException occur at Foo.scala:17?"
    new ProfanityFilter().validate(query) shouldBe Right(query)
  }
}
