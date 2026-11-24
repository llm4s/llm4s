package org.llm4s.jev

import org.llm4s.security.Secret
import org.llm4s.error.*
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.*

/**
 * The code in `docs/guide/jev.md`, compiled and run against the fake server, so the guide cannot drift from the API.
 * Each `// snippet:` region is part of one of the page's Scala blocks, and the last test reads the page and fails
 * unless every region is on it and every block of it is run (`GuideSnippets`, #2833).
 */
@org.llm4s.tags.DocsSpec
class JevGuideSnippetsSpec extends AnyFlatSpec with Matchers with EitherValues {

  import FakeJev.*

  private val twoAnswers =
    """{"model":"jev-1.13.0","answers":{"department":{"type":"choice","choice":"billing","probabilities":{"billing":0.88,"technical":0.12,"sales":0.0},"confidence":0.81},"urgent":{"type":"noul","noul":0.95},"frustration":{"type":"score","score":1.05,"legend":{"0":"Calm","1":"Frustrated","2":"Very angry"},"probabilities":{"0":0.0,"1":0.95,"2":0.05},"confidence":0.92}},"usage":{"input_tokens":318,"output_tokens":34}}"""

  private val urgentOnly =
    """{"model":"jev-1.13.0","answers":{"urgent":{"type":"noul","noul":0.95}},"usage":{"input_tokens":296,"output_tokens":20}}"""

  "The guide's snippets" should "build a config in code" in {
    // snippet: config
    val config = JevConfig(apiKey = Secret("tsk-..."))
      .withModel("jev-1.13.0") // pin the answers; the default is the alias jev-latest
      .withTimeout(20.seconds)
      .withRetry(JevRetryPolicy(maxRetries = 3))

    val client = JevClient(config) // validates the config
    // end snippet

    client.isRight shouldBe true
  }

  it should "ask questions and read typed answers" in {
    serve(ok(twoAnswers)) { (url, _) =>
      val config = JevConfig(Secret("tsk-..."), baseUrl = url)

      // snippet: ask
      val decision = for {
        client <- JevClient(config)
        response <- client.evaluate(
          JevRequest(
            "Help! My payouts have been failing for 3 days.",
            Map(
              "department" -> JevQuestion.choice(
                "Which team should handle this?",
                "billing"   -> "Payments, invoicing, refunds",
                "technical" -> "Bugs, outages, integrations",
                "sales"     -> "Pricing, upgrades, new accounts"
              ),
              "urgent"      -> JevQuestion.noul("Does this need immediate human attention?"),
              "frustration" -> JevQuestion.score("How frustrated is the customer?", "Calm", "Frustrated", "Very angry")
            )
          )
        )
        department  <- response.choice("department")
        urgent      <- response.noul("urgent")
        frustration <- response.score("frustration")
      } yield (department.choice, department.confidence, urgent.probability, frustration.score)
      // end snippet

      decision.value shouldBe (("billing", 0.81, 0.95, 1.05))
    }
  }

  it should "handle the failures a call can have" in {
    // snippet: describe
    def describe(error: LLMError): String = error match {
      case _: ValidationError     => "the request is wrong: fix it, retrying will not help"
      case _: AuthenticationError => "the key was rejected"
      case rate: RateLimitError   => s"rate limited, retry after ${rate.retryAfter.getOrElse(30.seconds)}"
      case svc: ServiceError      => s"TypeSafe failed with ${svc.httpStatus}"
      case _: TimeoutError        => "no answer in time"
      case _: NetworkError        => "could not reach TypeSafe"
      case _: CancelledError      => "the calling thread was interrupted"
      case other                  => other.message
    }
    // end snippet

    serve(Reply(401, """{"message":"Invalid API key"}""")) { (url, _) =>
      val client = JevClient(JevConfig(Secret("tsk-..."), baseUrl = url, retry = JevRetryPolicy.none)).value

      client
        .evaluate(JevRequest("s", Map("q" -> JevQuestion.noul("Is it?"))))
        .fold(describe, _ => "ok") shouldBe "the key was rejected"
    }
  }

  it should "attach a header to a request" in {
    serve(ok(urgentOnly)) { (url, seen) =>
      val client = JevClient(JevConfig(Secret("tsk-..."), baseUrl = url)).value
      // snippet: header
      val request = JevRequest("...", Map("urgent" -> JevQuestion.noul("Is it?")))
        .withHeader("X-Correlation-Id", "order-4711")
      // end snippet

      client.evaluate(request).isRight shouldBe true
      seen().head.headers("x-correlation-id") shouldBe "order-4711"
    }
  }

  it should "load a client from configuration" in {
    val client = org.llm4s.config.JevConfigLoader
      .load(pureconfig.ConfigSource.string("llm4s.jev.apiKey = tsk-..."))
      .flatMap(JevClient(_))

    client.isRight shouldBe true
  }

  it should "load the default configuration, failing with a ConfigurationError when no key is set" in {
    import org.llm4s.config.JevConfigLoader

    // snippet: default-config
    // Result[JevClient]: Left(ConfigurationError) when no key is set
    val client = JevConfigLoader.default().flatMap(JevClient(_))
    // end snippet

    client.left.toOption.foreach(_ shouldBe a[ConfigurationError])
  }

  "The guide" should "show exactly the code these tests run" in {
    org.llm4s.testutil.GuideSnippets.check(
      "docs/guide/jev.md",
      Seq("modules/providers/jev/src/test/scala/org/llm4s/jev/JevGuideSnippetsSpec.scala"),
      notRun = Set("// same version as llm4s-core") // the sbt dependency line
    ) shouldBe empty
  }
}
