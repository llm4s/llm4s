package org.llm4s.llmconnect.caching
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.Future
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global

class InMemoryEmbeddingCacheSpec extends AnyFlatSpec with Matchers {

  "InMemoryEmbeddingCache" should "store and retrieve embeddings" in {
    val cache = new InMemoryEmbeddingCache[String]()
    cache.put("key1", "embedding1")
    cache.get("key1") should be(Some("embedding1"))
  }

  it should "return None for non-existent keys" in {
    val cache = new InMemoryEmbeddingCache[String]()
    cache.get("nonexistent") should be(None)
  }

  it should "overwrite existing keys" in {
    val cache = new InMemoryEmbeddingCache[String]()
    cache.put("key1", "value1")
    cache.put("key1", "value2")
    cache.get("key1") should be(Some("value2"))
  }

  it should "track cache hits correctly" in {
    val cache = new InMemoryEmbeddingCache[String]()
    cache.put("key1", "value1")
    cache.get("key1") // hit
    cache.get("key1") // hit
    cache.get("key2") // miss

    val stats = cache.stats()
    stats("hits").asInstanceOf[Long] should be(2L)
    stats("misses").asInstanceOf[Long] should be(1L)
  }

  it should "calculate hit rate correctly" in {
    val cache = new InMemoryEmbeddingCache[String]()
    cache.put("key1", "value1")
    cache.get("key1") // hit
    cache.get("key1") // hit
    cache.get("key2") // miss
    cache.get("key3") // miss

    val stats   = cache.stats()
    val hitRate = stats("hit_rate_percent").toString.toDouble
    hitRate should be(50.0 +- 0.1)
  }

  it should "return correct cache size" in {
    val cache = new InMemoryEmbeddingCache[String]()
    cache.put("key1", "value1")
    cache.put("key2", "value2")
    cache.put("key3", "value3")

    cache.stats()("size").asInstanceOf[Int] should be(3)
  }

  it should "clear cache completely" in {
    val cache = new InMemoryEmbeddingCache[String]()
    cache.put("key1", "value1")
    cache.put("key2", "value2")

    cache.clear()
    cache.get("key1") should be(None)

    val stats = cache.stats()
    stats("size").asInstanceOf[Int] should be(0)
    stats("hits").asInstanceOf[Long] should be(0L)
    stats("misses").asInstanceOf[Long] should be(1L)
  }

  it should "be thread-safe for concurrent puts" in {
    val cache      = new InMemoryEmbeddingCache[Int]()
    val iterations = 1000

    val futures = (0 until 10).map { threadId =>
      Future {
        for (i <- 0 until iterations)
          cache.put(s"key-$threadId-$i", threadId * 1000 + i)
      }
    }

    Await.result(
      Future.sequence(futures),
      scala.concurrent.duration.Duration.Inf
    )

    cache.stats()("size").asInstanceOf[Int] should be(10 * iterations)
  }

  it should "be thread-safe for concurrent gets" in {
    val cache = new InMemoryEmbeddingCache[String]()
    cache.put("key1", "value1")

    val futures = (0 until 10).map { _ =>
      Future {
        for (_ <- 0 until 100)
          cache.get("key1")
      }
    }

    Await.result(
      Future.sequence(futures),
      scala.concurrent.duration.Duration.Inf
    )

    cache.stats()("hits").asInstanceOf[Long] should be >= 900L
  }

  it should "handle mixed workloads (gets and puts)" in {
    val cache = new InMemoryEmbeddingCache[String]()

    val futures = (0 until 5).map { threadId =>
      Future {
        for (i <- 0 until 200)
          if (i % 2 == 0) {
            cache.put(s"key-$threadId", s"value-$i")
          } else {
            cache.get(s"key-$threadId")
          }
      }
    }

    Await.result(
      Future.sequence(futures),
      scala.concurrent.duration.Duration.Inf
    )

    val stats = cache.stats()
    stats("total_requests").asInstanceOf[Long] should be > 0L
  }

}
