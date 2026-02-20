package org.llm4s.llmconnect.caching

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class InMemoryEmbeddingCacheSpec extends AnyFlatSpec with Matchers {

  "InMemoryEmbeddingCache" should "evict the least recently used item when maxSize is reached" in {
    // Set a small maxSize to test eviction
    val cache = new InMemoryEmbeddingCache[String](maxSize = 2)

    cache.put("key1", "val1")
    cache.put("key2", "val2")

    // Access key1 to make it "Recently Used"
    cache.get("key1")

    // Adding key3 should evict key2 (the least recently used)
    cache.put("key3", "val3")

    cache.get("key1") shouldBe Some("val1")
    cache.get("key2") shouldBe None
    cache.get("key3") shouldBe Some("val3")
    cache.stats().size shouldBe 2
  }

  it should "track cache hits correctly using the CacheStats class" in {
    val cache = new InMemoryEmbeddingCache[String]()
    cache.put("key1", "value1")
    cache.get("key1") // hit
    cache.get("key2") // miss

    val stats = cache.stats()
    // stats("hits") becomes stats.hits
    stats.hits should be(1L)
    stats.misses should be(1L)
    stats.totalRequests should be(2L)
  }
}
