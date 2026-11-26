package org.llm4s.shared

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** The runner's handshake check (#3046): a token, in constant time, and never a browser's handshake. */
class RunnerHandshakeSpec extends AnyFlatSpec with Matchers {

  private val token = RunnerHandshake.newToken()

  "RunnerHandshake.newToken" should "make a fresh token long enough for the runner each time" in {
    val tokens = Seq.fill(50)(RunnerHandshake.newToken())
    tokens.distinct should have size 50
    tokens.map(t => RunnerHandshake.tokenFrom(Some(t))) shouldBe tokens.map(Right(_))
    all(tokens.map(_.length)) should be >= RunnerHandshake.MinTokenLength
  }

  "RunnerHandshake.check" should "accept the token as a bearer token, whatever the header name's case" in {
    RunnerHandshake.check(token, Map("Authorization" -> Seq(s"Bearer $token"))) shouldBe Right(())
    RunnerHandshake.check(token, Map("authorization" -> Seq(RunnerHandshake.authorization(token)))) shouldBe Right(())
  }

  it should "refuse a handshake with no token, a wrong one, or more than one" in {
    RunnerHandshake.check(token, Map.empty) shouldBe Left("no token")
    RunnerHandshake.check(token, Map("Authorization" -> Seq(s"Bearer x$token"))) shouldBe Left("wrong token")
    RunnerHandshake.check(token, Map("Authorization" -> Seq(token))) shouldBe Left("wrong token")
    RunnerHandshake.check(token, Map("Authorization" -> Seq("Bearer "))) shouldBe Left("wrong token")
    RunnerHandshake.check(token, Map("Authorization" -> Seq(s"Bearer $token", "Bearer other"))) shouldBe
      Left("wrong token")
  }

  it should "refuse any handshake with an Origin header, even one carrying the token" in {
    val headers = Map("Authorization" -> Seq(s"Bearer $token"))
    RunnerHandshake.check(token, headers + ("Origin" -> Seq("https://evil.example"))).isLeft shouldBe true
    RunnerHandshake.check(token, headers + ("origin" -> Seq("http://localhost"))).isLeft shouldBe true
    RunnerHandshake.check(token, headers + ("Origin" -> Seq("null"))).isLeft shouldBe true
  }

  "RunnerHandshake.tokenFrom" should "refuse to start the runner with no token or a short one" in {
    RunnerHandshake.tokenFrom(None).isLeft shouldBe true
    RunnerHandshake.tokenFrom(Some("   ")).isLeft shouldBe true
    RunnerHandshake.tokenFrom(Some("a" * (RunnerHandshake.MinTokenLength - 1))).isLeft shouldBe true
    RunnerHandshake.tokenFrom(Some(("a" * 20) + " " + ("b" * 20))).isLeft shouldBe true
    RunnerHandshake.tokenFrom(Some(s" $token ")) shouldBe Right(token)
  }
}
