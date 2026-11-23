package org.llm4s.llmconnect.caching

import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.{ Clock, Instant, ZoneId }
import scala.concurrent.duration.DurationInt

class InMemoryEmbeddingCacheSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues {

  final private class MovableClock(var now: Instant) extends Clock {
    override def getZone: ZoneId                                    = ZoneId.of("UTC")
    override def withZone(zone: ZoneId): Clock                      = this
    override def instant(): Instant                                 = now
    def advance(by: scala.concurrent.duration.FiniteDuration): Unit = now = now.plusMillis(by.toMillis)
  }

  "InMemoryEmbeddingCache" should "evict the least recently used item when maxSize is reached" in {
    // Set a small maxSize to test eviction
    val cache = InMemoryEmbeddingCache.create(maxSize = 2).value

    cache.put("key1", Seq(1.0))
    cache.put("key2", Seq(2.0))

    // Access key1 to make it "Recently Used"
    cache.get("key1")

    // Adding key3 should evict key2 (the least recently used)
    cache.put("key3", Seq(3.0))

    cache.get("key1") shouldBe Right(Some(Seq(1.0)))
    cache.get("key2") shouldBe Right(None)
    cache.get("key3") shouldBe Right(Some(Seq(3.0)))
    cache.stats().map(_.size) shouldBe Some(2)
  }

  it should "track cache hits correctly using the CacheStats class" in {
    val cache = InMemoryEmbeddingCache.create().value
    cache.put("key1", Seq(1.0)) shouldBe Right(())
    cache.get("key1") // hit
    cache.get("key2") // miss

    val stats = cache.stats().value
    stats.hits should be(1L)
    stats.misses should be(1L)
    stats.totalRequests should be(2L)
    stats.hitRatePercent should be(50.0)
  }

  it should "expire entries after the configured TTL duration, measured with its java.time.Clock" in {
    val clock = new MovableClock(Instant.parse("2024-01-01T00:00:00Z"))
    val cache = InMemoryEmbeddingCache.create(maxSize = 10, ttl = Some(100.millis), clock = clock).value

    cache.put("tempKey", Seq(1.0))

    // Immediate check - should be a hit
    cache.get("tempKey") shouldBe Right(Some(Seq(1.0)))

    // Exactly ttl old is still valid
    clock.advance(100.millis)
    cache.get("tempKey") shouldBe Right(Some(Seq(1.0)))

    // Advance clock past TTL
    clock.advance(1.milli)
    cache.get("tempKey") shouldBe Right(None)

    val stats = cache.stats().value
    stats.hits shouldBe 2L
    stats.misses shouldBe 1L // The expired get counts as a miss
  }

  it should "empty itself and reset its statistics on clear" in {
    val cache = InMemoryEmbeddingCache.create().value
    cache.put("k", Seq(1.0))
    cache.get("k")
    cache.clear() shouldBe Right(())
    cache.stats() shouldBe Some(CacheStats(size = 0, hits = 0L, misses = 0L))
  }

  "InMemoryEmbeddingCache.create" should "refuse a maxSize that is not positive, instead of throwing" in {
    InMemoryEmbeddingCache.create(maxSize = -1).left.value.message should include("maxSize must be positive")
    InMemoryEmbeddingCache.create(maxSize = 0).left.value.message should include("maxSize must be positive")
  }

  it should "refuse a ttl that is not positive" in {
    InMemoryEmbeddingCache.create(ttl = Some(0.seconds)).left.value.message should include("ttl must be positive")
  }

  it should "report every invalid argument at once" in {
    val message = InMemoryEmbeddingCache.create(maxSize = 0, ttl = Some(-1.second)).left.value.message
    message should include("maxSize")
    message should include("ttl")
  }

  "CacheStats" should "derive totalRequests and hitRatePercent from hits and misses" in {
    val stats = CacheStats(size = 1, hits = 1L, misses = 3L)
    stats.totalRequests shouldBe 4L
    stats.hitRatePercent shouldBe 25.0
    CacheStats(0, 0L, 0L).hitRatePercent shouldBe 0.0
    stats.withHits(3L).withMisses(1L).hitRatePercent shouldBe 75.0
  }
}
