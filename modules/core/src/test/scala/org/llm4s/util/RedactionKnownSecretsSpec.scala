package org.llm4s.util

import org.llm4s.security.{ KnownSecrets, Secret }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RedactionKnownSecretsSpec extends AnyFlatSpec with Matchers {
  private val cred = "zq8-not-a-vendor-shape-41f0c2"

  // a value no heuristic recognises: only the registry can catch it
  private val shapes = Seq(
    s"plain text $cred here",
    s"""{"detail":"unexpected value $cred"}""",
    s"weird<<$cred>>syntax",
    s"""{"o":"{\\"note\\":\\"$cred\\"}"}"""
  )

  "every Redaction entry point" should "remove a registered credential, whatever the syntax" in KnownSecrets.isolated {
    Secret(cred)
    shapes.foreach { text =>
      (Redaction.redact(text) should not).include(cred)
      (Redaction.safeBody(text) should not).include(cred)
      (Redaction.redactForLogging(text) should not).include(cred)
      (Redaction.safe(text) should not).include(cred)
      (Redaction.remoteBody(text) should not).include(cred)
      (Redaction.scrubRemote(text) should not).include(cred)
      (Redaction.scrubKnown(text) should not).include(cred)
    }
  }

  it should "remove a registered credential from a URL's path" in KnownSecrets.isolated {
    Secret(cred)
    (Redaction.url(s"https://example.test/token/$cred/v1") should not).include(cred)
  }

  it should "leave an unregistered value of the same shape to the existing rules" in KnownSecrets.isolated {
    Redaction.scrubKnown(s"plain text $cred here") shouldBe s"plain text $cred here"
  }
}
