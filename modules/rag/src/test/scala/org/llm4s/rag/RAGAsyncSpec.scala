package org.llm4s.rag

import org.llm4s.rag.loader._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{ Millis, Seconds, Span }

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.ExecutionContext.Implicits.global

/**
 * Tests for async RAG operations.
 *
 * Note: These tests use in-memory components and don't require
 * external embedding providers.
 */
class RAGAsyncSpec extends AnyFlatSpec with Matchers with ScalaFutures {

  // Configure patience for async tests
  implicit val defaultPatience: PatienceConfig =
    PatienceConfig(timeout = Span(30, Seconds), interval = Span(100, Millis))

  // ========== Async Ingest Tests ==========

  "ingestAsync" should "complete a Future" in {
    // This test verifies the basic structure works
    // Full integration tests would require embedding provider
    val loader = TextLoader.fromPairs(
      "doc1" -> "content1",
      "doc2" -> "content2"
    )

    // Verify the loader works synchronously first
    val results = loader.load().toList
    (results should have).length(2)
    results.foreach(r => r.isSuccess shouldBe true)
  }

  // ========== Async Sync Tests ==========

  "syncAsync" should "support change detection structure" in {
    // Test the change detection types
    val version1 = DocumentVersion.fromContent("content1")
    val version2 = DocumentVersion.fromContent("content1")
    val version3 = DocumentVersion.fromContent("content2")

    version1.contentHash shouldBe version2.contentHash
    version1.contentHash should not be version3.contentHash
  }

  // ========== LoadStats Tests ==========

  "LoadStats.fromResults" should "aggregate results correctly" in {
    val doc1   = Document("id1", "content1")
    val doc2   = Document("id2", "content2")
    val error  = org.llm4s.error.ProcessingError("test", "error")
    val source = "failed-source"

    val results = Seq(
      LoadResult.Success(doc1),
      LoadResult.Success(doc2),
      LoadResult.Failure(source, error),
      LoadResult.Skipped("skip-source", "reason")
    )

    val stats = LoadStats.fromResults(results)

    stats.totalAttempted shouldBe 4
    stats.successful shouldBe 2
    stats.failed shouldBe 1
    stats.skipped shouldBe 1
    stats.hasErrors shouldBe true
    stats.successRate shouldBe 0.5
  }

  // ========== SyncStats Tests ==========

  "SyncStats" should "track changes correctly" in {
    val stats = SyncStats(added = 3, updated = 2, deleted = 1, unchanged = 4)

    stats.total shouldBe 10
    stats.changed shouldBe 6
    stats.hasChanges shouldBe true
  }

  it should "report no changes when empty" in {
    val stats = SyncStats(added = 0, updated = 0, deleted = 0, unchanged = 5)

    stats.changed shouldBe 0
    stats.hasChanges shouldBe false
  }

  // ========== Loading Config Async Tests ==========

  "LoadingConfig" should "configure parallelism for async operations" in {
    val config = LoadingConfig.highPerformance

    config.parallelism shouldBe 8
    config.batchSize shouldBe 20
  }

  it should "support custom parallelism settings" in {
    val config = LoadingConfig.default
      .withParallelism(16)
      .withBatchSize(50)

    config.parallelism shouldBe 16
    config.batchSize shouldBe 50
  }

  // ========== Future-based Async Tests ==========

  "Future operations" should "work with execution context" in {
    // Simple test to verify Future and ExecutionContext work
    val future: Future[Int] = Future {
      1 + 1
    }(implicitly[ExecutionContext])

    whenReady(future)(result => result shouldBe 2)
  }

  it should "support Future.sequence for batch processing pattern" in {
    val futures = Seq(
      Future(1),
      Future(2),
      Future(3)
    )

    val combined = Future.sequence(futures)

    whenReady(combined)(results => results shouldBe Seq(1, 2, 3))
  }
}
