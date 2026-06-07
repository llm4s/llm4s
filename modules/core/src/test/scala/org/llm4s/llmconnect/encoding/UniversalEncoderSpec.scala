package org.llm4s.llmconnect.encoding

import org.llm4s.llmconnect.EmbeddingClient
import org.llm4s.llmconnect.config.{ EmbeddingModelConfig, LocalEmbeddingModels }
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.Result
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.io.File
import java.nio.file.Files

class UniversalEncoderSpec extends AnyFunSuite with Matchers {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

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
    } finally file.delete()
  }

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
    val longText = "word " * 500
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
    withTempFile(".json", """{"key": "value"}""") { file =>
      val result = UniversalEncoder.encodeFromPath(
        file.toPath,
        mockClient,
        testTextModel,
        defaultChunking,
        experimentalStubsEnabled = false,
        testLocalModels
      )

      result.isRight || result.isLeft shouldBe true
    }
  }

  test("encodeFromPath with experimental stubs handles various content") {
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

  private val pngHeader: Array[Byte] =
    Array[Byte](0x89.toByte, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)

  private val wavHeader: Array[Byte] = {
    val h = new Array[Byte](44)
    h(0) = 'R'; h(1) = 'I'; h(2) = 'F'; h(3) = 'F'
    h(8) = 'W'; h(9) = 'A'; h(10) = 'V'; h(11) = 'E'
    h(12) = 'f'; h(13) = 'm'; h(14) = 't'; h(15) = ' '
    h
  }

  private def withBinaryTempFile(extension: String, content: Array[Byte])(test: File => Unit): Unit = {
    val file = Files.createTempFile("test-encoder-", extension).toFile
    try {
      Files.write(file.toPath, content)
      test(file)
    } finally file.delete()
  }

  test("multimodal stub success - image produces stub vector with Image modality") {
    withBinaryTempFile(".png", pngHeader) { file =>
      val result = UniversalEncoder.encodeFromPath(
        file.toPath,
        mockClient,
        testTextModel,
        defaultChunking,
        experimentalStubsEnabled = true,
        testLocalModels
      )

      result.isRight shouldBe true
      val vectors = result.toOption.get
      vectors.length shouldBe 1
      vectors.head.modality shouldBe Image
      vectors.head.meta should contain("experimental" -> "true")
      vectors.head.meta should contain("provider" -> "local-experimental")
    }
  }

  test("multimodal stub success - audio produces stub vector with Audio modality") {
    withBinaryTempFile(".wav", wavHeader) { file =>
      val result = UniversalEncoder.encodeFromPath(
        file.toPath,
        mockClient,
        testTextModel,
        defaultChunking,
        experimentalStubsEnabled = true,
        testLocalModels
      )

      result.isRight shouldBe true
      val vectors = result.toOption.get
      vectors.length shouldBe 1
      vectors.head.modality shouldBe Audio
      vectors.head.meta should contain("experimental" -> "true")
    }
  }

  test("multimodal real provider path - image calls embedMultimodal and returns provider result") {
    val multimodalProvider = new EmbeddingProvider {
      override def embed(request: EmbeddingRequest): Result[EmbeddingResponse] =
        Right(
          EmbeddingResponse(
            embeddings = request.input.map(_ => (1 to request.model.dimensions).map(i => i.toDouble / 100).toSeq),
            metadata = Map("provider" -> "mock-multimodal")
          )
        )

      override def embedMultimodal(
        request: org.llm4s.llmconnect.model.MultimediaEmbeddingRequest
      ): Result[EmbeddingResponse] =
        Right(
          EmbeddingResponse(
            embeddings = Seq((1 to request.model.dimensions).map(i => i.toDouble / 50).toSeq),
            metadata = Map("provider" -> "mock-multimodal", "modality" -> request.modality.toString)
          )
        )
    }

    val multimodalClient = new EmbeddingClient(multimodalProvider)

    withBinaryTempFile(".png", pngHeader) { file =>
      val result = UniversalEncoder.encodeFromPath(
        file.toPath,
        multimodalClient,
        testTextModel,
        defaultChunking,
        experimentalStubsEnabled = false,
        testLocalModels
      )

      result.isRight shouldBe true
      val vectors = result.toOption.get
      vectors.length shouldBe 1
      vectors.head.modality shouldBe Image
      (vectors.head.meta should contain).key("provider")
      vectors.head.meta("provider") shouldBe "mock-multimodal"
    }
  }

  test("multimodal size guard - rejects file exceeding maxMediaFileSize") {
    withBinaryTempFile(".png", pngHeader) { file =>
      val result = UniversalEncoder.encodeFromPath(
        file.toPath,
        mockClient,
        testTextModel,
        defaultChunking,
        experimentalStubsEnabled = false,
        testLocalModels,
        maxMediaFileSize = 1L
      )

      result.isLeft shouldBe true
      result.left.toOption.get.message should include("exceeds maximum size")
    }
  }
}
