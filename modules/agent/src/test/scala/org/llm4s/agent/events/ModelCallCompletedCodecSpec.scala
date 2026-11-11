package org.llm4s.agent.events

import org.llm4s.llmconnect.model.{ RequestAdjustment, RequestOption }
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.{ read, write, ReadWriter }

/**
 * A stored `ModelCallCompleted` keeps its provider as a plain string, so events stored before #2275 still read, and
 * reads without `adjustments` when it was stored before RFC 0008 (#2115) added them.
 */
class ModelCallCompletedCodecSpec extends AnyFlatSpec with Matchers:

  /** The payload as it was stored with `provider: Option[String]`. */
  final private case class StoredWithString(
    agent: String,
    model: String,
    attempts: Int,
    toolCalls: Int,
    usage: Option[CallUsage],
    estimatedCost: Option[Double],
    provider: Option[String] = None
  ) derives ReadWriter

  /** The payload as it was stored before it had a provider. */
  final private case class StoredWithoutProvider(
    agent: String,
    model: String,
    attempts: Int,
    toolCalls: Int,
    usage: Option[CallUsage],
    estimatedCost: Option[Double]
  ) derives ReadWriter

  private val usage = Some(CallUsage(10, 5, 15, None))

  "ModelCallCompleted" should "read an event stored before it had a provider" in {
    val stored = write(StoredWithoutProvider("assistant", "gpt-4o", 1, 0, usage, Some(0.01)))
    read[ModelCallCompleted](stored) shouldBe
      ModelCallCompleted("assistant", "gpt-4o", 1, 0, usage, Some(0.01), provider = None)
  }

  it should "read an event whose provider was stored as a string" in {
    val stored = write(StoredWithString("assistant", "gpt-4o", 1, 0, usage, Some(0.01), Some("openai")))
    read[ModelCallCompleted](stored).provider shouldBe Some(ProviderId("openai"))
  }

  it should "store its provider in the same form as a string" in {
    val event = ModelCallCompleted("assistant", "gpt-4o", 1, 0, usage, None, Some(ProviderId("anthropic")))
    write(event) shouldBe write(StoredWithString("assistant", "gpt-4o", 1, 0, usage, None, Some("anthropic")))
    read[ModelCallCompleted](write(event)) shouldBe event
  }

  /** The payload as it was stored before it had adjustments (RFC 0008, #2115). */
  final private case class StoredWithoutAdjustments(
    agent: String,
    model: String,
    attempts: Int,
    toolCalls: Int,
    usage: Option[CallUsage],
    estimatedCost: Option[Double],
    provider: Option[String] = None
  ) derives ReadWriter

  private def adjustments = List(
    RequestAdjustment.Dropped(RequestOption.Reasoning, "reasoning effort is not sent to gemini-2.5-pro by this client"),
    RequestAdjustment.Adjusted(RequestOption.Temperature, "2.5", "2.0", "gpt-4o maximum is 2.0"),
    RequestAdjustment.NotNeeded(RequestOption.PromptCache, "the provider caches prompts itself")
  )

  it should "read an event stored before it had adjustments with none" in {
    val stored = write(StoredWithoutAdjustments("assistant", "gpt-4o", 1, 0, usage, Some(0.01), Some("openai")))
    read[ModelCallCompleted](stored).adjustments shouldBe Nil
  }

  it should "store an event with no adjustments as it was stored before they existed" in {
    val event = ModelCallCompleted("assistant", "gpt-4o", 1, 0, usage, None, Some(ProviderId("openai")))
    write(event) shouldBe write(StoredWithoutAdjustments("assistant", "gpt-4o", 1, 0, usage, None, Some("openai")))
  }

  it should "round-trip every kind of adjustment" in {
    val event = ModelCallCompleted("assistant", "gpt-4o", 1, 0, usage, None, Some(ProviderId("openai")), adjustments)
    read[ModelCallCompleted](write(event)) shouldBe event
  }

  it should "be read by a reader that predates adjustments, which ignores them" in {
    val event =
      ModelCallCompleted("assistant", "gpt-4o", 1, 0, usage, Some(0.01), Some(ProviderId("openai")), adjustments)
    read[StoredWithoutAdjustments](write(event)) shouldBe
      StoredWithoutAdjustments("assistant", "gpt-4o", 1, 0, usage, Some(0.01), Some("openai"))
  }

  it should "store each adjustment tagged by its case, with its option by name" in {
    val event = ModelCallCompleted("assistant", "gpt-4o", 1, 0, usage, None, None, adjustments)
    ujson.read(write(event))("adjustments") shouldBe ujson.Arr(
      ujson.Obj(
        "$type"  -> "Dropped",
        "option" -> "Reasoning",
        "reason" -> "reasoning effort is not sent to gemini-2.5-pro by this client"
      ),
      ujson.Obj(
        "$type"     -> "Adjusted",
        "option"    -> "Temperature",
        "requested" -> "2.5",
        "sent"      -> "2.0",
        "reason"    -> "gpt-4o maximum is 2.0"
      ),
      ujson.Obj("$type" -> "NotNeeded", "option" -> "PromptCache", "reason" -> "the provider caches prompts itself")
    )
  }

  it should "leave out an adjustment it cannot read, an option or a case added later, and keep the rest" in {
    val stored =
      ujson.read(write(ModelCallCompleted("assistant", "gpt-4o", 1, 0, usage, Some(0.01), None, adjustments)))
    stored("adjustments").arr.prepend(ujson.Obj("$type" -> "Dropped", "option" -> "ServiceTier", "reason" -> "later"))
    stored("adjustments").arr.append(ujson.Obj("$type" -> "Deferred", "option" -> "TopP", "reason" -> "later"))
    val read = upickle.default.read[ModelCallCompleted](stored)
    read.adjustments shouldBe adjustments
    read.usage shouldBe usage
    read.estimatedCost shouldBe Some(0.01)
  }
