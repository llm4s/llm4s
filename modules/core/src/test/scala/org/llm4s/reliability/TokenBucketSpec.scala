package org.llm4s.reliability

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContext, Future }

class TokenBucketSpec extends AnyFlatSpec with Matchers {

  "TokenBucket" should "allow requests up to its burst capacity" in {
    val bucket = new TokenBucket(requestsPerMinute = 60, burstCapacity = 2)
    bucket.tryAcquire() shouldBe true
    bucket.tryAcquire() shouldBe true
    bucket.tryAcquire() shouldBe false
  }

  it should "refill tokens over time" in {
    // 600 RPM = one token every 100ms
    val start  = System.nanoTime()
    var now    = start
    val bucket = new TokenBucket(600, 1, () => now)

    bucket.tryAcquire() shouldBe true
    bucket.tryAcquire() shouldBe false

    now = start + 150_000_000L
    bucket.tryAcquire() shouldBe true
  }

  it should "never refill past its capacity" in {
    val start  = System.nanoTime()
    var now    = start
    val bucket = new TokenBucket(600, 1, () => now)

    now = start + 10.seconds.toNanos
    bucket.tryAcquire() shouldBe true
    bucket.tryAcquire() shouldBe false
  }

  it should "allow at most burstCapacity successes under concurrent contention" in {
    // requestsPerMinute = 0 disables refill, so exactly `burst` tokens can ever be handed out
    val burst  = 20
    val bucket = new TokenBucket(0, burst)

    val pool               = Executors.newFixedThreadPool(40)
    given ExecutionContext = ExecutionContext.fromExecutor(pool)
    val successes          = new AtomicInteger(0)
    val futures = List.fill(200)(Future {
      if (bucket.tryAcquire()) successes.incrementAndGet()
    })
    Await.result(Future.sequence(futures), 10.seconds)
    pool.shutdown()

    successes.get() shouldBe burst
  }
}
