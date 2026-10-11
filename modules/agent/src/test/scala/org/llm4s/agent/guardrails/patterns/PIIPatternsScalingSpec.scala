package org.llm4s.agent.guardrails.patterns

import org.llm4s.agent.guardrails.patterns.PIIPatterns.PIIType
import org.llm4s.testutil.LinearTime
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.*
import scala.util.Try
import scala.util.matching.Regex

/**
 * The PII patterns run in every default `PIIMasker` and `PIIDetector`, on text a model or a user supplies, so none may
 * take super-linear time on a long adversarial run (#1713: the Email pattern took 10.9 s on 20,000 `a`s).
 *
 * Absolute times fail on a slow or loaded runner, so this checks scaling instead, through
 * [[org.llm4s.testutil.LinearTime]]: the CPU time of a scan of 4n characters must be at most 8 times that of a scan
 * of n characters, each the cheapest of several samples calibrated well above the clock's granularity (#1743). Linear
 * time gives about 4, quadratic about 16. A single scan that runs past [[PIIPatternsScalingSpec.HangGuard]] fails at
 * once, mid-match.
 */
class PIIPatternsScalingSpec extends AnyFlatSpec with Matchers {
  import PIIPatternsScalingSpec._

  private val n = 5000

  private val adversarialUnits = Seq("a", "7", "a.", "a@")

  private def run(unit: String, length: Int): String = unit * (length / unit.length)

  /** Matches over `text` once, failing with a timeout if it runs past the hang guard. */
  private def countMatches(pattern: Regex, text: String): Int =
    pattern.findAllMatchIn(new GuardedText(text, System.nanoTime() + HangGuard.toNanos)).size

  private def checkScaling(piiType: PIIType): Unit =
    adversarialUnits.foreach { unit =>
      val label = s"${piiType.name} on a run of '$unit', n = $n"
      val scaling = Try(
        LinearTime.assertLinear(label, run(unit, n), run(unit, 4 * n), hangGuard = HangGuard)(
          countMatches(piiType.pattern, _)
        )
      ).fold(
        {
          case e: IllegalStateException => fail(s"$label: ${e.getMessage}")
          case e                        => throw e
        },
        identity
      )
      info(s"run of '$unit': $scaling")
    }

  (PIIType.all :+ PIIType.BankAccount).foreach { piiType =>
    s"The ${piiType.name} pattern" should "take linear time on long runs of letters, digits, a. and a@" in {
      checkScaling(piiType)
    }
  }

  "The Email pattern" should "scan a run of a million letters or digits in well under the hang guard" in {
    adversarialUnits.foreach { unit =>
      val text = run(unit, 1000000)
      Try(
        LinearTime.timed(s"Email on a run of a million '$unit'", HangGuard)(countMatches(PIIType.Email.pattern, text))
      )
        .fold(
          {
            case e: IllegalStateException => fail(s"Email on a run of a million '$unit': ${e.getMessage}")
            case e                        => throw e
          },
          _ => succeed
        )
    }
  }
}

object PIIPatternsScalingSpec {

  /** A single scan of one input that takes longer than this, in wall time, is treated as a hang. */
  val HangGuard: FiniteDuration = 60.seconds

  /** A view of a string whose reads fail once a deadline has passed, so a runaway match ends with an error. */
  final class GuardedText(text: String, deadline: Long) extends CharSequence {
    def length: Int = text.length
    def charAt(index: Int): Char = {
      if ((index & 0x3ff) == 0 && System.nanoTime() > deadline)
        throw new IllegalStateException(s"no result within the ${HangGuard.toSeconds} s hang guard")
      text.charAt(index)
    }
    def subSequence(start: Int, end: Int): CharSequence = text.subSequence(start, end)
    override def toString: String                       = text
  }
}
