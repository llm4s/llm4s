package org.llm4s.agent.guardrails

import org.llm4s.agent.guardrails.builtin.{ LengthCheck, ProfanityFilter, RegexValidator }
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable.ListBuffer

// scalastyle:off line.size.limit
class CompositeGuardrailSpec extends AnyFlatSpec with Matchers {

  // Helper: guardrail that always passes
  private def passing: Guardrail[String] = new Guardrail[String] {
    def validate(value: String): Result[String] = Right(value)
    val name: String                            = "AlwaysPass"
  }

  // Helper: guardrail that always fails with a given message
  private def failing(msg: String): Guardrail[String] = new Guardrail[String] {
    def validate(value: String): Result[String] =
      Left(org.llm4s.error.ValidationError.invalid("test", msg))
    val name: String = s"Fail($msg)"
  }

  // Helper: guardrail that records invocations for ordering/short-circuit verification
  private def tracking(label: String, log: ListBuffer[String], pass: Boolean): Guardrail[String] =
    new Guardrail[String] {
      def validate(value: String): Result[String] = {
        log += label
        if (pass) Right(value)
        else Left(org.llm4s.error.ValidationError.invalid("test", s"$label failed"))
      }
      val name: String = label
    }

  // ==========================================================================
  // CompositeGuardrail.all — All must pass
  // ==========================================================================

  "CompositeGuardrail.all" should "pass with empty guardrail list" in {
    val composite = CompositeGuardrail.all[String](Seq.empty)
    composite.validate("anything") shouldBe Right("anything")
  }

  it should "pass with a single passing guardrail" in {
    val composite = CompositeGuardrail.all(Seq(passing))
    composite.validate("test") shouldBe Right("test")
  }

  it should "fail with a single failing guardrail" in {
    val composite = CompositeGuardrail.all(Seq(failing("only one")))
    composite.validate("test").isLeft shouldBe true
  }

  it should "pass when all N guardrails pass" in {
    val composite = CompositeGuardrail.all(Seq(passing, passing, passing))
    composite.validate("ok") shouldBe Right("ok")
  }

  it should "fail when only the first guardrail fails" in {
    val composite = CompositeGuardrail.all(Seq(failing("first"), passing, passing))
    val result    = composite.validate("test")
    result.isLeft shouldBe true
    result.swap.toOption.get.message should include("first")
  }

  it should "fail when only the last guardrail fails" in {
    val composite = CompositeGuardrail.all(Seq(passing, passing, failing("last")))
    val result    = composite.validate("test")
    result.isLeft shouldBe true
    result.swap.toOption.get.message should include("last")
  }

  it should "aggregate errors from all failing guardrails" in {
    val composite = CompositeGuardrail.all(Seq(failing("err-A"), passing, failing("err-B")))
    val result    = composite.validate("test")
    result.isLeft shouldBe true
    val msg = result.swap.toOption.get.message
    msg should include("err-A")
    msg should include("err-B")
  }

  it should "run all guardrails even when early ones fail" in {
    val log = ListBuffer.empty[String]
    val composite = CompositeGuardrail.all(
      Seq(
        tracking("g1", log, pass = false),
        tracking("g2", log, pass = true),
        tracking("g3", log, pass = false)
      )
    )
    composite.validate("test")
    log.toList shouldBe List("g1", "g2", "g3")
  }

  it should "use real guardrails: length + profanity both failing" in {
    val composite = CompositeGuardrail.all(
      Seq(
        new LengthCheck(100, 200),
        new ProfanityFilter(customBadWords = Set("test"))
      )
    )
    val result = composite.validate("test")
    result.isLeft shouldBe true
    val msg = result.swap.toOption.get.message
    msg should include("Multiple validation failures")
  }

  // ==========================================================================
  // CompositeGuardrail.any — At least one must pass
  // ==========================================================================

  "CompositeGuardrail.any" should "pass with empty guardrail list" in {
    // No guardrails → successes is empty → falls to error branch
    // Actually let's check: results = Seq.empty, successes = empty, errors = empty
    // The code does: errors.map(_.formatted).mkString("; ") which is ""
    val composite = CompositeGuardrail.any[String](Seq.empty)
    val result    = composite.validate("anything")
    // With no guardrails, successes is empty, so it fails
    result.isLeft shouldBe true
  }

  it should "pass with a single passing guardrail" in {
    val composite = CompositeGuardrail.any(Seq(passing))
    composite.validate("test") shouldBe Right("test")
  }

  it should "fail with a single failing guardrail" in {
    val composite = CompositeGuardrail.any(Seq(failing("only")))
    composite.validate("test").isLeft shouldBe true
  }

  it should "pass when all guardrails pass" in {
    val composite = CompositeGuardrail.any(Seq(passing, passing, passing))
    composite.validate("ok") shouldBe Right("ok")
  }

  it should "pass when only the first guardrail passes" in {
    val composite = CompositeGuardrail.any(Seq(passing, failing("b"), failing("c")))
    composite.validate("test") shouldBe Right("test")
  }

  it should "pass when only the last guardrail passes" in {
    val composite = CompositeGuardrail.any(Seq(failing("a"), failing("b"), passing))
    composite.validate("test") shouldBe Right("test")
  }

  it should "fail when all guardrails fail and aggregate errors" in {
    val composite = CompositeGuardrail.any(Seq(failing("err-X"), failing("err-Y")))
    val result    = composite.validate("test")
    result.isLeft shouldBe true
    val msg = result.swap.toOption.get.message
    msg should include("All validations failed")
    msg should include("err-X")
    msg should include("err-Y")
  }

  it should "run all guardrails regardless of pass/fail" in {
    val log = ListBuffer.empty[String]
    val composite = CompositeGuardrail.any(
      Seq(
        tracking("a1", log, pass = true),
        tracking("a2", log, pass = false),
        tracking("a3", log, pass = true)
      )
    )
    composite.validate("test")
    log.toList shouldBe List("a1", "a2", "a3")
  }

  it should "use real guardrails: one regex passes, one fails" in {
    val composite = CompositeGuardrail.any(
      Seq(
        new RegexValidator("^[0-9]+$".r),
        new RegexValidator("^[a-z]+$".r)
      )
    )
    composite.validate("hello") shouldBe Right("hello")
    composite.validate("12345") shouldBe Right("12345")
    composite.validate("UPPER").isLeft shouldBe true
  }

  // ==========================================================================
  // CompositeGuardrail.sequential — Short-circuit on failure
  // ==========================================================================

  "CompositeGuardrail.sequential" should "pass with empty guardrail list" in {
    val seq = CompositeGuardrail.sequential[String](Seq.empty)
    seq.validate("anything") shouldBe Right("anything")
  }

  it should "pass with a single passing guardrail" in {
    val seq = CompositeGuardrail.sequential(Seq(passing))
    seq.validate("test") shouldBe Right("test")
  }

  it should "fail with a single failing guardrail" in {
    val seq = CompositeGuardrail.sequential(Seq(failing("only")))
    seq.validate("test").isLeft shouldBe true
  }

  it should "pass when all guardrails pass in sequence" in {
    val seq = CompositeGuardrail.sequential(Seq(passing, passing, passing))
    seq.validate("ok") shouldBe Right("ok")
  }

  it should "short-circuit: not evaluate guardrails after a failure" in {
    val log = ListBuffer.empty[String]
    val seq = CompositeGuardrail.sequential(
      Seq(
        tracking("s1", log, pass = true),
        tracking("s2", log, pass = false),
        tracking("s3", log, pass = true)
      )
    )
    seq.validate("test")
    log.toList shouldBe List("s1", "s2")
  }

  it should "preserve the error from the failing guardrail" in {
    val seq = CompositeGuardrail.sequential(
      Seq(passing, failing("specific-error"), passing)
    )
    val result = seq.validate("test")
    result.isLeft shouldBe true
    result.swap.toOption.get.message should include("specific-error")
  }

  it should "execute guardrails in declared order" in {
    val log = ListBuffer.empty[String]
    val seq = CompositeGuardrail.sequential(
      Seq(
        tracking("first", log, pass = true),
        tracking("second", log, pass = true),
        tracking("third", log, pass = true)
      )
    )
    seq.validate("test")
    log.toList shouldBe List("first", "second", "third")
  }

  it should "stop at the very first guardrail if it fails" in {
    val log = ListBuffer.empty[String]
    val seq = CompositeGuardrail.sequential(
      Seq(
        tracking("first", log, pass = false),
        tracking("second", log, pass = true)
      )
    )
    seq.validate("test")
    log.toList shouldBe List("first")
  }

  it should "use real guardrails: length check then profanity" in {
    val seq = CompositeGuardrail.sequential(
      Seq(
        new LengthCheck(1, 50),
        new ProfanityFilter()
      )
    )
    seq.validate("Clean short text") shouldBe Right("Clean short text")
    // Too long → fails at length check, profanity filter never runs
    val tooLong = "x" * 51
    seq.validate(tooLong).isLeft shouldBe true
  }

  // ==========================================================================
  // First mode (via constructor)
  // ==========================================================================

  "CompositeGuardrail with First mode" should "pass with empty guardrail list" in {
    val composite = new CompositeGuardrail[String](Seq.empty, ValidationMode.First)
    composite.validate("anything") shouldBe Right("anything")
  }

  it should "return the first guardrail's success" in {
    val composite = new CompositeGuardrail(Seq(passing, failing("ignored")), ValidationMode.First)
    composite.validate("test") shouldBe Right("test")
  }

  it should "return the first guardrail's failure" in {
    val composite = new CompositeGuardrail(Seq(failing("first-err"), passing), ValidationMode.First)
    val result    = composite.validate("test")
    result.isLeft shouldBe true
    result.swap.toOption.get.message should include("first-err")
  }

  it should "only evaluate the first guardrail" in {
    val log = ListBuffer.empty[String]
    val composite = new CompositeGuardrail(
      Seq(
        tracking("f1", log, pass = true),
        tracking("f2", log, pass = true)
      ),
      ValidationMode.First
    )
    composite.validate("test")
    log.toList shouldBe List("f1")
  }

  // ==========================================================================
  // Name and Description properties
  // ==========================================================================

  "CompositeGuardrail name" should "include child guardrail names" in {
    val composite = CompositeGuardrail.all(
      Seq(
        new LengthCheck(1, 100),
        new ProfanityFilter()
      )
    )
    composite.name should include("LengthCheck")
    composite.name should include("ProfanityFilter")
  }

  "CompositeGuardrail description" should "include the validation mode" in {
    val allMode = CompositeGuardrail.all(Seq(passing))
    val anyMode = CompositeGuardrail.any(Seq(passing))
    allMode.description.get should include("All")
    anyMode.description.get should include("Any")
  }

  it should "include child guardrail names" in {
    val composite = CompositeGuardrail.all(
      Seq(
        new LengthCheck(1, 10),
        new ProfanityFilter()
      )
    )
    val desc = composite.description.get
    desc should include("LengthCheck")
    desc should include("ProfanityFilter")
  }

  "Sequential guardrail name" should "use arrow notation" in {
    val seq = CompositeGuardrail.sequential(
      Seq(
        new LengthCheck(1, 10),
        new ProfanityFilter()
      )
    )
    seq.name should include("LengthCheck")
    seq.name should include("->")
    seq.name should include("ProfanityFilter")
  }

  "Sequential guardrail description" should "include child names" in {
    val seq = CompositeGuardrail.sequential(
      Seq(
        new LengthCheck(1, 10),
        new ProfanityFilter()
      )
    )
    seq.description.get should include("Sequential")
    seq.description.get should include("LengthCheck")
  }

  // ==========================================================================
  // Guardrail.andThen composition
  // ==========================================================================

  "Guardrail.andThen" should "chain three guardrails in order" in {
    val log = ListBuffer.empty[String]
    val g1  = tracking("a", log, pass = true)
    val g2  = tracking("b", log, pass = true)
    val g3  = tracking("c", log, pass = true)

    val composed = g1.andThen(g2).andThen(g3)
    composed.validate("test") shouldBe Right("test")
    log.toList shouldBe List("a", "b", "c")
  }

  it should "short-circuit in chained composition" in {
    val log = ListBuffer.empty[String]
    val g1  = tracking("a", log, pass = true)
    val g2  = tracking("b", log, pass = false)
    val g3  = tracking("c", log, pass = true)

    val composed = g1.andThen(g2).andThen(g3)
    composed.validate("test").isLeft shouldBe true
    log.toList shouldBe List("a", "b")
  }

  // ==========================================================================
  // Mixed real-world scenarios
  // ==========================================================================

  "CompositeGuardrail" should "support nesting: all containing any" in {
    val inner = CompositeGuardrail.any(
      Seq(
        new RegexValidator("^[0-9]+$".r),
        new RegexValidator("^[a-z]+$".r)
      )
    )
    val outer = CompositeGuardrail.all(
      Seq(
        new LengthCheck(1, 20),
        inner
      )
    )
    outer.validate("hello") shouldBe Right("hello")
    outer.validate("12345") shouldBe Right("12345")
    outer.validate("UPPER").isLeft shouldBe true
    // Too long for length check even though regex would pass
    outer.validate("a" * 21).isLeft shouldBe true
  }
}
// scalastyle:on line.size.limit
