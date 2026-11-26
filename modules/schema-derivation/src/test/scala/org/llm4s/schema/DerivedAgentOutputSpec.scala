package org.llm4s.schema

import org.llm4s.types.ProviderModelTypes.ModelName
import org.llm4s.agent.Agent
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.*
import org.llm4s.schema.Fixtures.{ Color, Invoice }
import org.llm4s.schema.SumFixtures.{ Action, Circle, Dot, Drawing, Idle, Shape, Team }
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
    val asked         = new CopyOnWriteArrayList[CompletionOptions]()
    val conversations = new CopyOnWriteArrayList[Conversation]()

    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
      val reply = replies(asked.size)
      conversations.add(conversation)
      asked.add(options)
      Right(Completion("id", Instant.EPOCH, ModelName("test-model"), AssistantMessage(reply)))
    }

    override def streamComplete(conversation: Conversation, options: CompletionOptions)(
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)

    override def contextWindow: Int     = 4096
    override def reserveCompletion: Int = 1024
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

  private def support(client: Scripted) =
    Agent.builder("support", client).withOutput(SchemaOf[Action].definition).build().flatMap(_.run("Help"))

  private def enveloped(action: Action): String = s"""{"answer":${upickle.default.write[Action](action)}}"""

  // #2953: a sum type whose cases all have fields is a root anyOf of objects, which an agent may answer with
  "A derived sum type" should "be an agent's response format, sent inside an envelope object" in {
    val client = new Scripted(enveloped(Action.Reply("On its way.")))

    support(client).flatMap(_.decodeOutput[Action]) shouldBe Right(Action.Reply("On its way."))
    client.asked.asScala.map(_.responseFormat) shouldBe Seq(
      Some(
        ResponseFormat.JsonSchema(
          ujson.Obj(
            "type"                 -> "object",
            "properties"           -> ujson.Obj("answer" -> SchemaOf[Action].toJsonSchema()),
            "required"             -> ujson.Arr("answer"),
            "additionalProperties" -> false
          )
        )
      )
    )
  }

  it should "round-trip every case through a run, the answer stored and decoded without the envelope" in {
    val cases: Seq[Action] =
      Seq(Action.Refund(BigDecimal("12.50"), "arrived late"), Action.Escalate(Team.Billing), Action.Reply("Done."))
    cases.foreach { action =>
      val result = support(new Scripted(enveloped(action)))

      result.map(_.answer) shouldBe Right(Some(upickle.default.write[Action](action)))
      result.flatMap(_.decodeOutput[Action]) shouldBe Right(action)
    }
  }

  it should "send an answer that is none of the cases back to the model once, naming the closest case's problem" in {
    val refund = Action.Refund(BigDecimal("12.50"), "arrived late")
    val client = new Scripted("""{"answer":{"$type":"Refund","amount":"12.50"}}""", enveloped(refund))

    support(client).flatMap(_.decodeOutput[Action]) shouldBe Right(refund)
    client.asked.size shouldBe 2
    val correction = client.conversations.get(1).messages.last.content
    correction should include("$.answer")
    correction should include("matches none of the 3 alternatives")
    correction should include("reason")
  }

  it should "be refused at build when a case has no fields, with what to do instead" in {
    val refused = Agent.builder("painter", new Scripted()).withOutput(SchemaOf[Shape].definition).build()
    val message = refused.left.map(_.message).swap.getOrElse("")

    message should include("root anyOf must hold only object schemas")
    message should include("make it a property of an object schema")
  }

  it should "be an agent's answer as a field of a case class, as the refusal advises, whatever its cases" in {
    val drawing = Drawing("dots", List(Dot, Circle(1.5)), None, Idle)
    val client  = new Scripted(upickle.default.write(drawing))
    val result =
      Agent.builder("painter", client).withOutput(SchemaOf[Drawing].definition).build().flatMap(_.run("Draw"))

    result.flatMap(_.decodeOutput[Drawing]) shouldBe Right(drawing)
    client.asked.asScala.map(_.responseFormat) shouldBe Seq(
      Some(ResponseFormat.JsonSchema(SchemaOf[Drawing].toJsonSchema()))
    )
  }
}
