package org.llm4s.jev

import org.llm4s.error.{ ProcessingError, ValidationError }
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicReference

class JevResponseSpec extends AnyFlatSpec with Matchers with EitherValues {

  import FakeJev.Examples

  private def parse(body: String): JevResponse = JevResponse.parse(body, None).value

  private def malformed(body: String): String =
    JevResponse.parse(body, None).left.value match {
      case p: ProcessingError => p.message
      case other              => fail(s"expected a ProcessingError, got $other")
    }

  private def withAnswer(answer: String): String =
    s"""{"model":"jev-1.13.0","answers":{"q":$answer},"usage":{"input_tokens":1,"output_tokens":2}}"""

  // ---- the contract's examples, verbatim ----

  "A response" should "parse the contract's Noul example" in {
    val response = parse(Examples.Noul)

    response.model shouldBe "jev-1.13.0"
    response.usage shouldBe JevUsage(296, 20)
    response.noul("is_urgent").value shouldBe NoulAnswer(0.95)
  }

  it should "parse the contract's Choice example, keeping the whole distribution and the confidence" in {
    val answer = parse(Examples.Choice).choice("department").value

    answer.choice shouldBe "billing"
    answer.probabilities shouldBe Map("billing" -> 0.88, "technical" -> 0.12, "sales" -> 0.0)
    answer.confidence shouldBe 0.81
  }

  it should "parse the contract's Score example into levels in order, with their descriptions and probabilities" in {
    val answer = parse(Examples.Score).score("frustration").value

    answer.score shouldBe 1.05
    answer.confidence shouldBe 0.92
    answer.levels shouldBe Seq(
      ScoreLevel(0, "Calm", 0.0),
      ScoreLevel(1, "Frustrated", 0.95),
      ScoreLevel(2, "Very angry", 0.05)
    )
  }

  it should "order the levels of a score numerically, not as text" in {
    val legend = (0 to 11).map(i => s""""$i":"l$i"""").mkString(",")
    val probs  = (0 to 11).map(i => s""""$i":0.0""").mkString(",")
    val body =
      withAnswer(s"""{"type":"score","score":3.5,"legend":{$legend},"probabilities":{$probs},"confidence":0.5}""")

    parse(body).score("q").value.levels.map(_.index) shouldBe (0 to 11)
  }

  it should "parse several answers of different types, each under its own id" in {
    val body =
      """{"model":"jev-1.13.0","answers":{"a":{"type":"noul","noul":0.1},"b":{"type":"choice","choice":"x","probabilities":{"x":1.0},"confidence":1.0}},"usage":{"input_tokens":5,"output_tokens":6}}"""

    val response = parse(body)

    response.answers.keySet shouldBe Set("a", "b")
    response.noul("a").value.probability shouldBe 0.1
    response.choice("b").value.choice shouldBe "x"
  }

  it should "carry the request id it is given" in {
    JevResponse.parse(Examples.Noul, Some("req-1")).value.requestId shouldBe Some("req-1")
    parse(Examples.Noul).requestId shouldBe None
    parse(Examples.Noul).withRequestId(Some("r")).requestId shouldBe Some("r")
  }

  it should "accept the bounds of 0 and 1, and a score at the lowest and the highest level" in {
    parse(withAnswer("""{"type":"noul","noul":0}""")).noul("q").value.probability shouldBe 0.0
    parse(withAnswer("""{"type":"noul","noul":1}""")).noul("q").value.probability shouldBe 1.0
    parse(withAnswer(score(0.0))).score("q").value.score shouldBe 0.0
    parse(withAnswer(score(2.0))).score("q").value.score shouldBe 2.0
  }

  private def score(value: Double, legend: String = """"0":"a","1":"b","2":"c"""", probs: String = ""): String = {
    val keys          = ujson.read(s"{$legend}").obj.keys
    val probabilities = if (probs.nonEmpty) probs else keys.map(k => s""""$k":0.0""").mkString(",")
    s"""{"type":"score","score":$value,"legend":{$legend},"probabilities":{$probabilities},"confidence":0.5}"""
  }

  it should "refuse a score outside its levels: the probability-weighted level lies between the first and the last" in {
    malformed(withAnswer(score(-0.5))) should include("answers.q.score")
    malformed(withAnswer(score(-0.5))) should include("outside the levels 0 to 2")
    malformed(withAnswer(score(2.01))) should include("outside the levels 0 to 2")
  }

  it should "accept a score a rounding error outside its levels, clamped into them" in {
    // 3 levels whose probabilities sum to one ulp over 1: the weighted level lands just past the top
    parse(withAnswer(score(2.0000000000000004))).score("q").value.score shouldBe 2.0
    parse(withAnswer(score(-1e-300))).score("q").value.score shouldBe 0.0
    parse(withAnswer(score(2.0 + JevResponse.ScoreTolerance / 2))).score("q").value.score shouldBe 2.0
  }

  it should "still refuse a score clearly outside its levels" in {
    malformed(withAnswer(score(2.0 + 1e-6))) should include("outside the levels 0 to 2")
    malformed(withAnswer(score(-1e-6))) should include("outside the levels 0 to 2")
    malformed(withAnswer(score(2.0 + JevResponse.ScoreTolerance * 2))) should include("outside the levels 0 to 2")
  }

  it should "refuse a level key that is not a canonical level number, so no two keys name one level" in {
    Seq("00", "+0", "01", "-1", " 1", "1.0", "99999999999").foreach { key =>
      withClue(s"'$key' ") {
        malformed(withAnswer(score(0.5, legend = s""""0":"a","$key":"b""""))) should include("not a level number")
      }
    }
  }

  it should "refuse a body nested deeper than any API response, and keep no value that deep" in {
    val depth = 100000
    val deep  = "[" * depth + "]" * depth
    val error = malformed(withAnswer(score(0.5, legend = s""""0":"a","1":$deep""")))

    error should include(s"nested more than ${JevResponse.MaxResponseDepth} levels deep")
  }

  /** Runs `body` on a thread with a 256 KiB stack, so a recursion over a deeply nested value overflows it. */
  private def onSmallStack[A](body: => A): A = {
    val outcome = new AtomicReference[Either[Throwable, A]](null)
    val thread  = new Thread(null, () => outcome.set(scala.util.Try(body).toEither), "small-stack", 256L * 1024)
    thread.start()
    thread.join(60000)
    thread.isAlive shouldBe false
    // A StackOverflowError is fatal, so Try rethrows it: the thread dies and records nothing.
    Option(outcome.get).getOrElse(fail("the call died on the small stack, most likely of a StackOverflowError")) match {
      case Right(a) => a
      case Left(e)  => throw e
    }
  }

  it should "parse a structured description at the depth limit, and print and hash the response on a small stack" in {
    // four levels of envelope (the root, answers, the answer, its legend) and the description: exactly the limit
    val depth = JevResponse.MaxResponseDepth - 4
    val body  = withAnswer(score(0.5, legend = s""""0":"a","1":${"[" * depth + "]" * depth}"""))

    val (printed, hashed, equal) = onSmallStack {
      val response = parse(body)
      (response.toString, response.hashCode, response == parse(body))
    }

    printed should not be empty
    hashed shouldBe parse(body).hashCode
    equal shouldBe true
  }

  it should "refuse a description one level past the limit" in {
    val depth = JevResponse.MaxResponseDepth - 3
    val error = malformed(withAnswer(score(0.5, legend = s""""0":"a","1":${"[" * depth + "]" * depth}""")))

    error should include(s"nested more than ${JevResponse.MaxResponseDepth} levels deep")
  }

  it should "refuse, on a small stack, a description nested 511 levels deep (under the shared 512-level limit)" in {
    val depth  = 511 - 4
    val body   = withAnswer(score(0.5, legend = s""""0":"a","1":${"[" * depth + "]" * depth}"""))
    val result = onSmallStack(JevResponse.parse(body, None))

    result.left.value shouldBe a[ProcessingError]
    result.left.value.message should include("nested more than")
  }

  it should "preserve structured score descriptions" in {
    val body = withAnswer(
      """{"type":"score","score":0.5,"legend":{"0":{"label":"low"},"1":["high",{"detail":true}]},"probabilities":{"0":0.5,"1":0.5},"confidence":1}"""
    )
    val levels = parse(body).score("q").value.levels
    levels.map(_.description) shouldBe Seq(ujson.Obj("label" -> "low"), ujson.Arr("high", ujson.Obj("detail" -> true)))
  }

  it should "reject a selected choice below the maximum and accept ties" in {
    malformed(
      withAnswer("""{"type":"choice","choice":"a","probabilities":{"a":0.1,"b":0.9},"confidence":1}""")
    ) should include("highest probability")
    parse(withAnswer("""{"type":"choice","choice":"a","probabilities":{"a":0.5,"b":0.5},"confidence":1}"""))
      .choice("q")
      .value
      .choice shouldBe "a"
  }

  // ---- typed access ----

  it should "say so when an answer is absent or of another type" in {
    val response = parse(Examples.Noul)

    response.choice("is_urgent").left.value shouldBe a[ValidationError]
    response.choice("is_urgent").left.value.message should include("not a choice answer")
    response.score("is_urgent").left.value.message should include("not a score answer")
    response.noul("missing").left.value.message should include("no answer came back")
    parse(Examples.Choice).noul("department").left.value.message should include("not a noul answer")
  }

  // ---- malformed responses: a ProcessingError that names where, and never quotes the body ----

  it should "refuse a body that is not JSON, without quoting it" in {
    malformed("<html>secret-state-echo</html>") should (include("not valid JSON").and(not).include("secret-state-echo"))
  }

  it should "refuse a response without its parts" in {
    malformed("[]") should include("expected an object")
    malformed("""{"answers":{},"usage":{"input_tokens":1,"output_tokens":1}}""") should include("no `model`")
    malformed("""{"model":"m","usage":{"input_tokens":1,"output_tokens":1}}""") should include("no `answers`")
    malformed("""{"model":"m","answers":{}}""") should include("no `usage`")
    malformed("""{"model":7,"answers":{},"usage":{"input_tokens":1,"output_tokens":1}}""") should include(
      "expected a string"
    )
    malformed("""{"model":"m","answers":[],"usage":{"input_tokens":1,"output_tokens":1}}""") should include("answers")
  }

  it should "refuse usage that is not a count of tokens" in {
    def usage(u: String) = s"""{"model":"m","answers":{},"usage":$u}"""

    malformed(usage("""{"input_tokens":-1,"output_tokens":1}""")) should include("usage.input_tokens")
    malformed(usage("""{"input_tokens":1.5,"output_tokens":1}""")) should include("usage.input_tokens")
    malformed(usage("""{"input_tokens":1}""")) should include("no `output_tokens`")
    malformed(usage("""{"input_tokens":"1","output_tokens":1}""")) should include("expected a finite number")
    malformed(usage("""{"input_tokens":3000000000,"output_tokens":1}""")) should include("usage.input_tokens")
  }

  it should "refuse an answer of an unknown or missing type" in {
    malformed(withAnswer("""{"type":"ranking","x":1}""")) should include("unsupported answer type 'ranking'")
    malformed(withAnswer("""{"noul":0.5}""")) should include("no `type`")
    malformed(withAnswer("""7""")) should include("expected an object")
  }

  it should "refuse a probability or confidence outside 0 to 1" in {
    malformed(withAnswer("""{"type":"noul","noul":1.5}""")) should include("outside 0 to 1")
    malformed(withAnswer("""{"type":"noul","noul":-0.1}""")) should include("outside 0 to 1")
    malformed(withAnswer("""{"type":"noul"}""")) should include("no `noul`")
    malformed(withAnswer("""{"type":"choice","choice":"a","probabilities":{"a":2},"confidence":0.5}""")) should
      include("answers.q.probabilities.a")
    malformed(withAnswer("""{"type":"choice","choice":"a","probabilities":{"a":1},"confidence":1.5}""")) should
      include("answers.q.confidence")
  }

  it should "refuse a choice that is not among its probabilities" in {
    malformed(withAnswer("""{"type":"choice","choice":"zzz","probabilities":{"a":1},"confidence":1}""")) should
      include("not among the probabilities")
  }

  it should "refuse a score whose levels and legend disagree or whose keys are not level numbers" in {
    malformed(
      withAnswer("""{"type":"score","score":1,"legend":{"0":"a","1":"b"},"probabilities":{"0":1},"confidence":1}""")
    ) should include("do not match the legend")
    malformed(
      withAnswer("""{"type":"score","score":1,"legend":{"x":"a"},"probabilities":{"x":1},"confidence":1}""")
    ) should include("not a level number")
    malformed(
      withAnswer("""{"type":"score","score":1,"legend":{"0":1},"probabilities":{"0":1},"confidence":1}""")
    ) should include("expected a string, object or array")
    malformed(
      withAnswer("""{"type":"score","score":null,"legend":{"0":"a"},"probabilities":{"0":1},"confidence":1}""")
    ) should include("expected a finite number")
  }
}
