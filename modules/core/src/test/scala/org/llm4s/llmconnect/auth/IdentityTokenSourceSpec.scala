package org.llm4s.llmconnect.auth

import org.llm4s.error.AuthenticationError
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }

class IdentityTokenSourceSpec extends AnyWordSpec with Matchers with EitherValues:

  private def tempFile(content: String): Path =
    val file = Files.createTempFile("svid", ".jwt")
    file.toFile.deleteOnExit()
    Files.writeString(file, content, StandardCharsets.UTF_8)

  "IdentityTokenSource.file" should {
    "read the token, trimmed" in {
      IdentityTokenSource.file(tempFile("  eyJ.a.b\n")).fetch() shouldBe Right("eyJ.a.b")
    }
    "pick up a rotated token on the next fetch" in {
      val file   = tempFile("first")
      val source = IdentityTokenSource.file(file)
      source.fetch() shouldBe Right("first")
      Files.writeString(file, "second")
      source.fetch() shouldBe Right("second")
    }
    "fail with AuthenticationError for a missing file" in {
      val missing = Path.of("/no/such/svid")
      val error   = IdentityTokenSource.file(missing).fetch().left.value
      error shouldBe an[AuthenticationError]
      // The message carries the platform's rendering of the path (`\no\such\svid` on Windows).
      error.message should include(missing.toString)
    }
    "fail with AuthenticationError for an empty or whitespace-only file" in {
      IdentityTokenSource.file(tempFile("")).fetch().left.value shouldBe an[AuthenticationError]
      IdentityTokenSource.file(tempFile(" \n\t")).fetch().left.value shouldBe an[AuthenticationError]
    }
  }

  "IdentityTokenSource.from" should {
    "turn a literal into a static source" in {
      IdentityTokenSource.from(IdentitySource.Literal("tok")).fetch() shouldBe Right("tok")
    }
    "never show a literal token in toString" in {
      (IdentitySource.Literal("secret-jwt").toString should not).include("secret-jwt")
    }
  }
