package org.llm4s.zio

import org.llm4s.agent.Agent
import org.llm4s.agent.graph.GraphError
import org.llm4s.error.LLMError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.testkit.{ Reply, ScriptedLLMClient, UnscriptedCallError }
import org.llm4s.toolapi._
import org.llm4s.types.Result
import upickle.default._
import org.llm4s.zio.AgentZ
import zio.ZIO
import zio.test.*

/**
 * `llm4s-testkit` driving an [[AgentZ]] end to end: the model's side of a tool-call turn and the final answer are
 * the script, and the recorded calls show what the agent sent.
 *
 * The page `docs/guide/testing-with-the-testkit.md` shows this code ("From llm4s-effect and llm4s-zio"); its drift
 * test, `TestingWithTestkitGuideSpec` in `modules/samples`, fails if a block of the page is not found here or in the
 * other specs it reads. Change the page and this spec together.
 */
object ScriptedAgentZSpec extends ZIOSpecDefault {

  final case class Temperature(city: String, celsius: Double) derives ReadWriter

  val temperatureTool: Result[ToolFunction[Map[String, Any], Temperature]] =
    ToolBuilder[Map[String, Any], Temperature](
      "get_temperature",
      "Current temperature in a city, in Celsius.",
      Schema
        .`object`[Map[String, Any]]("Parameters")
        .withProperty(Schema.property("city", Schema.string("City name")))
    ).withHandler(params => params.getString("city").map(city => Temperature(city, 21.0))).buildSafe()

  def packingAgent(client: LLMClient): Result[Agent] =
    temperatureTool.flatMap(tool =>
      Agent.builder("packing-assistant", client).withTools(new ToolRegistry(Seq(tool))).build()
    )

  /** The error the scripted client returned: an agent wraps it in the `NodeFailed` of the node that called it. */
  def providerError(error: LLMError): LLMError = error match {
    case GraphError.NodeFailed(_, _, cause) => cause
    case other                              => other
  }

  val spec = suite("AgentZ with a ScriptedLLMClient")(
    test("runs a tool call and then the final answer") {
      val client = ScriptedLLMClient.sequence(
        Reply.toolCall("get_temperature", """{"city": "Oslo"}"""),
        Reply.text("It is 21 degrees in Oslo: pack light layers.")
      )

      for {
        agent <- ZIO.fromEither(packingAgent(client))
        run   <- AgentZ(agent).run("What should I pack for Oslo today?")
      } yield assertTrue(
        run.answer.contains("It is 21 degrees in Oslo: pack light layers."),
        client.callCount == 2,
        client.calls.head.options.tools.map(_.name) == Seq("get_temperature"),
        client.calls(1).messages.last.content.contains("21")
      )
    },
    test("fails a call the script has no reply for with the typed error") {
      val client = ScriptedLLMClient.sequence(Reply.toolCall("get_temperature", """{"city": "Oslo"}"""))

      for {
        agent <- ZIO.fromEither(packingAgent(client))
        error <- AgentZ(agent).run("What should I pack for Oslo today?").flip
      } yield assertTrue(
        providerError(error) match {
          case unscripted: UnscriptedCallError =>
            unscripted.callNumber == 2 && unscripted.reason == "the script has 1 reply"
          case _ => false
        },
        client.callCount == 2
      )
    }
  )
}
