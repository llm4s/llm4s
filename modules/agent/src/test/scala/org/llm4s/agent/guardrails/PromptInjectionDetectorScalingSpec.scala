package org.llm4s.agent.guardrails

import org.llm4s.agent.guardrails.builtin.{ InjectionPattern, PromptInjectionDetector }
import org.llm4s.testutil.{ LinearTime, PerformanceTest }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * `PromptInjectionDetector` runs at admission, on the caller's thread and before any run budget applies, on text the
 * caller supplies, so no default pattern may take super-linear time on a long adversarial input (#3233: `"tell making "`
 * 1000 times took 38 s on `how_to_dangerous`, `"ignore"` and 40k spaces 12.5 s on `ignore_instructions`).
 *
 * Scaling, not absolute time, through [[org.llm4s.testutil.LinearTime]]: a scan of 4n characters may cost at most 8
 * times a scan of n.
 */
class PromptInjectionDetectorScalingSpec extends AnyFlatSpec with Matchers {

  // Large enough that a small sample is milliseconds of matching per run, well above timer and JIT noise: at 20k
  // characters a linear pattern (no_restrictions on a run of `guardrails`, 50 ns a character from 20k to 320k)
  // read 9.0x once on CI.
  private val n = 80000

  private def run(prefix: String, unit: String, length: Int): String = prefix + unit * (length / unit.length)

  // Every word of a pattern, repeated with and without a separator, and a run of whitespace after it: what makes a
  // gap or a whitespace quantifier start over at every position.
  private def wordsOf(pattern: InjectionPattern): Seq[String] =
    "[a-z0-9]{2,}".r.findAllIn(pattern.regex.regex).toSeq.distinct

  private def adversarial(pattern: InjectionPattern): Seq[(String, String)] = {
    val words = wordsOf(pattern)
    val pairs = words.zip(words.drop(1)).map { case (a, b) => ("", s"$a $b ") }
    words.flatMap(w => Seq(("", w), ("", s"$w "), (w, " "), (w, "x"))) ++ pairs ++ Seq(
      ("", "tell making "),
      ("", "write how to "),
      ("", "write a story about "),
      ("ignore", " "),
      ("", " "),
      ("'", " "),
      ("", "<script"),
      ("", "on"),
      ("", "; "),
      ("", "--  ")
    )
  }

  InjectionPattern.default.foreach { pattern =>
    "PromptInjectionDetector" should s"match ${pattern.name} in time linear in the input (#3233)" taggedAs PerformanceTest in {
      val detector = new PromptInjectionDetector(Seq(pattern))
      adversarial(pattern).distinct.foreach { case (prefix, unit) =>
        val label = s"${pattern.name} on '$prefix' + a run of '$unit', n = $n"
        LinearTime.assertLinear(label, run(prefix, unit, n), run(prefix, unit, 4 * n))(detector.validate)
      }
    }
  }

  // The scaling tests above are PerformanceTest, run by `sbt testPerformance`; this one stays in `sbt test` and fails
  // on a catastrophic regression of the shapes #3233 measured, in one run under a generous hang guard.
  "PromptInjectionDetector" should "scan a megabyte of the #3233 shapes with every default pattern well inside the hang guard" in {
    val detector = PromptInjectionDetector()
    val inputs = Seq(
      run("", "tell making ", 1000000),
      run("ignore", " ", 1000000),
      run("", "write a story about ", 1000000),
      run("", "'" + " " * 30, 1000000),
      run("", "<script", 1000000),
      run("", "on", 1000000)
    )
    LinearTime.timed("a megabyte of each #3233 shape", LinearTime.MegabyteHangGuard) {
      inputs.map(detector.validate)
    }
  }
}
