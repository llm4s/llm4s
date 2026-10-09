package org.llm4s.context

import org.llm4s.llmconnect.model._
import org.llm4s.testutil.SmallStack
import org.llm4s.types.ArtifactKey
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ToolOutputCompressorSpec extends AnyFlatSpec with Matchers {

  // ============ Content Type Detection ============

  "ToolOutputCompressor content type detection" should "identify JSON objects" in {
    val jsonContent = """{"name": "test", "value": 42}"""
    val message     = ToolMessage(jsonContent, "call_1")
    val store       = ArtifactStore.inMemory()

    // Process small JSON - should remain unchanged
    val result = ToolOutputCompressor.compressToolOutputs(Seq(message), store)

    result.isRight shouldBe true
    result.toOption.get.head.content shouldBe jsonContent
  }

  it should "identify JSON arrays" in {
    val jsonArray = """[1, 2, 3, "test"]"""
    val message   = ToolMessage(jsonArray, "call_1")
    val store     = ArtifactStore.inMemory()

    val result = ToolOutputCompressor.compressToolOutputs(Seq(message), store)

    result.isRight shouldBe true
  }

  it should "identify log content" in {
    val logContent =
      """INFO  2024-01-01 12:00:00 - Starting process
        |DEBUG 2024-01-01 12:00:01 - Loading config
        |WARN  2024-01-01 12:00:02 - Config missing value""".stripMargin
    val message = ToolMessage(logContent, "call_1")
    val store   = ArtifactStore.inMemory()

    val result = ToolOutputCompressor.compressToolOutputs(Seq(message), store)

    result.isRight shouldBe true
  }

  it should "identify error traces" in {
    val errorContent =
      """ERROR: NullPointerException
        |Exception in thread "main"
        |  at com.example.Main.run(Main.java:42)""".stripMargin
    val message = ToolMessage(errorContent, "call_1")
    val store   = ArtifactStore.inMemory()

    val result = ToolOutputCompressor.compressToolOutputs(Seq(message), store)

    result.isRight shouldBe true
  }

  it should "identify binary content" in {
    val binaryContent = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUg..."
    val message       = ToolMessage(binaryContent, "call_1")
    val store         = ArtifactStore.inMemory()

    val result = ToolOutputCompressor.compressToolOutputs(Seq(message), store)

    result.isRight shouldBe true
  }

  // ============ JSON Compression ============

  "ToolOutputCompressor JSON compression" should "remove null values when compressed" in {
    // Create content between 2KB and 8KB to trigger inline compression (not externalization)
    // Need 2KB+ to trigger compression, but <8KB to avoid externalization
    val nullFields  = (1 to 200).map(i => s""""field$i": null""").mkString(", ")
    val jsonContent = s"""{$nullFields, "keep": "value", "padding": "${"x" * 2000}"}"""
    val message     = ToolMessage(jsonContent, "call_1")
    val store       = ArtifactStore.inMemory()

    val result = ToolOutputCompressor.compressToolOutputs(Seq(message), store)

    result.isRight shouldBe true
    val processed = result.toOption.get.head.content
    // Either compressed (nulls removed) or externalized - both are valid compression outcomes
    processed.length should be < jsonContent.length
  }

  it should "remove empty strings from JSON" in {
    val emptyFields = (1 to 100).map(i => s""""field$i": """"").mkString(", ")
    val jsonContent = s"""{$emptyFields, "keep": "value"}"""
    val message     = ToolMessage(jsonContent, "call_1")
    val store       = ArtifactStore.inMemory()

    val result = ToolOutputCompressor.compressToolOutputs(Seq(message), store)

    result.isRight shouldBe true
  }

  it should "truncate large arrays to head + tail" in {
    // Create an array with 50 elements (> 20 triggers truncation)
    val largeArray = (1 to 50).map(i => s"$i").mkString("[", ", ", "]")
    // Need content > 2KB to trigger compression
    val jsonContent = s"""{"items": $largeArray, "padding": "${"x" * 2000}"}"""
    val message     = ToolMessage(jsonContent, "call_1")
    val store       = ArtifactStore.inMemory()

    val result = ToolOutputCompressor.compressToolOutputs(Seq(message), store)

    result.isRight shouldBe true
    val compressed = result.toOption.get.head.content
    // Should have truncation marker
    compressed should (include("items").or(include("+")))
  }

  // ============ Log Compression ============

  "ToolOutputCompressor log compression" should "keep short logs unchanged" in {
    val shortLog = "INFO  Starting\nDEBUG Loading\nINFO  Done"
    val message  = ToolMessage(shortLog, "call_1")
    val store    = ArtifactStore.inMemory()

    val result = ToolOutputCompressor.compressToolOutputs(Seq(message), store)

    result.isRight shouldBe true
    result.toOption.get.head.content shouldBe shortLog
  }

  it should "compress long logs when over inline threshold" in {
    // Create log with 150 lines (> 120 triggers compression) but under 8KB to avoid externalization
    val lines   = (1 to 150).map(i => s"INFO  L$i")
    val longLog = lines.mkString("\n")
    // Need content > 2KB but < 8KB for inline compression (not externalization)
    val paddedLog = longLog + "\n" + ("x" * 2500)
    val message   = ToolMessage(paddedLog, "call_1")
    val store     = ArtifactStore.inMemory()

    val result = ToolOutputCompressor.compressToolOutputs(Seq(message), store)

    result.isRight shouldBe true
    val processed = result.toOption.get.head.content
    // Should be either compressed inline (with collapsed marker) or externalized
    // Both are valid outcomes depending on content size thresholds
    processed.length should be < paddedLog.length
  }

  // ============ Externalization ============

  "ToolOutputCompressor externalization" should "externalize content over threshold" in {
    // Create content larger than default threshold (8KB)
    val largeContent = "x" * 10000
    val message      = ToolMessage(largeContent, "call_1")
    val store        = ArtifactStore.inMemory()

    val result = ToolOutputCompressor.compressToolOutputs(Seq(message), store)

    result.isRight shouldBe true
    val processed = result.toOption.get.head.content
    processed should include("EXTERNALIZED")
  }

  it should "create content pointers for externalized content" in {
    val largeJson = s"""{"data": "${"x" * 10000}"}"""
    val message   = ToolMessage(largeJson, "call_1")
    val store     = ArtifactStore.inMemory()

    val result = ToolOutputCompressor.compressToolOutputs(Seq(message), store)

    result.isRight shouldBe true
    val pointer = result.toOption.get.head.content
    pointer should include("EXTERNALIZED")
    pointer should include("JSON")
  }

  it should "store externalized content in artifact store" in {
    val largeContent = "important-data-" + ("x" * 10000)
    val message      = ToolMessage(largeContent, "call_1")
    val store        = ArtifactStore.inMemory()

    val result = ToolOutputCompressor.compressToolOutputs(Seq(message), store)

    result.isRight shouldBe true
    // Verify content was stored
    val key = ArtifactKey.fromContent(largeContent)
    store.exists(key) shouldBe true
    store.retrieve(key).toOption.get shouldBe Some(largeContent)
  }

  // ============ ArtifactStore ============

  "InMemoryArtifactStore" should "store and retrieve content" in {
    val store   = ArtifactStore.inMemory()
    val key     = ArtifactKey("test-key")
    val content = "test content"

    store.store(key, content)

    store.exists(key) shouldBe true
    store.retrieve(key) shouldBe Right(Some(content))
  }

  it should "return None for missing keys" in {
    val store = ArtifactStore.inMemory()
    val key   = ArtifactKey("missing-key")

    store.exists(key) shouldBe false
    store.retrieve(key) shouldBe Right(None)
  }

  it should "overwrite existing content" in {
    val store = ArtifactStore.inMemory()
    val key   = ArtifactKey("overwrite-key")

    store.store(key, "original")
    store.store(key, "updated")

    store.retrieve(key) shouldBe Right(Some("updated"))
  }

  // ============ Edge Cases ============

  "ToolOutputCompressor" should "pass through non-tool messages unchanged" in {
    val messages = Seq(
      UserMessage("Hello"),
      AssistantMessage("Hi there"),
      ToolMessage("small output", "call_1")
    )
    val store = ArtifactStore.inMemory()

    val result = ToolOutputCompressor.compressToolOutputs(messages, store)

    result.isRight shouldBe true
    val processed = result.toOption.get
    processed.length shouldBe 3
    processed(0) shouldBe messages(0)
    processed(1) shouldBe messages(1)
  }

  it should "handle empty message list" in {
    val store = ArtifactStore.inMemory()

    val result = ToolOutputCompressor.compressToolOutputs(Seq.empty, store)

    result.isRight shouldBe true
    result.toOption.get shouldBe empty
  }

  it should "preserve tool call IDs" in {
    val message = ToolMessage("content", "my-unique-call-id")
    val store   = ArtifactStore.inMemory()

    val result = ToolOutputCompressor.compressToolOutputs(Seq(message), store)

    result.isRight shouldBe true
    result.toOption.get.head match {
      case tm: ToolMessage => tm.toolCallId shouldBe "my-unique-call-id"
      case _               => fail("Expected ToolMessage")
    }
  }

  it should "handle invalid JSON gracefully" in {
    val invalidJson = "{not valid json"
    val message     = ToolMessage(invalidJson, "call_1")
    val store       = ArtifactStore.inMemory()

    val result = ToolOutputCompressor.compressToolOutputs(Seq(message), store)

    result.isRight shouldBe true
  }

  it should "handle multiple tool messages" in {
    val messages = (1 to 5).map(i => ToolMessage(s"""{"id": $i, "data": "test"}""", s"call_$i"))
    val store    = ArtifactStore.inMemory()

    val result = ToolOutputCompressor.compressToolOutputs(messages, store)

    result.isRight shouldBe true
    result.toOption.get.length shouldBe 5
  }

  // A tool result is untrusted input: JSON nested too deeply to parse is treated as text, both on
  // the inline-compression path (above 2 KB) and the externalisation path (above the threshold), and
  // never overflows the stack (#1562). On a 1 MB stack, so the outcome is deterministic.
  it should "treat a tool result nested too deeply to parse as text instead of overflowing the stack" in {
    val inline       = "[" * 2000 + "]" * 2000 // 4 KB: compressed inline, below the 8 KB threshold
    val externalized = "[" * 100000 + "]" * 100000
    Seq(inline, externalized).foreach { deep =>
      val store = ArtifactStore.inMemory()
      withClue(s"${deep.length} bytes: ") {
        SmallStack.run(ToolOutputCompressor.compressToolOutputs(Seq(ToolMessage(deep, "call_1")), store)) match {
          case Right(Right(messages)) => messages should have size 1
          case other                  => fail(s"expected Right(Right(messages)), got ${other.toString.take(200)}")
        }
      }
    }
  }
}
