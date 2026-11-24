package org.llm4s.llmconnect.auth

import org.llm4s.error.AuthenticationError
import org.llm4s.security.Secret
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }

class IdentityTokenProviderSpec extends AnyWordSpec with Matchers with EitherValues:

  private def tempFile(content: String): Path =
    val file = Files.createTempFile("svid", ".jwt")
    file.toFile.deleteOnExit()
    Files.writeString(file, content, StandardCharsets.UTF_8)

  "IdentityTokenProvider.file" should {
    "read the token, trimmed" in {
      IdentityTokenProvider.file(tempFile("  eyJ.a.b\n")).current().map(_.reveal) shouldBe Right("eyJ.a.b")
    }
    "pick up a rotated token on the next fetch" in {
      val file   = tempFile("first")
      val source = IdentityTokenProvider.file(file)
      source.current().map(_.reveal) shouldBe Right("first")
      Files.writeString(file, "second")
      source.current().map(_.reveal) shouldBe Right("second")
    }
    "fail with AuthenticationError for a missing file" in {
      val missing = Path.of("/no/such/svid")
      val error   = IdentityTokenProvider.file(missing).current().left.value
      error shouldBe an[AuthenticationError]
      // The message carries the platform's rendering of the path (`\no\such\svid` on Windows).
      error.message should include(missing.toString)
    }
    "fail with AuthenticationError for an empty or whitespace-only file" in {
      IdentityTokenProvider.file(tempFile("")).current().left.value shouldBe an[AuthenticationError]
      IdentityTokenProvider.file(tempFile(" \n\t")).current().left.value shouldBe an[AuthenticationError]
    }
    "accept a token of exactly the size limit" in {
      val token = "a" * IdentityTokenProvider.MaxTokenFileBytes
      IdentityTokenProvider.file(tempFile(token)).current().map(_.reveal) shouldBe Right(token)
    }
    "refuse a file larger than the size limit, without echoing its content" in {
      val content = "SECRETCONTENT" + ("a" * IdentityTokenProvider.MaxTokenFileBytes)
      val error   = IdentityTokenProvider.file(tempFile(content)).current().left.value
      error shouldBe an[AuthenticationError]
      error.message should include("larger than")
      (error.message should not).include("SECRETCONTENT")
    }
    "refuse a directory" in {
      val dir = Files.createTempDirectory("svid-dir")
      dir.toFile.deleteOnExit()
      val error = IdentityTokenProvider.file(dir).current().left.value
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
      val source = IdentityTokenProvider.file(link)
      source.current().map(_.reveal) shouldBe Right("first")
      Files.delete(link)
      Files.createSymbolicLink(link, second)
      source.current().map(_.reveal) shouldBe Right("second")
    }
  }

  "IdentityTokenProvider.from" should {
    "turn a literal into a static source" in {
      IdentityTokenProvider.from(IdentitySource.Literal(Secret("tok"))).current().map(_.reveal) shouldBe Right("tok")
    }
    "trim a padded literal, and refuse a blank one" in {
      IdentityTokenProvider.static(Secret("  padded-literal-token  ")).current().map(_.reveal) shouldBe
        Right("padded-literal-token")
      IdentityTokenProvider.static(Secret("   ")).current().isLeft shouldBe true
    }
    "give a token that prints as ***, whether read from a file or held as a literal (#2811)" in {
      IdentityTokenProvider.static(Secret("literal-identity-token")).current().map(_.toString) shouldBe Right("***")
      IdentityTokenProvider.file(tempFile("file-identity-token")).current().map(_.toString) shouldBe Right("***")
    }
    "never show a literal token in toString" in {
      (IdentitySource.Literal(Secret("secret-jwt")).toString should not).include("secret-jwt")
    }
  }
