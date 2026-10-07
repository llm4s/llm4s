package org.llm4s.rag

import org.llm4s.chunking.{ ChunkerFactory, ChunkingConfig }
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.utils.ChunkingUtils
import org.llm4s.types.{ Result, TryOps }
import org.llm4s.vectorstore._
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.util.Try

/**
 * The snippets of the "Weighted score fusion" and "Invalid chunking settings" parts of `docs/guide/vector-store.md`,
 * compiled and run as written (#1318, items 4 to 6).
 *
 * If a snippet here stops compiling or an assertion fails, the guide is teaching something that no longer works:
 * change the guide and this spec together. Each `snippet` method is the code of one block in the guide; the
 * assertions pin what the surrounding prose claims.
 */
class RagValidationGuideSpec extends AnyWordSpec with Matchers with EitherValues {

  // ---- Weighted score fusion: how the two channels are combined

  private def snippetWeakestHit(): Result[Seq[(String, Double)]] = {
    // Only the vector channel counts (vector weight 1.0), and the keyword channel finds nothing.
    val hits = for {
      store    <- VectorStoreFactory.inMemory()
      keywords <- KeywordIndex.inMemory()
      _        <- store.upsert(VectorRecord("strong", Array(1f, 0f), Some("strong")))
      _        <- store.upsert(VectorRecord("weak", Array(0.6f, 0.8f), Some("weak")))
      found <- HybridSearcher(store, keywords).search(
        Array(1f, 0f),
        "no keyword matches this",
        topK = 5,
        strategy = FusionStrategy.WeightedScore(vectorWeight = 1.0, keywordWeight = 0.0)
      )
    } yield found.map(hit => hit.id -> hit.score)
    hits
  }

  "WeightedScore fusion" should {
    "give a channel's best hit 1.0 and its weakest genuine hit 0.1, not 0.0" in {
      val scores = snippetWeakestHit().value.toMap
      scores("strong") shouldBe 1.0 +- 1e-9
      // 0.1 is the floor (`ScoreNormalisationSpec` pins the constant); a chunk the channel missed would score 0.0.
      scores("weak") shouldBe 0.1 +- 1e-9
      scores("weak") should be > 0.0
    }

    "return the best hit first" in {
      snippetWeakestHit().value.map(_._1) shouldBe Seq("strong", "weak")
    }
  }

  // ---- Weighted score fusion: invalid weights

  private def weightedScore(vector: Double, keyword: Double): Result[FusionStrategy] =
    Try(FusionStrategy.WeightedScore(vector, keyword)).toResult

  "an invalid WeightedScore" should {
    "throw IllegalArgumentException from the constructor, and from RAGConfig.withWeightedScore" in {
      an[IllegalArgumentException] should be thrownBy FusionStrategy.WeightedScore(0.0, 0.0)
      an[IllegalArgumentException] should be thrownBy FusionStrategy.WeightedScore(-0.1, 1.0)
      an[IllegalArgumentException] should be thrownBy FusionStrategy.WeightedScore(Double.NaN, 1.0)
      an[IllegalArgumentException] should be thrownBy FusionStrategy.WeightedScore(Double.PositiveInfinity, 1.0)
      an[IllegalArgumentException] should be thrownBy RAGConfig().withWeightedScore(0.0, 0.0)
    }

    "become a Left when the weights come from user input and are checked with Try" in {
      weightedScore(0.7, 0.3).value shouldBe FusionStrategy.WeightedScore(0.7, 0.3)
      weightedScore(0.0, 0.0).left.value.message should include("At least one weight must be positive")
      weightedScore(Double.NaN, 1.0).isLeft shouldBe true
      weightedScore(Double.PositiveInfinity, 1.0).isLeft shouldBe true
      weightedScore(-1.0, 1.0).isLeft shouldBe true
    }
  }

  // ---- Invalid chunking settings

  private def chunkingConfig(size: Int, overlap: Int): Result[ChunkingConfig] =
    Try(ChunkingConfig(targetSize = size, maxSize = size * 3 / 2, overlap = overlap)).toResult

  "an invalid ChunkingConfig" should {
    "throw IllegalArgumentException naming the rule that failed" in {
      val overlapTooBig = the[IllegalArgumentException] thrownBy ChunkingConfig(targetSize = 800, overlap = 800)
      overlapTooBig.getMessage should include("overlap must be >= 0 and < targetSize")

      val noSize = the[IllegalArgumentException] thrownBy ChunkingConfig(targetSize = 0)
      noSize.getMessage should include("targetSize must be positive")

      val maxBelowTarget = the[IllegalArgumentException] thrownBy ChunkingConfig(targetSize = 800, maxSize = 400)
      maxBelowTarget.getMessage should include("maxSize must be >= targetSize")
    }

    "also throw from RAGConfig.withChunking(strategy, size, overlap), which builds one" in {
      an[IllegalArgumentException] should be thrownBy
        RAGConfig().withChunking(ChunkerFactory.Strategy.Simple, 100, 100)
      RAGConfig().withChunking(ChunkerFactory.Strategy.Simple, 100, 99).chunkingConfig.overlap shouldBe 99
    }

    "become a Left when the sizes come from user input and are checked with Try" in {
      chunkingConfig(800, 150).value.overlap shouldBe 150
      chunkingConfig(800, 800).left.value.message should include("overlap must be >= 0 and < targetSize")
      chunkingConfig(0, 0).left.value.message should include("targetSize must be positive")
    }

    "be reported as a Left(ValidationError) by ChunkingUtils.chunkTextValidated" in {
      val text = "one two three four five six seven eight nine ten"
      ChunkingUtils.chunkTextValidated(text, size = 20, overlap = 5).value should not be empty

      val badOverlap = ChunkingUtils.chunkTextValidated(text, size = 20, overlap = 20).left.value
      badOverlap shouldBe a[ValidationError]
      badOverlap.asInstanceOf[ValidationError].field shouldBe "overlap"

      val badSize = ChunkingUtils.chunkTextValidated(text, 0, 0).left.value
      badSize.asInstanceOf[ValidationError].field shouldBe "size"
    }
  }

  "a chunker" should {
    "accept every configuration ChunkingConfig lets you build, including the boundary" in {
      val text    = "a b c d e f g h i j " * 20
      val chunker = ChunkerFactory.simple()
      // overlap = targetSize - 1 is the largest overlap allowed: each chunk still moves one character forward.
      val chunks = chunker.chunk(text, ChunkingConfig(targetSize = 10, overlap = 9))
      chunks should not be empty
    }
  }
}
