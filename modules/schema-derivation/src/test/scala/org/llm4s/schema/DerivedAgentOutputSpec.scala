package org.llm4s.schema

import org.llm4s.agent.Agent
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.*
import org.llm4s.schema.Fixtures.*
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.CopyOnWriteArrayList
import scala.jdk.CollectionConverters.*
import java.time.Instant

/**
 * A derived schema as an agent's response format (#1912, design 4.26): `withOutput(SchemaOf[A].definition)`.
 * `llm4s-agent` is a test-only dependency of this module, so this composition is checked without the module
 * depending on the agent runtime.
 */
class DerivedAgentOutputSpec extends AnyFlatSpec with Matchers {

  final private class Scripted(replies: String*) extends LLMClient {
    val asked = new CopyOnWriteArrayList[CompletionOptions]()

    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
      val reply = replies(asked.size)
      asked.add(options)
      Right(Completion("id", Instant.EPOCH, reply, "test-model", AssistantMessage(reply)))
    }

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)

    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 1024
  }

  private val invoiceJson =
    """{"vendor":"Acme Supplies Ltd","amount":1250.0,"currency":"GBP","description":"office furniture"}"""

  "A derived schema" should "be sent as the agent's response format, and its answer decode" in {
    val client = new Scripted(invoiceJson)
    val result =
      Agent.builder("extractor", client).withOutput(SchemaOf[Invoice].definition).build().flatMap(_.run("Extract it"))

    result.flatMap(_.decodeOutput[Invoice]) shouldBe Right(
      Invoice("Acme Supplies Ltd", 1250.0, "GBP", "office furniture")
    )
    client.asked.asScala.map(_.responseFormat) shouldBe Seq(
      Some(ResponseFormat.JsonSchema(SchemaOf[Invoice].toJsonSchema()))
    )
  }

  it should "be refused at build when it is not a JSON object" in {
    val refused = Agent.builder("extractor", new Scripted()).withOutput(SchemaOf[Color].definition).build()
    refused.left.map(_.message).swap.getOrElse("") should include("object")
  }
}
