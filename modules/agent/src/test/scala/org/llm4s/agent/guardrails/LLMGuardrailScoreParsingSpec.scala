package org.llm4s.agent.guardrails

import org.llm4s.agent.guardrails.builtin._
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * How a judge's reply is read as a score (#1405).
 *
 * A reply is a score only when it holds exactly one plain decimal number from 0 to 1. Anything else is a
 * parse failure and fails the guardrail: it is never clamped into range, because a clamped value reads as
 * 1.0 for the common mistakes (a 0 to 100 answer, a fraction, a percentage) and approves the content.
 */
class LLMGuardrailScoreParsingSpec extends AnyFlatSpec with Matchers {

  final private class ReplyClient(reply: String) extends LLMClient {
    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
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

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)

    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 1024
  }

  private def judge(reply: String, threshold: Double): Result[String] =
    LLMGuardrail(
      client = new ReplyClient(reply),
      prompt = "Rate quality",
      passThreshold = threshold,
      guardrailName = "TestGuardrail"
    ).validate("content")

  private def show(reply: String): String =
    "'" + reply.replace("\n", "\\n").replace("\t", "\\t") + "'"

  // A threshold of 0.0 is the strongest check: every reply that was read as a score passes it, so a Left
  // can only mean the reply was refused as unreadable.
  private val rejected: Seq[String] = Seq(
    // the issue's table
    "85",
    "85%",
    "0.5%",
    "8/10",
    "0.5/1",
    "1e-3",
    "0,9",
    // a percentage named apart from the number
    "1 %",
    "1 percent",
    "1 Percent",
    "1 per cent",
    "1 pct",
    "1 (percent)",
    "0.9 percentile",
    "1 \uFF05",
    "1 \u2030",
    "1 \u2031",
    "1 \uFE6A",
    "1 \u066A",
    "1 \u0609",
    "1 \u060A",
    // a per-mille scale named in words
    "1 per mille",
    "1 permille",
    "1 Per Mille",
    "1 per mil",
    "1 per thousand",
    "1 per hundred",
    // a scale named in words the parser has no list for: anything but the number and a score label is refused
    "1 per ten thousand",
    "1 basis point",
    "100 basis points",
    "100 bps",
    "1 bp",
    "1 out of 10",
    "1 on a scale of 0 to 100",
    // out of range
    "1.5",
    "100",
    "2",
    "1.0001",
    "1.0000000000000001",
    "1.00000000000000000000000000001",
    "-0.2",
    "-0.5",
    // a sign apart from the number
    "- 1",
    "- 0.9",
    "−1",
    "− 1",
    "negative 1",
    "minus 1",
    "-\n1",
    // not a plain decimal
    "NaN",
    "Infinity",
    "0.9/1",
    "1/0",
    "0.85.",
    "5e-1",
    "+0.5",
    "0..5",
    "٠.٩",
    // more than one number, or digits inside a word
    "0.5 or 0.6",
    "0.7 out of 1",
    "0.8-0.9",
    "gpt4 rates this 0.9",
    // prose around the number: the reply must be the number, optionally labelled `Score:`
    "The score is 0.7",
    "0.7, because it is fine",
    "I would say 0.7",
    "0.7 (high)",
    "Score: 0.7 overall",
    "Rating: 0.7",
    // the grammar's edges: emphasis may wrap the whole label but not split it, and a fence must be bare
    "**Score**: 0.7",
    "```text\n0.7\n```",
    // nothing to read
    "",
    "   ",
    "I cannot provide a score for this."
  )

  private val accepted: Seq[(String, Double)] = Seq(
    "0.9"            -> 0.9,
    "0.7"            -> 0.7,
    "  0.7  "        -> 0.7,
    "0.7\n"          -> 0.7,
    "Score: 0.7"     -> 0.7,
    "score: 0.7"     -> 0.7,
    "Score:0.7"      -> 0.7,
    "**Score:** 0.7" -> 0.7,
    "**0.7**"        -> 0.7,
    "`0.7`"          -> 0.7,
    "\"0.7\""        -> 0.7,
    "(0.7)"          -> 0.7,
    "```\n0.7\n```"  -> 0.7,
    // wrapping before and after the number is read independently, so it need not balance
    "((((0.7" -> 0.7,
    "0.7 *"   -> 0.7,
    "0000.5"  -> 0.5,
    ".5"      -> 0.5,
    "0"       -> 0.0,
    "0.0"     -> 0.0,
    "1"       -> 1.0,
    "1.0"     -> 1.0
  )

  behavior.of("LLMGuardrail score parsing")

  rejected.foreach { reply =>
    it should s"refuse the reply ${show(reply)} instead of reading a score from it" in {
      val result = judge(reply, threshold = 0.0)

      result.isLeft shouldBe true
      result.swap.toOption.get match {
        case error: ValidationError =>
          error.field shouldBe "llm_response"
          error.message should include("Could not parse LLM judge score")
        case other => fail(s"expected a ValidationError, got $other")
      }
    }
  }

  it should "not round a value just below 1 up to a perfect score" in {
    judge("0.99999999999999999", threshold = 0.99) shouldBe Right("content")
    judge("0.99999999999999999", threshold = 1.0).swap.toOption.get match {
      case error: ValidationError => error.field shouldBe "output"
      case other                  => fail(s"expected a ValidationError, got $other")
    }
  }

  it should "compare a decimal with the threshold before rounding it to a Double" in {
    // 0.79999999999999999 and 0.8 are the same Double, but only one of them reaches a threshold of 0.8.
    judge("0.79999999999999999", threshold = 0.8).swap.toOption.get match {
      case error: ValidationError => error.field shouldBe "output"
      case other                  => fail(s"expected a ValidationError, got $other")
    }
    judge("0.8", threshold = 0.8) shouldBe Right("content")
    judge("0.80000000000000001", threshold = 0.8) shouldBe Right("content")
    judge("0.69999999999999999", threshold = 0.7).isLeft shouldBe true
  }

  it should "pass nothing at a NaN threshold and everything readable at a negative infinite one" in {
    judge("1", threshold = Double.NaN).isLeft shouldBe true
    judge("0", threshold = Double.NegativeInfinity) shouldBe Right("content")
    judge("1", threshold = Double.PositiveInfinity).isLeft shouldBe true
  }

  accepted.foreach { case (reply, score) =>
    it should s"read ${show(reply)} as exactly $score" in {
      // Passes at a threshold equal to the score, and fails just above it: so the score is neither lower
      // nor higher than read.
      judge(reply, threshold = score) shouldBe Right("content")

      if (score < 1.0) {
        val above = judge(reply, threshold = score + 0.05)
        above.isLeft shouldBe true
        above.swap.toOption.get match {
          case error: ValidationError => error.field shouldBe "output"
          case other                  => fail(s"expected a ValidationError, got $other")
        }
      }
    }
  }

  it should "refuse a scaled reply on every judge guardrail that shares the parser" in {
    val client = new ReplyClient("85")

    val results = Seq(
      LLMSafetyGuardrail(client).validate("content"),
      LLMToneGuardrail(client, Set("professional")).validate("content"),
      LLMFactualityGuardrail(client, "Paris is the capital of France.", threshold = 0.0).validate("content"),
      LLMQualityGuardrail(client, "What is the capital of France?", threshold = 0.0).validate("content")
    )

    results.foreach { result =>
      result.isLeft shouldBe true
      result.swap.toOption.get match {
        case error: ValidationError => error.field shouldBe "llm_response"
        case other                  => fail(s"expected a ValidationError, got $other")
      }
    }
  }

  it should "refuse a number of a million digits quickly, before parsing it" in {
    // Parsing a million-digit decimal takes tens of seconds; the length cap refuses it unparsed.
    val reply   = "0." + "9" * 1000000
    val started = System.nanoTime()
    val result  = LLMGuardrail.readScore(reply)
    val elapsed = (System.nanoTime() - started) / 1000000L

    result shouldBe None
    elapsed should be < 2000L
    judge(reply, threshold = 0.0).swap.toOption.get match {
      case error: ValidationError =>
        error.field shouldBe "llm_response"
        error.message should include("Could not parse LLM judge score")
      case other => fail(s"expected a ValidationError, got $other")
    }
  }

  it should "read a number of up to 64 characters and refuse a longer one" in {
    val atCap   = "0." + "5" * 62
    val overCap = "0." + "5" * 63

    atCap.length shouldBe 64
    LLMGuardrail.readScore(atCap) shouldBe Some(BigDecimal(atCap))
    judge(atCap, threshold = 0.5) shouldBe Right("content")
    judge(s"**Score:** $atCap", threshold = 0.5) shouldBe Right("content")

    LLMGuardrail.readScore(overCap) shouldBe None
    LLMGuardrail.readScore("0" * 60 + ".5") shouldBe Some(BigDecimal("0.5"))
    LLMGuardrail.readScore("0" * 63 + ".5") shouldBe None
  }

  it should "name the reply and what was expected when it refuses one" in {
    val message = judge("85", threshold = 0.0).swap.toOption.get.message

    message should include("'85'")
    message should include("0 to 1")
  }
}
