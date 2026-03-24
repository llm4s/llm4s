package org.llm4s.llmconnect

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class ProviderExchangeSinkSpec extends AnyFunSuite with Matchers:

  test("JsonlProviderExchangeSink appends redacted exchange records") {
    val tempFile = Files.createTempFile("provider-exchange", ".jsonl")
    val sink     = JsonlProviderExchangeSink(tempFile)

    val exchange = ProviderExchange(
      exchangeId = "ex-2",
      provider = "openai",
      model = Some("gpt-4o"),
      requestId = None,
      correlationId = None,
      startedAt = Instant.parse("2026-03-22T10:10:00Z"),
      completedAt = Instant.parse("2026-03-22T10:10:01Z"),
      durationMs = 1000L,
      outcome = ProviderExchangeOutcome.Error,
      requestBody = """{"apiKey":"sk-secret","prompt":"hello"}""",
      responseBody = Some("""{"message":"failed"}"""),
      errorMessage = Some("Authorization: Bearer sk-secret")
    )

    sink.record(exchange)

    val lines = Files.readAllLines(tempFile)
    lines.size shouldBe 1

    val json = ujson.read(lines.get(0))
    json("exchange_id").str shouldBe "ex-2"
    json("provider").str shouldBe "openai"
    json("outcome").str shouldBe "Error"
    (json("request_body").str should not).include("sk-secret")
    (json("error_message").str should not).include("sk-secret")
  }

  test("ProviderExchangeSink.createRunScopedJsonl creates a timestamped file in the directory") {
    val tempDir   = Files.createTempDirectory("provider-exchange-dir")
    val startedAt = Instant.parse("2026-03-23T07:15:00Z")

    val result = ProviderExchangeSink.createRunScopedJsonl(tempDir, startedAt)

    result.isRight shouldBe true
    val sink = result.toOption.get
    sink.path.getParent shouldBe tempDir
    sink.path.getFileName.toString shouldBe "provider-exchanges-2026-03-23T07-15-00Z.jsonl"
    Files.exists(sink.path) shouldBe true
  }

  test("ProviderExchangeSink.createRunScopedJsonl creates a collision-safe suffix when the base file exists") {
    val tempDir   = Files.createTempDirectory("provider-exchange-collision")
    val startedAt = Instant.parse("2026-03-23T07:15:00Z")
    val firstPath = tempDir.resolve("provider-exchanges-2026-03-23T07-15-00Z.jsonl")
    Files.createFile(firstPath)

    val result = ProviderExchangeSink.createRunScopedJsonl(tempDir, startedAt)

    result.isRight shouldBe true
    val sink = result.toOption.get
    sink.path.getFileName.toString shouldBe "provider-exchanges-2026-03-23T07-15-00Z-2.jsonl"
    Files.exists(sink.path) shouldBe true
  }

  test("ProviderExchangeSink.createRunScopedJsonl fails when the directory path is an existing file") {
    val existingFile: Path = Files.createTempFile("provider-exchange-not-dir", ".tmp")

    val result = ProviderExchangeSink.createRunScopedJsonl(existingFile, Instant.parse("2026-03-23T07:15:00Z"))

    result.isLeft shouldBe true
    result.left.toOption.exists(
      _.message.contains("Failed to create provider exchange log file in directory")
    ) shouldBe true
  }

  test("ProviderExchangeSink.createRunScopedJsonl fails with ConfigurationError when the directory is not writable") {
    val tempDir = Files.createTempDirectory("provider-exchange-read-only")
    assume(Files.getFileStore(tempDir).supportsFileAttributeView("posix"))

    val originalPermissions = Files.getPosixFilePermissions(tempDir)
    Files.setPosixFilePermissions(tempDir, PosixFilePermissions.fromString("r-xr-xr-x"))

    try
      val result = ProviderExchangeSink.createRunScopedJsonl(tempDir, Instant.parse("2026-03-23T07:15:00Z"))

      result.isLeft shouldBe true
      result.left.toOption.exists(
        _.message.contains("Failed to create provider exchange log file in directory")
      ) shouldBe true
    finally Files.setPosixFilePermissions(tempDir, originalPermissions)
  }
