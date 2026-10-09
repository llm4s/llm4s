package org.llm4s.agent.guardrails

import org.llm4s.agent.guardrails.builtin._
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicInteger

/**
 * The `threshold` of a judge guardrail must be between 0.0 and 1.0 (#1520).
 *
 * The check runs in `validate`, before the judge is called, because the constructors and factories return the
 * guardrail itself and not a `Result`. Every judge goes through `LLMGuardrail.validate`, so each of them is
 * covered here, through its own factory.
 */
class LLMGuardrailThresholdSpec extends AnyFlatSpec with Matchers {

  /** Answers every call with the same reply and counts the calls, so a test can tell whether the judge was asked. */
  final private class CountingClient(reply: String) extends LLMClient {
    val calls = new AtomicInteger(0)

    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
      calls.incrementAndGet()
      Right(
        Completion(
          id = "test-id",
          created = 0L,
          content = reply,
          model = "test-model",
          message = AssistantMessage(reply),
          usage = None
        )
      )
    }

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)

    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 1024
  }

  /** A judge guardrail built through one of the public factories, with the given threshold. */
  final private case class Judge(label: String, build: (LLMClient, Double) => LLMGuardrail)

  private val judges: Seq[Judge] = Seq(
    Judge("LLMGuardrail.apply", (c, t) => LLMGuardrail(c, "Rate the quality", t, "TestGuardrail")),
    Judge("LLMSafetyGuardrail", (c, t) => LLMSafetyGuardrail(c, t)),
    Judge("LLMFactualityGuardrail", (c, t) => LLMFactualityGuardrail(c, "The sky is blue", t)),
    Judge("LLMQualityGuardrail", (c, t) => LLMQualityGuardrail(c, "What colour is the sky?", t)),
    Judge("LLMToneGuardrail", (c, t) => LLMToneGuardrail(c, Set("professional"), t))
  )

  private val outOfRange: Seq[Double] = Seq(
    -0.0001,
    1.0001,
    -1.0,
    2.0,
    Double.MaxValue,
    Double.MinValue,
    Double.NegativeInfinity,
    Double.PositiveInfinity,
    Double.NaN
  )

  // 0.0 and 1.0 are the two bounds; the others sit next to them or in the middle.
  private val inRange: Seq[Double] = Seq(0.0, Double.MinPositiveValue, 0.5, Math.nextDown(1.0), 1.0)

  private def failure(result: Result[String]): ValidationError =
    result match {
      case Left(error: ValidationError) => error
      case other                        => fail(s"expected a ValidationError, got $other")
    }

  "A judge guardrail" should "refuse a threshold outside 0.0 to 1.0 or NaN, on field threshold" in {
    judges.foreach { judge =>
      outOfRange.foreach { value =>
        withClue(s"${judge.label} with threshold $value: ") {
          val client = new CountingClient("1.0")
          val error  = failure(judge.build(client, value).validate("content"))
          error.field shouldBe "threshold"
        }
      }
    }
  }

  it should "refuse a bad threshold without calling the judge" in {
    judges.foreach { judge =>
      outOfRange.foreach { value =>
        withClue(s"${judge.label} with threshold $value: ") {
          val client = new CountingClient("1.0")
          judge.build(client, value).validate("content")
          client.calls.get shouldBe 0
        }
      }
    }
  }

  it should "report a bad threshold before it reads the reply" in {
    // The reply is unreadable, which on its own is an error on field llm_response: the threshold comes first.
    val client = new CountingClient("not a number")
    failure(LLMGuardrail(client, "Rate", 1.5, "TestGuardrail").validate("content")).field shouldBe "threshold"
    client.calls.get shouldBe 0
  }

  it should "name the threshold and the guardrail in the message" in {
    val error = failure(LLMGuardrail(new CountingClient("1.0"), "Rate", 1.5, "NamedGuardrail").validate("content"))
    error.message should include("threshold")
    error.message should include("NamedGuardrail")
    error.message should include("1.5")
  }

  it should "accept the bounds and every value between them, and ask the judge once" in {
    judges.foreach { judge =>
      inRange.foreach { value =>
        withClue(s"${judge.label} with threshold $value: ") {
          // A score of 1.0 reaches any threshold up to and including 1.0.
          val client = new CountingClient("1.0")
          judge.build(client, value).validate("content") shouldBe Right("content")
          client.calls.get shouldBe 1
        }
      }
    }
  }

  it should "pass a score equal to the threshold and fail one just below it" in {
    judges.foreach { judge =>
      withClue(s"${judge.label}: ") {
        judge.build(new CountingClient("0.6"), 0.6).validate("content") shouldBe Right("content")

        val below = failure(judge.build(new CountingClient("0.6"), 0.7).validate("content"))
        below.field shouldBe "output"
      }
    }
  }

  it should "validate the threshold of a subclass that overrides it with a val" in {
    val client = new CountingClient("1.0")
    val guardrail = new LLMGuardrail {
      val llmClient: LLMClient       = client
      val evaluationPrompt: String   = "Rate"
      val name: String               = "SubclassGuardrail"
      override val threshold: Double = 1.5
    }
    failure(guardrail.validate("content")).field shouldBe "threshold"
    client.calls.get shouldBe 0
  }

  it should "leave the default thresholds valid" in {
    // The defaults (0.7 for the base trait and most judges, 0.8 for the safety judge) are inside the range.
    val client = new CountingClient("1.0")
    val defaults = Seq(
      LLMSafetyGuardrail(client),
      LLMFactualityGuardrail(client, "context"),
      LLMQualityGuardrail(client, "query"),
      LLMToneGuardrail(client, Set("professional"))
    )
    defaults.foreach { guardrail =>
      withClue(s"${guardrail.name}: ") {
        guardrail.validate("content") shouldBe Right("content")
      }
    }
    client.calls.get shouldBe defaults.size
  }
}
