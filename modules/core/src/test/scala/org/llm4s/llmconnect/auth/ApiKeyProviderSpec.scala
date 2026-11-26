package org.llm4s.llmconnect.auth

import org.llm4s.error.ConfigurationError
import org.llm4s.types.ProviderModelTypes.ApiKey
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.charset.StandardCharsets
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.{ Level, Logger => LogbackLogger }
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

import java.nio.file.attribute.{ FileTime, PosixFilePermissions }
import scala.jdk.CollectionConverters.*
import scala.util.Try
import java.nio.file.{ Files, Path }

/** `ApiKeyProvider` (#2313): a key read per request, so a rotated key is used without rebuilding the client. */
class ApiKeyProviderSpec extends AnyWordSpec with Matchers with EitherValues:

  private def tempFile(content: String): Path =
    val file = Files.createTempFile("api-key", ".txt")
    file.toFile.deleteOnExit()
    Files.writeString(file, content, StandardCharsets.UTF_8)

  private def key(provider: ApiKeyProvider): String = provider.current().value.asKey

  private def warningsDuring(body: => Unit): List[String] =
    val logger   = LoggerFactory.getLogger(ApiKeyProvider.getClass).asInstanceOf[LogbackLogger]
    val appender = new ListAppender[ILoggingEvent]()
    appender.start()
    logger.addAppender(appender)
    Try(body).fold(
      e =>
        logger.detachAppender(appender)
        throw e
      ,
      _ => logger.detachAppender(appender)
    )
    appender.list.asScala.toList.filter(_.getLevel == Level.WARN).map(_.getFormattedMessage)

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

    "drop a leading byte-order mark, which Windows editors write (#3109)" in {
      key(ApiKeyProvider.file(tempFile("\uFEFFsk-bom-key-00001\r\n"))) shouldBe "sk-bom-key-00001"
      key(ApiKeyProvider.file(tempFile("\n \uFEFFsk-bom-key-00002\n"))) shouldBe "sk-bom-key-00002"
      ApiKeyProvider.file(tempFile("\uFEFF\n")).current().left.value.message should include("is empty")
    }

    "fail for a UTF-16 key file, saying to save it as UTF-8 (#3109)" in {
      Seq(StandardCharsets.UTF_16LE -> Array(0xff, 0xfe), StandardCharsets.UTF_16BE -> Array(0xfe, 0xff)).foreach {
        case (charset, bom) =>
          val file = Files.createTempFile("llm4s-key", ".txt")
          file.toFile.deleteOnExit()
          Files.write(file, bom.map(_.toByte) ++ "sk-utf16-key-0001\r\n".getBytes(charset))
          val error = ApiKeyProvider.file(file).current().left.value
          withClue(charset) {
            error shouldBe a[ConfigurationError]
            error.message should include(file.toString)
            error.message should include("is UTF-16 encoded; save it as UTF-8")
          }
      }
    }

    "keep a key holding a tab or a Latin-1 character, which the JDK HTTP client sends (#3109)" in {
      key(ApiKeyProvider.file(tempFile("sk-tab\tkey-0001\n"))) shouldBe "sk-tab\tkey-0001"
      key(ApiKeyProvider.file(tempFile("sk-caf\u00e9-key-0001"))) shouldBe "sk-caf\u00e9-key-0001"
      key(ApiKeyProvider.file(tempFile("sk-nbsp\u00a0key-0001"))) shouldBe "sk-nbsp\u00a0key-0001"
    }

    "fail for a key holding a control character or one above U+00FF, naming the kind, without echoing it (#3109)" in {
      val cases = Seq(
        "sk-nul\u0000key-0001"    -> "a control character at position 7 of the trimmed key",
        "sk-esc\u001bkey-0001"    -> "a control character at position 7 of the trimmed key",
        "sk-del\u007fkey-0001"    -> "a control character at position 7 of the trimmed key",
        "sk-bom\uFEFFkey-0001"    -> "a character above U+00FF at position 7 of the trimmed key",
        "sk-zcaron\u017ekey-0001" -> "a character above U+00FF at position 10 of the trimmed key",
        "  sk-ab\u0001key-0001"   -> "a control character at position 6 of the trimmed key"
      )
      cases.foreach { case (content, reason) =>
        val file  = tempFile(content)
        val error = ApiKeyProvider.file(file).current().left.value
        withClue(reason) {
          error shouldBe a[ConfigurationError]
          error.message should include(file.toString)
          error.message should include(reason)
          (error.message should not).include("key-0001")
        }
      }
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

    "warn once, naming the path and never the key, when the file is readable by group or others (POSIX)" in {
      val shared = tempFile("sk-shared-mode-0001")
      val owned  = tempFile("sk-owner-mode-00001")
      assume(Try(Files.getPosixFilePermissions(shared)).isSuccess, "not a POSIX file system")
      Files.setPosixFilePermissions(shared, PosixFilePermissions.fromString("rw-r--r--"))
      Files.setPosixFilePermissions(owned, PosixFilePermissions.fromString("rw-------"))
      val warnings = warningsDuring {
        val provider = ApiKeyProvider.file(shared)
        key(provider) shouldBe "sk-shared-mode-0001"
        Files.writeString(shared, "sk-shared-mode-rotated-2")
        key(provider) shouldBe "sk-shared-mode-rotated-2"
        key(ApiKeyProvider.file(owned)) shouldBe "sk-owner-mode-00001"
      }
      warnings should have size 1
      warnings.head should include(shared.toString)
      (warnings.head should not).include("sk-shared")
    }

    "name the path, never the key, in toString" in {
      val file = tempFile("sk-hidden-key-00001")
      val text = ApiKeyProvider.file(file).toString
      text should include(file.toString)
      (text should not).include("sk-hidden")
    }
  }
