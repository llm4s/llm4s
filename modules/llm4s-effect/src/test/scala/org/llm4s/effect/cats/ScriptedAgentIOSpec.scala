package org.llm4s.effect.cats

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.llm4s.agent.Agent
import org.llm4s.agent.graph.GraphError
import org.llm4s.error.LLMError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.testkit.{ Reply, ScriptedLLMClient, UnscriptedCallError }
import org.llm4s.toolapi._
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default._

/**
 * `llm4s-testkit` driving an [[AgentIO]] end to end: the model's side of a tool-call turn and the final answer are
 * the script, and the recorded calls show what the agent sent.
 *
 * The page `docs/guide/testing-with-the-testkit.md` shows this code ("From llm4s-effect and llm4s-zio"); its drift
 * test, `TestingWithTestkitGuideSpec` in `modules/samples`, fails if a block of the page is not found here or in the
 * other specs it reads. Change the page and this spec together.
 */
class ScriptedAgentIOSpec extends AnyFlatSpec with Matchers with EitherValues {
  import ScriptedAgentIOSpec.*

  "AgentIO with a ScriptedLLMClient" should "run a tool call and then the final answer" in {
    val client = ScriptedLLMClient.sequence(
      Reply.toolCall("get_temperature", """{"city": "Oslo"}"""),
      Reply.text("It is 21 degrees in Oslo: pack light layers.")
    )

    val run = AgentIO[IO](packingAgent(client).value).run("What should I pack for Oslo today?").unsafeRunSync()

    run.answer shouldBe Some("It is 21 degrees in Oslo: pack light layers.")
    client.callCount shouldBe 2
    client.calls.head.options.tools.map(_.name) shouldBe Seq("get_temperature")
    client.calls(1).messages.last.content should include("21")
  }

  it should "raise a call the script has no reply for as an LLMException carrying the typed error" in {
    val client = ScriptedLLMClient.sequence(Reply.toolCall("get_temperature", """{"city": "Oslo"}"""))

    val raised =
      AgentIO[IO](packingAgent(client).value).run("What should I pack for Oslo today?").attempt.unsafeRunSync()

    raised.left.value match {
      case e: LLMException =>
        providerError(e.error) match {
          case unscripted: UnscriptedCallError =>
            unscripted.callNumber shouldBe 2
            unscripted.reason shouldBe "the script has 1 reply"
          case other => fail(s"expected an UnscriptedCallError, got $other")
        }
      case other => fail(s"expected an LLMException, got $other")
    }
    client.callCount shouldBe 2
  }
}

object ScriptedAgentIOSpec {

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

}
