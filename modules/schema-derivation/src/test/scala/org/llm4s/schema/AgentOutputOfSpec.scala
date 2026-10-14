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

/** `withOutputOf`: an agent's response format derived from a case class (#1912, design 4.26). */
class AgentOutputOfSpec extends AnyFlatSpec with Matchers {

  final private class Scripted(replies: String*) extends LLMClient {
    val asked = new CopyOnWriteArrayList[CompletionOptions]()

    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
      val reply = replies(asked.size)
      asked.add(options)
      Right(Completion("id", 0L, reply, "test-model", AssistantMessage(reply)))
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

  "withOutputOf" should "send the derived schema as the response format and decode the answer" in {
    val client = new Scripted(invoiceJson)
    val result = Agent.builder("extractor", client).withOutputOf[Invoice].build().flatMap(_.run("Extract it"))

    result.flatMap(_.decodeOutput[Invoice]) shouldBe Right(
      Invoice("Acme Supplies Ltd", 1250.0, "GBP", "office furniture")
    )
    client.asked.asScala.map(_.responseFormat) shouldBe Seq(
      Some(ResponseFormat.JsonSchema(SchemaOf[Invoice].toJsonSchema()))
    )
  }

  it should "refuse a type that is not a JSON object at build" in {
    val refused = Agent.builder("extractor", new Scripted()).withOutputOf[Color].build()
    refused.left.map(_.message).swap.getOrElse("") should include("object")
  }

  it should "refuse a schema that cannot be built at build, without throwing" in {
    import RecursionFixtures.BothGivens.Gs.given
    // never used: build refuses the schema first
    given upickle.default.ReadWriter[RecursionFixtures.BothGivens.P] =
      upickle.default.readwriter[ujson.Value].bimap(_ => ujson.Null, _ => sys.error("not read"))
    val refused = Agent.builder("extractor", new Scripted()).withOutputOf[RecursionFixtures.BothGivens.P].build()
    refused.left.map(_.message).swap.getOrElse("") should include("recursive type")
  }
}
