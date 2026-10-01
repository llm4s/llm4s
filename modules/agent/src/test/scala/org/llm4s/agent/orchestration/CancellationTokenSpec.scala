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
  // whenCancelled
  // ==========================================================================

  "CancellationToken.whenCancelled" should "complete successfully when cancelled" in {
    val token  = CancellationToken()
    val future = token.whenCancelled
    future.isCompleted shouldBe false
    token.cancel()
    Await.result(future, 1.second) shouldBe (())
  }

  it should "already be complete for a token cancelled before it was asked for" in {
    val token = CancellationToken()
    token.cancel()
    Await.result(token.whenCancelled, 1.second) shouldBe (())
  }

  it should "return the same future on every call" in {
    val token = CancellationToken()
    token.whenCancelled shouldBe theSameInstanceAs(token.whenCancelled)
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

  it should "never complete whenCancelled" in {
    val token = CancellationToken.none
    token.cancel()
    token.whenCancelled.isCompleted shouldBe false
  }
}
