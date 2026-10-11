package org.llm4s.effect.cats

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.llm4s.agent.Agent
import org.llm4s.error.RateLimitError
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }
import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolRegistry }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Shows [[AgentIO]] driven by `llm4s-testkit`'s [[ScriptedLLMClient]] instead of a real provider or
 * the hand-rolled fakes in [[Fixtures]]: the script is the model's side of the conversation, so a
 * test reads as "the model will ask for the tool, then answer" rather than wiring a `LLMClient`
 * subclass by hand. See `docs/guide/testing-with-the-testkit.md` for the same example without
 * cats-effect.
 */
class AgentIOTestkitSpec extends AnyFlatSpec with Matchers {

  private case class Temperature(city: String, celsius: Double) derives upickle.default.ReadWriter

  private val temperatureTool = ToolBuilder[Map[String, Any], Temperature](
    "get_temperature",
    "Current temperature in a city, in Celsius.",
    Schema.`object`[Map[String, Any]]("Parameters").withProperty(Schema.property("city", Schema.string("City name")))
  ).withHandler(params => params.getString("city").map(city => Temperature(city, 21.0))).buildSafe()

  "AgentIO" should "run a tool call end to end against a scripted model" in {
    val client = ScriptedLLMClient.sequence(
      Reply.toolCall("get_temperature", ujson.Obj("city" -> "Oslo")),
      Reply.text("It is 21 degrees in Oslo: pack light layers.")
    )
    val tool = temperatureTool.toOption.get
    val agentIO = AgentIO[IO](
      Agent.builder("packing-assistant", client).withTools(new ToolRegistry(Seq(tool))).build().toOption.get
    )

    val result = agentIO.run("What should I pack for Oslo today?").unsafeRunSync()

    result.answer shouldBe Some("It is 21 degrees in Oslo: pack light layers.")
    client.callCount shouldBe 2
    client.calls.head.options.tools.map(_.name) shouldBe Seq("get_temperature")
  }

  it should "surface a scripted provider failure as a raised LLMException" in {
    val client  = ScriptedLLMClient.sequence(Reply.failure(RateLimitError("openai")))
    val agentIO = AgentIO[IO](Agent.builder("packing-assistant", client).build().toOption.get)

    a[LLMException] should be thrownBy agentIO.run("What should I pack for Oslo today?").unsafeRunSync()
  }
}
