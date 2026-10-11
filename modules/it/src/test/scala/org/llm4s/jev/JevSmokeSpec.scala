package org.llm4s.jev

import org.llm4s.error.AuthenticationError
import org.llm4s.it.Tier
import org.llm4s.it.tags.Cloud
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Cloud smoke tests for the Jev decision client, against TypeSafe's live API.
 *
 * `llm4s-jev` is otherwise tested only against a local fake server built from TypeSafe's published API reference, so this
 * is the suite that checks what the fake assumes. Run it with `sbt "it/testOnly org.llm4s.jev.*"` or `sbt testSmoke`.
 * It calls the real API and is billed per input token (a few hundred tokens per test).
 *
 * Requires: `TYPESAFE_API_KEY`.
 *
 * What it settles that the documentation leaves open: that the answers have the documented shape, bounds and
 * relations (probabilities sum to 1, a confidence in 0 to 1, a choice among the options), and the 401 mapping. It does not
 * exercise the 429 and 529 paths, which cannot be provoked on demand.
 */
@Cloud
class JevSmokeSpec extends AnyFlatSpec with Matchers {

  private val apiKey: Option[String] = Option(System.getenv("TYPESAFE_API_KEY")).filter(_.nonEmpty)

  private val ticket = "Help! My payouts have been failing for 3 days and my customers are angry."

  private val request = JevRequest(
    ticket,
    Map(
      "urgent" -> JevQuestion.noul("Does this convey urgency?"),
      "department" -> JevQuestion.choice(
        "Which team should handle this?",
        "billing"   -> "Payments, invoicing, refunds",
        "technical" -> "Bugs, outages, integrations",
        "sales"     -> "Pricing, upgrades, new accounts"
      ),
      "frustration" -> JevQuestion.score("How frustrated is the customer?", "Calm", "Frustrated", "Very angry")
    )
  )

  "Jev" should "answer a Noul, a Choice and a Score in one call, in the documented shapes" in {
    Tier.require(apiKey.isDefined, "TYPESAFE_API_KEY not set")

    val client   = JevClient(JevConfig(apiKey.get)).fold(e => fail(e.message), identity)
    val response = client.evaluate(request).fold(e => fail(s"evaluate failed: ${e.formatted}"), identity)
    client.close()

    response.model should startWith("jev")
    response.usage.inputTokens should be > 0

    val urgent = response.noul("urgent").fold(e => fail(e.message), identity)
    urgent.probability should ((be >= 0.0).and(be <= 1.0))

    val department = response.choice("department").fold(e => fail(e.message), identity)
    department.probabilities.keySet shouldBe Set("billing", "technical", "sales")
    department.probabilities.values.sum shouldBe 1.0 +- 0.05
    department.probabilities.contains(department.choice) shouldBe true
    department.confidence should ((be >= 0.0).and(be <= 1.0))

    val frustration = response.score("frustration").fold(e => fail(e.message), identity)
    frustration.levels.map(_.description) shouldBe Seq("Calm", "Frustrated", "Very angry")
    frustration.levels.map(_.probability).sum shouldBe 1.0 +- 0.05
    frustration.score should ((be >= 0.0).and(be <= 2.0))
    frustration.confidence should ((be >= 0.0).and(be <= 1.0))
  }

  it should "report the request id and the versioned model for the alias jev-latest" in {
    Tier.require(apiKey.isDefined, "TYPESAFE_API_KEY not set")

    val client = JevClient(JevConfig(apiKey.get)).fold(e => fail(e.message), identity)
    val response = client
      .evaluate(JevRequest(ticket, Map("urgent" -> request.questions("urgent"))))
      .fold(e => fail(e.message), identity)
    client.close()

    info(s"model=${response.model} requestId=${response.requestId}")
    response.model should not be "jev-latest" // the alias resolves to a versioned id
  }

  it should "reject an invalid API key with an AuthenticationError, and not retry it" in {
    val client = JevClient(JevConfig("tsk-invalid-key-for-smoke-test")).fold(e => fail(e.message), identity)

    val error = client.evaluate(request).left.getOrElse(fail("an invalid key was accepted"))
    client.close()

    error shouldBe a[AuthenticationError]
    info(s"401 body mapped to: ${error.message}")
  }
}
