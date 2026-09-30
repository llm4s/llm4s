package org.llm4s.agent.orchestration

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Await
import scala.concurrent.duration._

class CancellationTokenSpec extends AnyFlatSpec with Matchers {

  // ==========================================================================
  // Basic operations
  // ==========================================================================

  "CancellationToken" should "start as not cancelled" in {
    val token = CancellationToken()
    token.isCancelled shouldBe false
  }

  it should "be cancellable" in {
    val token = CancellationToken()
    token.cancel()
    token.isCancelled shouldBe true
  }

  it should "be idempotent on cancel" in {
    val token = CancellationToken()
    token.cancel()
    token.cancel()
    token.isCancelled shouldBe true
  }

  // ==========================================================================
  // Callbacks
  // ==========================================================================

  "CancellationToken.onCancel" should "execute callback on cancel" in {
    val token    = CancellationToken()
    val executed = new AtomicInteger(0)
    token.onCancel(executed.incrementAndGet())
    executed.get() shouldBe 0

    token.cancel()
    executed.get() shouldBe 1
  }

  it should "execute multiple callbacks" in {
    val token   = CancellationToken()
    val counter = new AtomicInteger(0)
    token.onCancel(counter.incrementAndGet())
    token.onCancel(counter.incrementAndGet())
    token.onCancel(counter.incrementAndGet())

    token.cancel()
    counter.get() shouldBe 3
  }

  it should "execute callback immediately if already cancelled" in {
    val token = CancellationToken()
    token.cancel()

    val executed = new AtomicInteger(0)
    token.onCancel(executed.incrementAndGet())
    executed.get() shouldBe 1
  }

  it should "not re-execute callbacks on second cancel" in {
    val token   = CancellationToken()
    val counter = new AtomicInteger(0)
    token.onCancel(counter.incrementAndGet())

    token.cancel()
    counter.get() shouldBe 1

    token.cancel()
    counter.get() shouldBe 1
  }

  // ==========================================================================
  // throwIfCancelled
  // ==========================================================================

  "CancellationToken.throwIfCancelled" should "not throw when not cancelled" in {
    val token = CancellationToken()
    noException should be thrownBy token.throwIfCancelled()
  }

  it should "throw CancellationException when cancelled" in {
    val token = CancellationToken()
    token.cancel()
    a[CancellationException] should be thrownBy token.throwIfCancelled()
  }

  // ==========================================================================
  // cancellationFuture
  // ==========================================================================

  "CancellationToken.cancellationFuture" should "fail when cancelled" in {
    val token  = CancellationToken()
    val future = token.cancellationFuture
    token.cancel()

    val thrown = intercept[CancellationException] {
      Await.result(future, 1.second)
    }
    thrown.getMessage should include("cancelled")
  }

  // ==========================================================================
  // cachedCancellationFuture
  // ==========================================================================

  "CancellationToken.cachedCancellationFuture" should "return same future on multiple calls" in {
    val token = CancellationToken()
    val f1    = token.cachedCancellationFuture
    val f2    = token.cachedCancellationFuture
    f1 shouldBe theSameInstanceAs(f2)
  }

  // ==========================================================================
  // CancellationToken.none
  // ==========================================================================

  "CancellationToken.none" should "never be cancelled" in {
    val token = CancellationToken.none
    token.isCancelled shouldBe false
    token.cancel()
    token.isCancelled shouldBe false
  }

  it should "not throw on throwIfCancelled" in {
    noException should be thrownBy CancellationToken.none.throwIfCancelled()
  }

  // ==========================================================================
  // CancellationException
  // ==========================================================================

  "CancellationException" should "carry a message" in {
    val ex = new CancellationException("test cancelled")
    ex.getMessage shouldBe "test cancelled"
    ex shouldBe a[RuntimeException]
  }
}
