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
    "accept a token of exactly the size limit" in {
      val token = "a" * IdentityTokenSource.MaxTokenFileBytes
      IdentityTokenSource.file(tempFile(token)).fetch() shouldBe Right(token)
    }
    "refuse a file larger than the size limit, without echoing its content" in {
      val content = "SECRETCONTENT" + ("a" * IdentityTokenSource.MaxTokenFileBytes)
      val error   = IdentityTokenSource.file(tempFile(content)).fetch().left.value
      error shouldBe an[AuthenticationError]
      error.message should include("larger than")
      (error.message should not).include("SECRETCONTENT")
    }
    "refuse a directory" in {
      val dir = Files.createTempDirectory("svid-dir")
      dir.toFile.deleteOnExit()
      val error = IdentityTokenSource.file(dir).fetch().left.value
      error shouldBe an[AuthenticationError]
      error.message should include("not a regular file")
    }
    "follow a symbolic link to the token, and to its replacement after a rotation" in {
      val first  = tempFile("first")
      val second = tempFile("second")
      val link   = first.resolveSibling(s"svid-link-${System.nanoTime()}")
      val made   = scala.util.Try(Files.createSymbolicLink(link, first)).isSuccess
      // Creating a link needs a privilege on Windows; the read path is the same as for a plain file there.
      assume(made, "symbolic links unavailable on this platform")
      link.toFile.deleteOnExit()
      val source = IdentityTokenSource.file(link)
      source.fetch() shouldBe Right("first")
      Files.delete(link)
      Files.createSymbolicLink(link, second)
      source.fetch() shouldBe Right("second")
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
