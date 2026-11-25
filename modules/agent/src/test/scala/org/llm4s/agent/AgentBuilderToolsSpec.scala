package org.llm4s.agent

import org.llm4s.agent.AgentFixture.*
import org.llm4s.agent.graph.tool.{ ToolContent, ToolOutcome }
import org.llm4s.error.ValidationError
import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolFunction, ToolRegistry }
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.ReadWriter

/**
 * `AgentBuilder.withTools` and `withToolSet` add to the agent's tools, as `withMiddleware`, `withHandoffs` and the
 * guardrails add to theirs; neither replaces what an earlier call set, and a name two calls define fails `build()`
 * (#2916).
 */
class AgentBuilderToolsSpec extends AnyFlatSpec with Matchers with EitherValues {

  final private case class Echoed(text: String) derives ReadWriter

  private def registryTool(name: String): ToolFunction[Echoed] =
    ToolBuilder[Echoed](
      name,
      "Echoes its text",
      Schema.`object`[Map[String, Any]]("Echo parameters").withRequiredField("text", Schema.string("Text to echo"))
    ).withHandler(extractor => extractor.getString("text").map(Echoed(_)))
      .build()
      .fold(e => fail(s"tool did not build: $e"), identity)

  private def registry(names: String*): ToolRegistry =
    ToolRegistry.of(names.map(registryTool)).fold(e => fail(e.message), identity)

  private def typed(name: String) =
    SpecTools.tool(name)((a, _) => ToolOutcome.success(ToolContent.Json(ujson.Str(a.text))))

  /** The names of the tools the agent offers the model on its first call, in order. */
  private def offered(builder: ScriptedLLMClient => AgentBuilder): Seq[String] = {
    val client = ScriptedLLMClient.of(CompletionFixture.simple("done"))
    built(builder(client)).run("go").value
    client.calls.head._2.tools.map(_.name)
  }

  "withToolSet" should "add to the tools an earlier withToolSet set" in {
    offered(
      Agent
        .builder("assistant", _)
        .withToolSet(SpecTools.set(typed("first")))
        .withToolSet(SpecTools.set(typed("second")))
    ) shouldBe Seq("first", "second")
  }

  it should "add to the tools an earlier withTools set" in {
    offered(
      Agent.builder("assistant", _).withTools(registry("from_registry")).withToolSet(SpecTools.set(typed("typed")))
    ) shouldBe Seq("from_registry", "typed")
  }

  "withTools" should "add to the tools an earlier withTools set" in {
    offered(Agent.builder("assistant", _).withTools(registry("a", "b")).withTools(registry("c"))) shouldBe
      Seq("a", "b", "c")
  }

  it should "add to the tools an earlier withToolSet set" in {
    offered(
      Agent.builder("assistant", _).withToolSet(SpecTools.set(typed("typed"))).withTools(registry("from_registry"))
    ) shouldBe Seq("typed", "from_registry")
  }

  "build" should "return a Left naming a tool two calls defined" in {
    val client = ScriptedLLMClient.of(CompletionFixture.simple("never asked"))

    def refused(builder: AgentBuilder): String = {
      val error = builder.build().left.value
      error shouldBe a[ValidationError]
      error.message
    }

    refused(
      Agent.builder("assistant", client).withTools(registry("echo")).withToolSet(SpecTools.set(typed("echo")))
    ) should include("tool 'echo': duplicate tool name")
    refused(
      Agent.builder("assistant", client).withTools(registry("echo", "other")).withTools(registry("echo"))
    ) should include("tool 'echo': duplicate tool name")
    refused(
      Agent
        .builder("assistant", client)
        .withToolSet(SpecTools.set(typed("lookup")))
        .withToolSet(SpecTools.set(typed("lookup")))
    ) should include("tool 'lookup': duplicate tool name")
    client.callCount shouldBe 0
  }
}
