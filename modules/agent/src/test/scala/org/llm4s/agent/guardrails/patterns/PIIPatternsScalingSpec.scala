package org.llm4s.agent.guardrails.patterns

import org.llm4s.agent.guardrails.patterns.PIIPatterns.PIIType
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.Try
import scala.util.matching.Regex

/**
 * The PII patterns run in every default `PIIMasker` and `PIIDetector`, on text a model or a user supplies, so none may
 * take super-linear time on a long adversarial run (#1713: the Email pattern took 10.9 s on 20,000 `a`s).
 *
 * Absolute times fail on a slow or loaded runner, so this checks scaling instead: after a warm-up, the time on a run
 * of 4n characters must be at most 8 times the time on n characters, as the median of several samples. Linear time
 * gives about 4, quadratic about 16. A run that takes longer than [[PIIPatternsScalingSpec.HangGuardNanos]] fails at once.
 */
class PIIPatternsScalingSpec extends AnyFlatSpec with Matchers {
  import PIIPatternsScalingSpec._

  private val n       = 5000
  private val samples = 5

  private val adversarialUnits = Seq("a", "7", "a.", "a@")

  private def run(unit: String, length: Int): String = unit * (length / unit.length)

  /** Matches over `text` once, failing with a timeout if it runs past the hang guard. */
  private def countMatches(pattern: Regex, text: String): Int =
    pattern.findAllMatchIn(new GuardedText(text, System.nanoTime() + HangGuardNanos)).size

  private def timeNanos(pattern: Regex, text: String, passes: Int): Long = {
    val start = System.nanoTime()
    (1 to passes).foreach(_ => countMatches(pattern, text))
    System.nanoTime() - start
  }

  /**
   * The median of `time(4n) / time(n)`. Each sample times both sizes back to back, with enough passes over the
   * size-n text to take at least `MinSampleNanos`, so a fast pattern is timed well above timer noise.
   */
  private def scalingRatio(pattern: Regex, unit: String): Double = {
    val small     = run(unit, n)
    val large     = run(unit, 4 * n)
    val warmUntil = System.nanoTime() + WarmUpNanos
    while ({ countMatches(pattern, small); countMatches(pattern, large); System.nanoTime() < warmUntil }) ()
    val once   = math.max(1L, timeNanos(pattern, small, 1))
    val passes = math.max(1L, math.min(100000L, MinSampleNanos / once)).toInt
    val ratios = (1 to samples).map { _ =>
      val smallNanos = timeNanos(pattern, small, passes)
      val largeNanos = timeNanos(pattern, large, passes)
      largeNanos.toDouble / math.max(1L, smallNanos).toDouble
    }
    ratios.sorted.apply(samples / 2)
  }

  private def checkScaling(piiType: PIIType): Unit =
    adversarialUnits.foreach { unit =>
      val ratio = Try(scalingRatio(piiType.pattern, unit)).fold(
        e => fail(s"${piiType.name} on a run of '$unit': ${e.getMessage}"),
        identity
      )
      withClue(s"${piiType.name} on a run of '$unit', time(4n) / time(n) with n = $n: ") {
        ratio should be <= 8.0
      }
    }

  (PIIType.all :+ PIIType.BankAccount).foreach { piiType =>
    s"The ${piiType.name} pattern" should "take linear time on long runs of letters, digits, a. and a@" in {
      checkScaling(piiType)
    }
  }

  "The Email pattern" should "scan a run of a million letters or digits in well under the hang guard" in {
    adversarialUnits.foreach { unit =>
      val text = run(unit, 1000000)
      Try(countMatches(PIIType.Email.pattern, text)).fold(
        e => fail(s"Email on a run of a million '$unit': ${e.getMessage}"),
        _ => succeed
      )
    }
  }
}

object PIIPatternsScalingSpec {

  /** A single pass over one input that takes longer than this is treated as a hang. */
  val HangGuardNanos: Long = 60L * 1000 * 1000 * 1000

  val MinSampleNanos: Long = 50L * 1000 * 1000

  val WarmUpNanos: Long = 500L * 1000 * 1000

  /** A view of a string whose reads fail once a deadline has passed, so a runaway match ends with an error. */
  final class GuardedText(text: String, deadline: Long) extends CharSequence {
    def length: Int = text.length
    def charAt(index: Int): Char = {
      if ((index & 0x3ff) == 0 && System.nanoTime() > deadline)
        throw new IllegalStateException(s"no result within the ${HangGuardNanos / 1000000000L} s hang guard")
      text.charAt(index)
    }
    def subSequence(start: Int, end: Int): CharSequence = text.subSequence(start, end)
    override def toString: String = text
  }
}
