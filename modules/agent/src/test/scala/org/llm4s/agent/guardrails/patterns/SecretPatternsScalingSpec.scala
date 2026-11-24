package org.llm4s.agent.guardrails.patterns

import org.llm4s.testutil.{ LinearTime, PerformanceTest }
import org.llm4s.agent.guardrails.patterns.SecretPatterns.SecretType
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * The secret patterns run on text a model or a user supplies (`SecretLeakGuardrail`) and on every string
 * `Redaction` redacts, so none may take super-linear time on a long adversarial run (#2824: the JWT pattern took
 * 4.7 s on 60 KB of `eyJ` and 75 s on 240 KB).
 *
 * Scaling, not absolute time, through [[org.llm4s.testutil.LinearTime]]: a scan of 4n characters may cost at most 8
 * times a scan of n. Linear time gives about 4, quadratic about 16.
 */
class SecretPatternsScalingSpec extends AnyFlatSpec with Matchers {

  private val n = 20000

  /**
   * Runs that make a pattern start over and over: every pattern's own prefix, each part of a JWT and of a PEM block
   * without the part that follows it, and the separators the patterns stop at.
   */
  private val adversarialUnits = Seq(
    "eyJ",
    "eyJa.",
    "eyJa.eyJ",
    "eyJa.eyJb.",
    "sk-",
    "sk-proj-",
    "sk-ant-",
    "sk-lf-",
    "pk-lf-",
    "pa-",
    "AIza",
    "AKIA",
    "-----BEGIN PRIVATE KEY-----",
    "-----BEGIN RSA PRIVATE KEY-----\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASC\n",
    "-----BEGIN PRIVATE KEY-----\nProc-Type: 4,ENCRYPTED\n----",
    "-----",
    "-",
    "a-",
    "a_",
    "a",
    "a "
  )

  private def run(unit: String, length: Int): String = unit * (length / unit.length)

  SecretType.all.foreach { secretType =>
    "SecretPatterns" should s"find and redact ${secretType.name} in time linear in the text (#2824)" taggedAs PerformanceTest in {
      adversarialUnits.foreach { unit =>
        val label = s"${secretType.name} on a run of '${unit.take(24).replace("\n", "\\n")}', n = $n"
        LinearTime.assertLinear(label, run(unit, n), run(unit, 4 * n)) { text =>
          secretType.findAll(text).size + SecretPatterns.redactAll(text, Seq(secretType)).length
        }
      }
    }
  }

  // The scaling tests above are PerformanceTest, run by `sbt testPerformance`; this one stays in `sbt test` and fails
  // on a catastrophic regression of the worst of their shapes in one run, under a generous hang guard.
  "SecretPatterns" should "scan a megabyte of JWT and PEM prefixes for every secret type well inside the hang guard (#2824)" in {
    val input = run("eyJ", 500000) + run("-----BEGIN PRIVATE KEY-----", 500000)
    input.length should be > 999000
    LinearTime.timed("a megabyte of JWT and PEM prefixes", LinearTime.MegabyteHangGuard) {
      SecretPatterns.detect(input, SecretType.all).size + SecretPatterns.redactAll(input, SecretType.all).length
    }
  }

  // The JWT and PEM patterns step over a run one character at a time; a pattern that recursed for each would
  // overflow the stack here.
  it should "read a megabyte-long token run and a megabyte-long PEM body without recursing (#2824)" in {
    val jwt = run("a-", 1000000) + "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2lnbmF0dXJl"
    SecretPatterns.redactAll(jwt) shouldBe run("a-", 1000000) + "[REDACTED_JWT]"
    val pem = "-----BEGIN PRIVATE KEY-----\n" + run("MIIEvQIBADANBgkqhkiG9w0BAQEFAASC\n", 1000000)
    SecretPatterns.redactAll(pem + "-----END PRIVATE KEY-----", SecretType.all) shouldBe "[REDACTED_PRIVATE_KEY]"
    SecretPatterns.redactAll(pem, SecretType.all) shouldBe "[REDACTED_PRIVATE_KEY]"
    SecretPatterns.redactAll(pem + "- -", SecretType.all) shouldBe "[REDACTED_PRIVATE_KEY]- -"
  }
}
