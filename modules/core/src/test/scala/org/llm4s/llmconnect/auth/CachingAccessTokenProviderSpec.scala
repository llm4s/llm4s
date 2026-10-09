package org.llm4s.llmconnect.auth

import org.llm4s.error.{ AuthenticationError, CancelledError, NetworkError }
import org.llm4s.types.Result
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant
import java.util.concurrent.{ Callable, Executors }
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

class CachingAccessTokenProviderSpec extends AnyWordSpec with Matchers:

  private val start = Instant.parse("2026-10-04T12:00:00Z")

  /** Issues t1, t2, ... each living `lifetime` from the clock's now. */
  final private class Issuer(clock: MutableClock, lifetime: FiniteDuration):
    val calls = new AtomicInteger(0)
    def fetch(): Result[AccessToken] =
      val n = calls.incrementAndGet()
      Right(AccessToken(s"t$n", clock.instant().plusMillis(lifetime.toMillis)))

  "CachingAccessTokenProvider" should {
    "reuse the token until the refresh margin, then refresh" in {
      val clock    = MutableClock(start)
      val issuer   = Issuer(clock, 10.minutes)
      val provider = CachingAccessTokenProvider(() => issuer.fetch(), 1.minute, clock)
      provider.token() shouldBe Right("t1")
      clock.advance(8.minutes)
      provider.token() shouldBe Right("t1")
      clock.advance(1.minute + 1.second)
      provider.token() shouldBe Right("t2")
      issuer.calls.get shouldBe 2
    }

    "refreshes at half-life when the token lives shorter than the margin" in {
      val clock    = MutableClock(start)
      val issuer   = Issuer(clock, 30.seconds)
      val provider = CachingAccessTokenProvider(() => issuer.fetch(), 60.seconds, clock)
      provider.token() shouldBe Right("t1")
      clock.advance(14.seconds)
      provider.token() shouldBe Right("t1")
      clock.advance(2.seconds)
      provider.token() shouldBe Right("t2")
    }

    "make one fetch for 20 concurrent callers" in {
      val clock = MutableClock(start)
      val calls = new AtomicInteger(0)
      val fetch = () => {
        calls.incrementAndGet()
        Thread.sleep(50)
        Right(AccessToken("t1", clock.instant().plusSeconds(600)))
      }
      val provider = CachingAccessTokenProvider(fetch, 1.minute, clock)
      val pool     = Executors.newVirtualThreadPerTaskExecutor()
      val tasks    = (1 to 20).map(_ => (() => provider.token()): Callable[Result[String]])
      val results  = pool.invokeAll(tasks.asJava).asScala.map(_.get())
      pool.shutdown()
      results.distinct shouldBe Seq(Right("t1"))
      calls.get shouldBe 1
    }

    "drop the cached token only when it is the rejected one" in {
      val clock    = MutableClock(start)
      val issuer   = Issuer(clock, 10.minutes)
      val provider = CachingAccessTokenProvider(() => issuer.fetch(), 1.minute, clock)
      provider.token() shouldBe Right("t1")
      provider.invalidate("t1")
      provider.token() shouldBe Right("t2")
      provider.invalidate("t1") // stale: a slower caller's 401 for the old token
      provider.token() shouldBe Right("t2")
      issuer.calls.get shouldBe 2
    }

    "share a failure for a brief window, then try again" in {
      val clock = MutableClock(start)
      val calls = new AtomicInteger(0)
      val fetch = () =>
        if calls.incrementAndGet() == 1 then Left(AuthenticationError("token-exchange", "nope"))
        else Right(AccessToken("t2", clock.instant().plusSeconds(600)))
      val provider = CachingAccessTokenProvider(fetch, 1.minute, clock)
      provider.token().isLeft shouldBe true
      provider.token().isLeft shouldBe true // inside the window: the same failure, no second fetch
      calls.get shouldBe 1
      clock.advance(CachingAccessTokenProvider.FailureTtl + 1.second)
      provider.token() shouldBe Right("t2")
      calls.get shouldBe 2
    }

    "make one fetch, not one per caller, for 20 concurrent callers during an outage" in {
      val clock = MutableClock(start)
      val calls = new AtomicInteger(0)
      val fetch = () => {
        calls.incrementAndGet()
        Thread.sleep(50) // an endpoint that takes its time to fail: the others queue behind the lock
        Left(AuthenticationError("token-exchange", "down"))
      }
      val provider = CachingAccessTokenProvider(fetch, 1.minute, clock)
      val pool     = Executors.newVirtualThreadPerTaskExecutor()
      val tasks    = (1 to 20).map(_ => (() => provider.token()): Callable[Result[String]])
      val results  = pool.invokeAll(tasks.asJava).asScala.map(_.get())
      pool.shutdown()
      results.count(_.isLeft) shouldBe 20
      calls.get shouldBe 1
    }

    "forget a shared failure once a fetch succeeds, and when a token is rejected" in {
      val clock = MutableClock(start)
      val calls = new AtomicInteger(0)
      val fetch = () =>
        calls.incrementAndGet() match
          case 1 => Left(AuthenticationError("token-exchange", "nope"))
          case n => Right(AccessToken(s"t${n - 1}", clock.instant().plusSeconds(600)))
      val provider = CachingAccessTokenProvider(fetch, 1.minute, clock)
      provider.token().isLeft shouldBe true
      clock.advance(CachingAccessTokenProvider.FailureTtl + 1.second)
      provider.token() shouldBe Right("t1")
      provider.invalidate("t1")
      provider.token() shouldBe Right("t2") // a rejection forces a fresh fetch at once
    }

    "not cache a token that is already expired" in {
      val clock    = MutableClock(start)
      val issuer   = Issuer(clock, 0.seconds)
      val provider = CachingAccessTokenProvider(() => issuer.fetch(), 1.minute, clock)
      provider.token() shouldBe Right("t1")
      provider.token() shouldBe Right("t2")
    }

    "return CancelledError, with the interrupt flag set again, to a caller interrupted while it waits for the lock" in {
      val clock   = MutableClock(start)
      val entered = new java.util.concurrent.CountDownLatch(1)
      val release = new java.util.concurrent.CountDownLatch(1)
      val fetch = () => {
        entered.countDown()
        release.await()
        Right(AccessToken("t1", clock.instant().plusSeconds(600)))
      }
      val provider = CachingAccessTokenProvider(fetch, 1.minute, clock)
      val holder   = Thread.ofVirtual().start(() => provider.token(): Unit)
      entered.await()
      val outcome = new java.util.concurrent.atomic.AtomicReference[(Result[String], Boolean)]()
      val waiter =
        Thread.ofVirtual().start(() => outcome.set(provider.token() -> Thread.currentThread().isInterrupted))
      // Wait until the waiter is parked on the lock, then cancel it.
      while waiter.getState != Thread.State.WAITING do Thread.onSpinWait()
      waiter.interrupt()
      waiter.join(5000)
      val (result, interrupted) = outcome.get
      result.left.toOption.get shouldBe an[org.llm4s.error.CancelledError]
      interrupted shouldBe true
      release.countDown()
      holder.join(5000)
      provider.token() shouldBe Right("t1")
    }

    "leave the cache alone, with the interrupt flag set, when invalidate is interrupted" in {
      val clock    = MutableClock(start)
      val issuer   = Issuer(clock, 10.minutes)
      val provider = CachingAccessTokenProvider(() => issuer.fetch(), 1.minute, clock)
      provider.token() shouldBe Right("t1")
      Thread.currentThread().interrupt()
      provider.invalidate("t1")
      Thread.interrupted() shouldBe true
      provider.token() shouldBe Right("t1")
    }

    "return a cancelled fetch to its caller alone, so a waiter and a later caller fetch afresh" in {
      val clock   = MutableClock(start)
      val calls   = new AtomicInteger(0)
      val entered = new java.util.concurrent.CountDownLatch(1)
      val release = new java.util.concurrent.CountDownLatch(1)
      // The first fetch behaves as the HTTP layer does when its thread is interrupted: it returns
      // CancelledError and keeps the flag. Later fetches succeed.
      val fetch = () =>
        calls.incrementAndGet() match
          case 1 =>
            entered.countDown()
            CancelledError.catchInterrupt(release.await()) match
              case Left(e) =>
                Thread.currentThread().interrupt()
                Left(CancelledError("http.POST", Some(e)))
              case Right(()) => Right(AccessToken("t1", clock.instant().plusSeconds(600)))
          case n => Right(AccessToken(s"t$n", clock.instant().plusSeconds(600)))
      val provider = CachingAccessTokenProvider(fetch, 1.minute, clock)
      val outcomeA = new java.util.concurrent.atomic.AtomicReference[(Result[String], Boolean)]()
      val outcomeB = new java.util.concurrent.atomic.AtomicReference[(Result[String], Boolean)]()
      val callerA =
        Thread.ofVirtual().start(() => outcomeA.set(provider.token() -> Thread.currentThread().isInterrupted))
      entered.await()
      val callerB =
        Thread.ofVirtual().start(() => outcomeB.set(provider.token() -> Thread.currentThread().isInterrupted))
      while callerB.getState != Thread.State.WAITING do Thread.onSpinWait() // B is parked behind A's fetch
      callerA.interrupt()
      callerA.join(5000)
      callerB.join(5000)
      val (resultA, interruptedA) = outcomeA.get
      resultA.left.toOption.get shouldBe a[CancelledError]
      interruptedA shouldBe true
      outcomeB.get shouldBe (Right("t2") -> false) // its own fetch, not A's cancellation
      provider.token() shouldBe Right("t2") // C: the token B fetched, no cached cancellation
      calls.get shouldBe 2
    }

    "treat an InterruptedException thrown by the fetch as a cancellation, and cache nothing" in {
      val clock = MutableClock(start)
      val calls = new AtomicInteger(0)
      val fetch = () =>
        if calls.incrementAndGet() == 1 then throw new InterruptedException("cancelled")
        else Right(AccessToken("t2", clock.instant().plusSeconds(600)))
      val provider = CachingAccessTokenProvider(fetch, 1.minute, clock)
      val result   = provider.token()
      Thread.interrupted() shouldBe true // the flag is set again, and cleared here for what follows
      result.left.toOption.get shouldBe a[CancelledError]
      provider.token() shouldBe Right("t2")
      calls.get shouldBe 2
    }

    "treat a failure that ends while the caller is interrupted as a cancellation, and cache nothing" in {
      val clock = MutableClock(start)
      val calls = new AtomicInteger(0)
      val fetch = () =>
        if calls.incrementAndGet() == 1 then
          // On a virtual thread an interrupted socket read surfaces as a plain network error.
          Thread.currentThread().interrupt()
          Left(NetworkError("socket closed", None, "token-exchange"))
        else Right(AccessToken("t2", clock.instant().plusSeconds(600)))
      val provider = CachingAccessTokenProvider(fetch, 1.minute, clock)
      val result   = provider.token()
      Thread.interrupted() shouldBe true
      result.left.toOption.get shouldBe a[CancelledError]
      provider.token() shouldBe Right("t2")
      calls.get shouldBe 2
    }

    "share a transient failure for a shorter window than a rejection" in {
      val clock = MutableClock(start)
      val calls = new AtomicInteger(0)
      val fetch = () =>
        if calls.incrementAndGet() == 1 then Left(NetworkError("connection refused", None, "token-exchange"))
        else Right(AccessToken("t2", clock.instant().plusSeconds(600)))
      val provider = CachingAccessTokenProvider(fetch, 1.minute, clock)
      CachingAccessTokenProvider.TransientFailureTtl should be < CachingAccessTokenProvider.FailureTtl
      provider.token().left.toOption.get shouldBe a[NetworkError]
      provider.token().left.toOption.get shouldBe a[NetworkError] // inside the window: shared
      calls.get shouldBe 1
      clock.advance(CachingAccessTokenProvider.TransientFailureTtl + 1.millis)
      provider.token() shouldBe Right("t2")
      calls.get shouldBe 2
    }

    "redact the token value in AccessToken.toString" in {
      (AccessToken("secret", start).toString should not).include("secret")
    }
  }
