package org.llm4s.samples.embeddingsupport

import org.llm4s.llmconnect.config.LocalEmbeddingModels
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import pureconfig.ConfigSource

/**
 * The embeddings sample's settings, which were `Llm4sConfig.embeddingsChunking()`,
 * `embeddingsInputs()`, `embeddingsUi()`, `experimentalStubsEnabled` and
 * `localEmbeddingModels()` in `llm4s-core` until #2265. Their specs
 * (`Llm4sConfigEmbeddingsInputsSpec`, `Llm4sConfigEmbeddingsChunkingSpec`, `Llm4sConfigFacadeSpec`
 * and the local-model cases of `Llm4sConfigEmbeddingsSpec` / `EmbeddingsConfigLoaderSpec`) moved
 * here with them.
 */
class EmbeddingSampleConfigSpec extends AnyWordSpec with Matchers with EitherValues {

  private def src(hocon: String): ConfigSource = ConfigSource.string(hocon)

  private val noConfig = src("")

  "EmbeddingSampleConfig.chunking" should {
    "fall back to defaults when no chunking config is provided" in {
      EmbeddingSampleConfig.chunking(noConfig).value shouldBe EmbeddingChunkingSettings(
        enabled = true,
        size = 1000,
        overlap = 100
      )
    }

    "respect llm4s.samples.embeddings.chunking overrides" in {
      val source = src("llm4s.samples.embeddings.chunking { size = 2048, overlap = 256, enabled = false }")
      EmbeddingSampleConfig.chunking(source).value shouldBe EmbeddingChunkingSettings(
        enabled = false,
        size = 2048,
        overlap = 256
      )
    }

    "fill unset keys from the defaults" in {
      val source = src("llm4s.samples.embeddings.chunking.size = 500")
      EmbeddingSampleConfig.chunking(source).value shouldBe EmbeddingChunkingSettings(size = 500)
    }

    "reject a value of the wrong type rather than silently defaulting" in {
      val error = EmbeddingSampleConfig.chunking(src("llm4s.samples.embeddings.chunking.size = abc")).left.value
      error.message should include("llm4s.samples.embeddings.chunking.size")
    }

    "no longer read core's old llm4s.embeddings.chunking keys" in {
      val source = src("llm4s.embeddings.chunking.size = 7")
      EmbeddingSampleConfig.chunking(source).value.size shouldBe 1000
    }
  }

  "EmbeddingSampleConfig.inputs" should {
    "return None values when no input/query config is provided" in {
      EmbeddingSampleConfig.inputs(noConfig).value shouldBe EmbeddingInputSettings(None, None, None)
    }

    "respect inputPath and query" in {
      val source = src("""llm4s.samples.embeddings { inputPath = "/single/path.txt", query = "test query" }""")
      EmbeddingSampleConfig.inputs(source).value shouldBe EmbeddingInputSettings(
        inputPath = Some("/single/path.txt"),
        inputPaths = None,
        query = Some("test query")
      )
    }

    "respect inputPaths and trim values, treating blank ones as unset" in {
      val source = src("""llm4s.samples.embeddings { inputPaths = " /a.txt,/b.txt ", query = "   " }""")
      EmbeddingSampleConfig.inputs(source).value shouldBe EmbeddingInputSettings(
        inputPath = None,
        inputPaths = Some("/a.txt,/b.txt"),
        query = None
      )
    }

    "reject an input that is not a string" in {
      val error = EmbeddingSampleConfig.inputs(src("llm4s.samples.embeddings.query { a = 1 }")).left.value
      error.message should include("llm4s.samples.embeddings.query")
    }
  }

  "EmbeddingSampleConfig.ui" should {
    "fall back to defaults when no config" in {
      val ui = EmbeddingSampleConfig.ui(noConfig).value
      ui.maxRowsPerFile shouldBe 200
      ui.topDimsPerRow shouldBe 6
      ui.globalTopK shouldBe 10
      ui.showGlobalTop shouldBe false
      ui.colorEnabled shouldBe true
      ui.tableWidth shouldBe 120
    }

    "respect every ui key" in {
      val source = src(
        """llm4s.samples.embeddings.ui {
          |  maxRowsPerFile = 5, topDimsPerRow = 3, globalTopK = 2
          |  showGlobalTop = true, colorEnabled = false, tableWidth = 80
          |}""".stripMargin
      )
      EmbeddingSampleConfig.ui(source).value shouldBe EmbeddingUiSettings(
        maxRowsPerFile = 5,
        topDimsPerRow = 3,
        globalTopK = 2,
        showGlobalTop = true,
        colorEnabled = false,
        tableWidth = 80
      )
    }
  }

  "EmbeddingSampleConfig.experimentalStubs" should {
    "default to false when not configured" in {
      EmbeddingSampleConfig.experimentalStubs(noConfig).value shouldBe false
    }

    "return true when configured" in {
      EmbeddingSampleConfig
        .experimentalStubs(src("llm4s.samples.embeddings.experimentalStubs = true"))
        .value shouldBe true
    }
  }

  "EmbeddingSampleConfig.localModels" should {
    "load local model names" in {
      val source = src(
        """llm4s.samples.embeddings.localModels {
          |  imageModel = "custom-image-model"
          |  audioModel = "custom-audio-model"
          |  videoModel = "custom-video-model"
          |}""".stripMargin
      )
      EmbeddingSampleConfig.localModels(source).value shouldBe LocalEmbeddingModels(
        imageModel = "custom-image-model",
        audioModel = "custom-audio-model",
        videoModel = "custom-video-model"
      )
    }

    "default each missing model name" in {
      val source = src("""llm4s.samples.embeddings.localModels.imageModel = "clip"""")
      EmbeddingSampleConfig.localModels(source).value shouldBe
        EmbeddingSampleConfig.DefaultLocalModels.withImageModel("clip")
    }

    "use the defaults when the section is missing" in {
      EmbeddingSampleConfig.localModels(noConfig).value shouldBe LocalEmbeddingModels(
        imageModel = "openclip-vit-b32",
        audioModel = "wav2vec2-base",
        videoModel = "timesformer-base"
      )
    }
  }

  "the samples' application.conf" should {
    "parse, and with no variables set give every setting its default" in {
      val source = ConfigSource.resources("application.conf")
      EmbeddingSampleConfig.chunking(source).value shouldBe EmbeddingChunkingSettings()
      EmbeddingSampleConfig.ui(source).value shouldBe EmbeddingUiSettings()
      EmbeddingSampleConfig.experimentalStubs(source).value shouldBe false
      EmbeddingSampleConfig.localModels(source).value shouldBe EmbeddingSampleConfig.DefaultLocalModels
    }
  }

  "a source that cannot be parsed" should {
    "be a ConfigurationError" in {
      val error = EmbeddingSampleConfig.inputs(src("llm4s { ")).left.value
      error.message should include("Failed to load llm4s.samples.embeddings")
    }
  }

  "EmbeddingQuery.loadFromEnv" should {
    "read through EmbeddingSampleConfig without failing on the default config" in {
      EmbeddingQuery.loadFromEnv().isRight shouldBe true
    }
  }
}
