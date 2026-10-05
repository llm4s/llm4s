package org.llm4s.llmconnect.auth

import org.llm4s.error.AuthenticationError
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

    "not cache a failure" in {
      val clock = MutableClock(start)
      val calls = new AtomicInteger(0)
      val fetch = () =>
        if calls.incrementAndGet() == 1 then Left(AuthenticationError("token-exchange", "nope"))
        else Right(AccessToken("t2", clock.instant().plusSeconds(600)))
      val provider = CachingAccessTokenProvider(fetch, 1.minute, clock)
      provider.token().isLeft shouldBe true
      provider.token() shouldBe Right("t2")
    }

    "not cache a token that is already expired" in {
      val clock    = MutableClock(start)
      val issuer   = Issuer(clock, 0.seconds)
      val provider = CachingAccessTokenProvider(() => issuer.fetch(), 1.minute, clock)
      provider.token() shouldBe Right("t1")
      provider.token() shouldBe Right("t2")
    }

    "redact the token value in AccessToken.toString" in {
      (AccessToken("secret", start).toString should not).include("secret")
    }
  }
