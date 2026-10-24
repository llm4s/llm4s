package org.llm4s.agent.events

import org.llm4s.types.ProviderModelTypes.ProviderId
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.{ read, write, ReadWriter }

/** A stored `ModelCallCompleted` keeps its provider as a plain string, so events stored before #2275 still read. */
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
