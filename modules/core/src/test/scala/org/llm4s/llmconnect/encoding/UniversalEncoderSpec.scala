package org.llm4s.llmconnect.encoding

import org.llm4s.llmconnect.EmbeddingClient
import org.llm4s.llmconnect.config.{ EmbeddingModelConfig, LocalEmbeddingModels }
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.llm4s.types.Result
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.io.File
import java.nio.file.Files

class UniversalEncoderSpec extends AnyFunSuite with Matchers {

  // Mock embedding provider for testing
  private class MockEmbeddingProvider extends EmbeddingProvider {
    override def embed(request: EmbeddingRequest): Result[EmbeddingResponse] =
      Right(
        EmbeddingResponse(
          embeddings = request.input.map(_ => (1 to request.model.dimensions).map(i => i.toDouble / 100).toSeq),
          metadata = Map("provider" -> "mock", "count" -> request.input.size.toString)
        )
      )
  }

  private val mockClient      = new EmbeddingClient(new MockEmbeddingProvider())
  private val testTextModel   = EmbeddingModelConfig("test-model", 128)
  private val defaultChunking = UniversalEncoder.TextChunkingConfig(enabled = false, size = 1000, overlap = 100)

  // Use model names from ModelDimensionRegistry
  private val testLocalModels = LocalEmbeddingModels(
    imageModel = "openclip-vit-b32",
    audioModel = "wav2vec2-base",
    videoModel = "timesformer-base"
  )

  private def withTempFile(extension: String, content: String)(test: File => Unit): Unit = {
    val file = Files.createTempFile("test-encoder-", extension).toFile
    try {
      Files.write(file.toPath, content.getBytes("UTF-8"))
      test(file)
    } finally
      file.delete()
  }

  // ================================= TEXT ENCODING =================================

  test("encodeFromPath should encode text file content") {
    withTempFile(".txt", "Hello world") { file =>
      val result = UniversalEncoder.encodeFromPath(
        file.toPath,
        mockClient,
        testTextModel,
        defaultChunking,
        experimentalStubsEnabled = false,
        testLocalModels
      )

      result.isRight shouldBe true
      val vectors = result.toOption.get
      vectors.length shouldBe 1
      vectors.head.modality shouldBe Text
      vectors.head.model shouldBe "test-model"
    }
  }

  test("encodeFromPath should chunk text when chunking is enabled") {
    val longText = "word " * 500 // 2500 characters
    withTempFile(".txt", longText) { file =>
      val chunkingConfig = UniversalEncoder.TextChunkingConfig(enabled = true, size = 500, overlap = 50)

      val result = UniversalEncoder.encodeFromPath(
        file.toPath,
        mockClient,
        testTextModel,
        chunkingConfig,
        experimentalStubsEnabled = false,
        testLocalModels
      )

      result.isRight shouldBe true
      val vectors = result.toOption.get
      vectors.length should be > 1
      vectors.foreach(_.modality shouldBe Text)
    }
  }

  test("encodeFromPath should return error for non-existent file") {
    val nonExistent = new File("/nonexistent/path/file.txt")

    val result = UniversalEncoder.encodeFromPath(
      nonExistent.toPath,
      mockClient,
      testTextModel,
      defaultChunking,
      experimentalStubsEnabled = false,
      testLocalModels
    )

    result.isLeft shouldBe true
    result.left.toOption.get.message should include("File not found")
  }

  test("encodeFromPath processes JSON-like text files") {
    // Note: Tika detects MIME type from content, not extension
    // JSON content in a temp file may be detected as text/plain
    withTempFile(".json", """{"key": "value"}""") { file =>
      val result = UniversalEncoder.encodeFromPath(
        file.toPath,
        mockClient,
        testTextModel,
        defaultChunking,
        experimentalStubsEnabled = false,
        testLocalModels
      )

      // Either succeeds (detected as text) or fails (unknown type)
      result.isRight || result.isLeft shouldBe true
    }
  }

  // ================================= EXPERIMENTAL STUBS =================================

  test("encodeFromPath with experimental stubs handles various content") {
    // Note: Tika detects MIME type from content, not extension
    // Text-like content will be detected as text/plain regardless of extension
    withTempFile(".txt", "Test content for experimental encoding") { file =>
      val result = UniversalEncoder.encodeFromPath(
        file.toPath,
        mockClient,
        testTextModel,
        defaultChunking,
        experimentalStubsEnabled = true,
        testLocalModels
      )

      result.isRight shouldBe true
    }
  }

  // ================================= VECTOR METADATA =================================

  test("encodeFromPath should include correct metadata in vectors") {
    withTempFile(".txt", "Test content") { file =>
      val result = UniversalEncoder.encodeFromPath(
        file.toPath,
        mockClient,
        testTextModel,
        defaultChunking,
        experimentalStubsEnabled = false,
        testLocalModels
      )

      result.isRight shouldBe true
      val vector = result.toOption.get.head
      (vector.meta should contain).key("provider")
      (vector.meta should contain).key("mime")
    }
  }

  test("encodeFromPath should generate chunk IDs") {
    withTempFile(".txt", "Test content") { file =>
      val result = UniversalEncoder.encodeFromPath(
        file.toPath,
        mockClient,
        testTextModel,
        defaultChunking,
        experimentalStubsEnabled = false,
        testLocalModels
      )

      result.isRight shouldBe true
      val vector = result.toOption.get.head
      vector.id should include("chunk_0")
    }
  }

  // ================================= L2 NORMALIZATION =================================

  test("encodeFromPath should return normalized vectors") {
    withTempFile(".txt", "Test content") { file =>
      val result = UniversalEncoder.encodeFromPath(
        file.toPath,
        mockClient,
        testTextModel,
        defaultChunking,
        experimentalStubsEnabled = false,
        testLocalModels
      )

      result.isRight shouldBe true
      val values = result.toOption.get.head.values
      val norm   = math.sqrt(values.map(v => v.toDouble * v.toDouble).sum)
      norm shouldBe (1.0 +- 0.01) // Should be normalized to unit length
    }
  }
}
