package org.llm4s.llmconnect.model

import org.llm4s.types.ProviderModelTypes.ProviderId

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.{ read, write }

/**
 * Pins the stored JSON of [[ThinkingBlock]], which is inside stored messages, checkpoints and durable events.
 * Written against the code before `ThinkingBlock.Opaque.provider` became a `ProviderId` (#2908) and unchanged
 * by it but for how the block is built: the bytes below are what both write.
 */
class ThinkingBlockWireFormatSpec extends AnyFlatSpec with Matchers {

  private val opaqueData = """{"type":"reasoning.encrypted","data":"x","index":0}"""

  private val blocks = Seq[(ThinkingBlock, String)](
    ThinkingBlock.Text("Plan the call.") -> """{"type":"text","text":"Plan the call."}""",
    ThinkingBlock.Text("Plan the call.", Some("sig-abc")) ->
      """{"type":"text","text":"Plan the call.","signature":"sig-abc"}""",
    ThinkingBlock.Redacted("ZW5j") -> """{"type":"redacted","data":"ZW5j"}""",
    ThinkingBlock.Opaque(ProviderId("openrouter"), opaqueData) ->
      """{"type":"opaque","provider":"openrouter","data":"{\"type\":\"reasoning.encrypted\",\"data\":\"x\",\"index\":0}"}"""
  )

  "A ThinkingBlock" should "write the pinned JSON" in
    blocks.foreach { case (block, json) => write(block) shouldBe json }

  it should "read the pinned JSON and write the same bytes back" in
    blocks.foreach { case (block, json) =>
      read[ThinkingBlock](json) shouldBe block
      write(read[ThinkingBlock](json)) shouldBe json
    }

  "An assistant message with an opaque block" should "write the pinned JSON, as a thread stores it" in {
    val message = AssistantMessage("Checking.").withThinking(blocks.map(_._1))
    val json =
      """{"type":"assistant","content":"Checking.","toolCalls":[],"thinking":[""" +
        blocks.map(_._2).mkString(",") + "]}"
    write[Message](message) shouldBe json
    write[Message](read[Message](json)) shouldBe json
  }
}
