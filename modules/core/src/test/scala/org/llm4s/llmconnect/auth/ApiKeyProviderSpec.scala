package org.llm4s.llmconnect.auth

import org.llm4s.error.ConfigurationError
import org.llm4s.types.ProviderModelTypes.ApiKey
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.charset.StandardCharsets
import java.nio.file.attribute.FileTime
import java.nio.file.{ Files, Path }

/** `ApiKeyProvider` (#2313): a key read per request, so a rotated key is used without rebuilding the client. */
class ApiKeyProviderSpec extends AnyWordSpec with Matchers with EitherValues:

  private def tempFile(content: String): Path =
    val file = Files.createTempFile("api-key", ".txt")
    file.toFile.deleteOnExit()
    Files.writeString(file, content, StandardCharsets.UTF_8)

  private def key(provider: ApiKeyProvider): String = provider.current().value.asKey

  "ApiKeyProvider.static" should {
    "always return its key, and hide it in toString" in {
      val provider = ApiKeyProvider.static(ApiKey("sk-static-0123456789"))
      key(provider) shouldBe "sk-static-0123456789"
      key(provider) shouldBe "sk-static-0123456789"
      (provider.toString should not).include("sk-static")
    }
  }

  "ApiKeyProvider.file" should {
    "read the key, trimmed" in {
      key(ApiKeyProvider.file(tempFile("  sk-from-file-0001\n"))) shouldBe "sk-from-file-0001"
    }

    "return the new key once the file is rewritten" in {
      val file     = tempFile("sk-first-key-0001")
      val provider = ApiKeyProvider.file(file)
      key(provider) shouldBe "sk-first-key-0001"
      Files.writeString(file, "sk-rotated-key-00002")
      key(provider) shouldBe "sk-rotated-key-00002"
    }

    "reuse the key it read while the file is unchanged" in {
      val file     = tempFile("sk-cached-key-0001")
      val provider = ApiKeyProvider.file(file)
      key(provider) shouldBe "sk-cached-key-0001"
      // Same size and the same modification time: the file is not read again.
      val modified = Files.getLastModifiedTime(file)
      Files.writeString(file, "sk-cached-key-0002")
      Files.setLastModifiedTime(file, modified)
      key(provider) shouldBe "sk-cached-key-0001"
      // A new modification time is a change.
      Files.setLastModifiedTime(file, FileTime.fromMillis(modified.toMillis + 60000))
      key(provider) shouldBe "sk-cached-key-0002"
    }

    "fail with a ConfigurationError naming the path for a missing file" in {
      val missing = Path.of("/no/such/api-key")
      val error   = ApiKeyProvider.file(missing).current().left.value
      error shouldBe a[ConfigurationError]
      // The message carries the platform's rendering of the path (`\no\such\api-key` on Windows).
      error.message should include(missing.toString)
    }

    "fail for an empty or whitespace-only file" in {
      ApiKeyProvider.file(tempFile("")).current().left.value.message should include("is empty")
      ApiKeyProvider.file(tempFile(" \n\t")).current().left.value.message should include("is empty")
    }

    "fail for a key of more than one line, without echoing it" in {
      val error = ApiKeyProvider.file(tempFile("sk-line-one-0001\nsk-line-two-0002")).current().left.value
      error.message should include("more than one line")
      (error.message should not).include("sk-line")
    }

    "refuse a file larger than the size limit, without echoing its content" in {
      val content = "SECRETCONTENT" + ("a" * ApiKeyProvider.MaxKeyFileBytes)
      val error   = ApiKeyProvider.file(tempFile(content)).current().left.value
      error.message should include("larger than")
      (error.message should not).include("SECRETCONTENT")
    }

    "refuse a directory" in {
      val dir = Files.createTempDirectory("api-key-dir")
      dir.toFile.deleteOnExit()
      ApiKeyProvider.file(dir).current().left.value.message should include("not a regular file")
    }

    "fail once the file is deleted, and recover when it is written again" in {
      val file     = tempFile("sk-before-delete-01")
      val provider = ApiKeyProvider.file(file)
      key(provider) shouldBe "sk-before-delete-01"
      Files.delete(file)
      provider.current().left.value shouldBe a[ConfigurationError]
      Files.writeString(file, "sk-after-delete-0002")
      key(provider) shouldBe "sk-after-delete-0002"
    }

    "name the path, never the key, in toString" in {
      val file = tempFile("sk-hidden-key-00001")
      val text = ApiKeyProvider.file(file).toString
      text should include(file.toString)
      (text should not).include("sk-hidden")
    }
  }
