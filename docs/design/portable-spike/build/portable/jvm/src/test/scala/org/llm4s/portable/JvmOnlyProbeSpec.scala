package org.llm4s.portable

import org.llm4s.core.safety.DefaultErrorMapper
import org.llm4s.types.ArtifactKey
import org.llm4s.types.ProviderModelTypes.ApiKey
import org.llm4s.util.Redaction
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Runs only on the JVM. Neither Scala.js 1.22 nor Scala Native 0.5.12 has `java.security.MessageDigest`. `Redaction`
 * does not link on Native (`URLEncoder.encode(String, Charset)`) and links on Scala.js but fails at run time: its
 * patterns use look-behind (ES2018, set in this build) and an embedded flag in mid-pattern, which JavaScript's
 * RegExp cannot express. `DefaultErrorMapper` reaches `Redaction`, and on Scala.js also
 * `java.io.InterruptedIOException`, `java.net.ConnectException` and `Matcher.useTransparentBounds`.
 */
class JvmOnlyProbeSpec extends AnyFlatSpec with Matchers {

  "ArtifactKey.fromContent" should "hash content with SHA-256" in {
    ArtifactKey.fromContent("abc").value shouldBe
      "content_ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
  }

  "Redaction" should "hide a registered key in text" in {
    ApiKey("sk-portable-spike-0123456789abcdef").toString shouldBe "***"
    Redaction.redact("token sk-portable-spike-0123456789abcdef here").contains("0123456789abcdef") shouldBe false
  }

  "DefaultErrorMapper" should "map a throwable to an LLMError" in {
    DefaultErrorMapper(new java.io.IOException("boom")).message should include("boom")
  }
}
