package org.llm4s.rag.loader

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Tests for LoadingConfig configuration behavior.
 */
class LoadingConfigSpec extends AnyFlatSpec with Matchers {

  "LoadingConfig.default" should "have sensible defaults" in {
    val config = LoadingConfig.default
    config.failFast shouldBe false
    config.useHints shouldBe true
    config.skipEmptyDocuments shouldBe true
    config.enableVersioning shouldBe true
    config.parallelism shouldBe 4
    config.batchSize shouldBe 10
  }

  "LoadingConfig.strict" should "enable fail-fast" in {
    LoadingConfig.strict.failFast shouldBe true
  }

  "LoadingConfig.lenient" should "disable fail-fast and skip empty" in {
    val config = LoadingConfig.lenient
    config.failFast shouldBe false
    config.skipEmptyDocuments shouldBe true
  }

  "LoadingConfig.highPerformance" should "have increased parallelism and batch size" in {
    val config = LoadingConfig.highPerformance
    config.parallelism shouldBe 8
    config.batchSize shouldBe 20
  }

  "LoadingConfig.conservative" should "have reduced parallelism and batch size" in {
    val config = LoadingConfig.conservative
    config.parallelism shouldBe 2
    config.batchSize shouldBe 5
  }

  "LoadingConfig fluent API" should "support withFailFast" in {
    LoadingConfig.default.withFailFast.failFast shouldBe true
  }

  it should "support withContinueOnError" in {
    LoadingConfig.strict.withContinueOnError.failFast shouldBe false
  }

  it should "support withHints" in {
    LoadingConfig.default.withHints(false).useHints shouldBe false
    LoadingConfig.default.withHints(true).useHints shouldBe true
  }

  it should "support withSkipEmpty" in {
    LoadingConfig.default.withSkipEmpty(false).skipEmptyDocuments shouldBe false
    LoadingConfig.default.withSkipEmpty(true).skipEmptyDocuments shouldBe true
  }

  it should "support withVersioning" in {
    LoadingConfig.default.withVersioning(false).enableVersioning shouldBe false
    LoadingConfig.default.withVersioning(true).enableVersioning shouldBe true
  }

  it should "support withParallelism" in {
    LoadingConfig.default.withParallelism(16).parallelism shouldBe 16
  }

  it should "clamp parallelism to minimum of 1" in {
    LoadingConfig.default.withParallelism(0).parallelism shouldBe 1
    LoadingConfig.default.withParallelism(-5).parallelism shouldBe 1
  }

  it should "support withBatchSize" in {
    LoadingConfig.default.withBatchSize(50).batchSize shouldBe 50
  }

  it should "clamp batch size to minimum of 1" in {
    LoadingConfig.default.withBatchSize(0).batchSize shouldBe 1
    LoadingConfig.default.withBatchSize(-3).batchSize shouldBe 1
  }

  it should "support chaining" in {
    val config = LoadingConfig.default.withFailFast
      .withHints(false)
      .withSkipEmpty(false)
      .withParallelism(12)
      .withBatchSize(25)

    config.failFast shouldBe true
    config.useHints shouldBe false
    config.skipEmptyDocuments shouldBe false
    config.parallelism shouldBe 12
    config.batchSize shouldBe 25
  }

  it should "not mutate the original config" in {
    val original = LoadingConfig.default
    val modified = original.withFailFast.withParallelism(100)
    original.failFast shouldBe false
    original.parallelism shouldBe 4
    modified.failFast shouldBe true
    modified.parallelism shouldBe 100
  }
}
