package org.llm4s.security

import org.llm4s.util.Redaction
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class TokenRotationSpec extends AnyFlatSpec with Matchers {
  "TokenGenerations" should "mask the current token and the one it just replaced" in KnownSecrets.isolated {
    val g = new TokenGenerations
    g.rotate("ya29.first-token-0001")
    g.rotate("ya29.second-token-0002")
    (Redaction.safeBody("late body echoing ya29.first-token-0001") should not).include("first-token-0001")
    (Redaction.safeBody("body echoing ya29.second-token-0002") should not).include("second-token-0002")
  }

  it should "forget the generation before the previous one" in KnownSecrets.isolated {
    val g = new TokenGenerations
    g.rotate("ya29.first-token-0001"); g.rotate("ya29.second-token-0002"); g.rotate("ya29.third-token-0003")
    KnownSecrets.scrub("ya29.first-token-0001", "[REDACTED]") shouldBe "ya29.first-token-0001"
  }

  it should "keep a token repeated across rotations" in KnownSecrets.isolated {
    val g = new TokenGenerations
    g.rotate("ya29.first-token-0001"); g.rotate("ya29.first-token-0001"); g.rotate("ya29.second-token-0002")
    KnownSecrets.scrub("ya29.first-token-0001", "[REDACTED]") shouldBe "[REDACTED]"
    g.rotate("ya29.first-token-0001") // A, B, A: A is current again and must stay registered
    KnownSecrets.scrub("ya29.first-token-0001", "[REDACTED]") shouldBe "[REDACTED]"
  }

  it should "have registered a token before any concurrent rotate of it returns" in KnownSecrets.isolated {
    // Each round: eight requests obtain the same new token at once; each must see it registered on return.
    val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
    (0 until 200).foreach { i =>
      val g     = new TokenGenerations
      val t     = f"ya29.concurrent-$i%06d"
      val start = new java.util.concurrent.CountDownLatch(1)
      val tasks = (0 until 8).map { _ =>
        pool.submit { () =>
          start.await()
          g.rotate(t)
          KnownSecrets.scrub(t, "[REDACTED]") shouldBe "[REDACTED]"
        }
      }
      start.countDown()
      tasks.foreach(_.get(30, java.util.concurrent.TimeUnit.SECONDS))
    }
    pool.shutdown()
  }

  it should "keep a token another source still holds" in KnownSecrets.isolated {
    val g1 = new TokenGenerations
    val g2 = new TokenGenerations
    g1.rotate("ya29.shared-token-0001"); g2.rotate("ya29.shared-token-0001")
    g1.rotate("ya29.g1-second-0002"); g1.rotate("ya29.g1-third-0003")
    KnownSecrets.scrub("ya29.shared-token-0001", "[REDACTED]") shouldBe "[REDACTED]"
  }
}
