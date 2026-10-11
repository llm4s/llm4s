package org.llm4s.zio

import org.llm4s.agent.Agent
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }
import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolRegistry }
import zio.ZIO
import zio.test._

/**
 * Shows [[AgentZ]] driven by `llm4s-testkit`'s [[ScriptedLLMClient]] instead of a real provider or
 * the hand-rolled fakes in [[Fixtures]]: the script is the model's side of the conversation, so a
 * test reads as "the model will ask for the tool, then answer" rather than wiring a `LLMClient`
 * subclass by hand. See `docs/guide/testing-with-the-testkit.md` for the same example without ZIO.
 */
object AgentZTestkitSpec extends ZIOSpecDefault {

  private case class Temperature(city: String, celsius: Double) derives upickle.default.ReadWriter

  private val temperatureTool = ToolBuilder[Map[String, Any], Temperature](
    "get_temperature",
    "Current temperature in a city, in Celsius.",
    Schema.`object`[Map[String, Any]]("Parameters").withProperty(Schema.property("city", Schema.string("City name")))
  ).withHandler(params => params.getString("city").map(city => Temperature(city, 21.0))).buildSafe()

  def spec: Spec[Any, Any] = suite("AgentZTestkitSpec")(
    test("runs a tool call end to end against a scripted model") {
      val client = ScriptedLLMClient.sequence(
        Reply.toolCall("get_temperature", ujson.Obj("city" -> "Oslo")),
        Reply.text("It is 21 degrees in Oslo: pack light layers.")
      )
      for {
        tool <- ZIO.fromEither(temperatureTool)
        agentZ <- ZIO
          .fromEither(Agent.builder("packing-assistant", client).withTools(new ToolRegistry(Seq(tool))).build())
          .map(AgentZ(_))
        result <- agentZ.run("What should I pack for Oslo today?")
      } yield assertTrue(
        result.answer.contains("It is 21 degrees in Oslo: pack light layers."),
        client.callCount == 2,
        client.calls.head.options.tools.map(_.name) == Seq("get_temperature")
      )
    },
    test("surfaces a scripted provider failure as the run's LLMError") {
      val client = ScriptedLLMClient.sequence(Reply.failure(org.llm4s.error.RateLimitError("openai")))
      for {
        agentZ <- ZIO.fromEither(Agent.builder("packing-assistant", client).build()).map(AgentZ(_))
        exit   <- agentZ.run("What should I pack for Oslo today?").exit
      } yield assertTrue(exit.isFailure)
    }
  )
}
