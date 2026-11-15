// Outside `org.llm4s.capability` on purpose: this is what code that uses the module sees.
package org.llm4s.capabilityusage

import org.llm4s.capability.*
import org.llm4s.model.{ ModelCapabilities, ModelRegistryService }
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.compiletime.testing.Error

/**
 * `model"provider/model"` is checked against the bundled model data at compile time and typed with exactly the
 * capabilities the data marks true; `requireCapabilities` checks it again at run time.
 *
 * In the bundled data, gpt-4o has vision, tools, parallel tools, structured output, prompt caching and system
 * messages but not reasoning; o3-mini has tools, structured output, reasoning and prompt caching only;
 * text-embedding-3-small has none of them.
 */
class ModelLiteralSpec extends AnyFlatSpec with Matchers with EitherValues {

  private def messages(errors: List[Error]): String = errors.map(_.message).mkString("\n")

  // Referred to by the code the specs below type-check.
  val gpt4o      = model"openai/gpt-4o"
  val o3mini     = model"openai/o3-mini"
  val embeddings = model"openai/text-embedding-3-small"

  "model\"...\"" should "type a known model with exactly the capabilities the bundled data marks true" in {
    summon[gpt4o.type <:< ModelRef[Vision & Tools & ParallelTools & StructuredOutput & PromptCaching & SystemMessages]]
    scala.compiletime.testing.typeCheckErrors("summon[gpt4o.type <:< ModelRef[Reasoning]]") should not be empty

    summon[o3mini.type <:< ModelRef[Tools & StructuredOutput & Reasoning & PromptCaching]]
    scala.compiletime.testing.typeCheckErrors("summon[o3mini.type <:< ModelRef[Vision]]") should not be empty
    scala.compiletime.testing.typeCheckErrors("summon[o3mini.type <:< ModelRef[ParallelTools]]") should not be empty
    scala.compiletime.testing.typeCheckErrors("summon[o3mini.type <:< ModelRef[SystemMessages]]") should not be empty

    scala.compiletime.testing.typeCheckErrors("summon[embeddings.type <:< ModelRef[Tools]]") should not be empty
  }

  it should "carry the same capabilities as values" in {
    gpt4o.capabilities shouldBe List(
      Capability.Vision,
      Capability.Tools,
      Capability.ParallelTools,
      Capability.StructuredOutput,
      Capability.PromptCaching,
      Capability.SystemMessages
    )
    o3mini.capabilities shouldBe List(
      Capability.Tools,
      Capability.StructuredOutput,
      Capability.Reasoning,
      Capability.PromptCaching
    )
    embeddings.capabilities shouldBe Nil
    (gpt4o.provider, gpt4o.model, gpt4o.id) shouldBe (("openai", "gpt-4o", "openai/gpt-4o"))
    o3mini.toString shouldBe "ModelRef(openai/o3-mini, Tools & StructuredOutput & Reasoning & PromptCaching)"
  }

  it should "be accepted where a ModelRef of fewer capabilities is needed, and refused where it lacks one" in {
    scala.compiletime.testing.typeCheckErrors("val r: ModelRef[Vision & Tools] = model\"openai/gpt-4o\"") shouldBe Nil
    messages(
      scala.compiletime.testing.typeCheckErrors("val r: ModelRef[Vision] = model\"openai/o3-mini\"")
    ) should include("Vision")
  }

  it should "refuse an unknown model at compile time, suggesting the closest known one" in {
    val errors = messages(scala.compiletime.testing.typeCheckErrors("model\"openai/gpt-4oo\""))
    errors should include("unknown model openai/gpt-4oo")
    errors should include("did you mean openai/gpt-4o?")
  }

  it should "suggest nothing when no known model is close" in {
    val errors = messages(scala.compiletime.testing.typeCheckErrors("model\"nobody/qwertyuiopasdfghjkl-zxcvbnm\""))
    errors should include("unknown model nobody/qwertyuiopasdfghjkl-zxcvbnm")
    (errors should not).include("did you mean")
  }

  it should "refuse an id with no provider, and interpolated values" in {
    messages(scala.compiletime.testing.typeCheckErrors("model\"gpt-4o\"")) should include("is not a provider/model id")
    messages(scala.compiletime.testing.typeCheckErrors("{ val m = \"gpt-4o\"; model\"openai/$m\" }")) should include(
      "with no ${} in it"
    )
  }

  "requireCapabilities(ref)" should "check the model again at run time against the registry in scope" in {
    given ModelRegistryService = ModelRegistryService.default().fold(e => fail(e.message), identity)
    val client                 = ScriptedLLMClient.always(Reply.text("ok"))

    val capable: Capable[Vision & Tools & ParallelTools & StructuredOutput & PromptCaching & SystemMessages] =
      client.requireCapabilities(gpt4o).value
    capable.capabilities shouldBe gpt4o.capabilities
    capable.metadata.map(_.modelId) shouldBe Some("gpt-4o")
    capable.client shouldBe client
  }

  it should "check only the smaller set it is given" in {
    given ModelRegistryService = ModelRegistryService.default().fold(e => fail(e.message), identity)
    val client                 = ScriptedLLMClient.always(Reply.text("ok"))
    client.requireCapabilities[Vision](gpt4o).value.capabilities shouldBe List(Capability.Vision)
  }

  it should "accept a model whose literal has no capabilities, checking nothing" in {
    given ModelRegistryService = ModelRegistryService.default().fold(e => fail(e.message), identity)
    val client                 = ScriptedLLMClient.always(Reply.text("ok"))
    client.requireCapabilities(embeddings).value.capabilities shouldBe Nil
  }

  it should "refuse, naming every missing capability, when the run-time registry differs from the bundled data" in {
    val downgraded = org.llm4s.capability.Fixtures
      .model("gpt-4o", ModelCapabilities(supportsFunctionCalling = Some(true)))
      .withProvider("openai")
    given ModelRegistryService = ModelRegistryService.fromModels(List(downgraded))
    val client                 = ScriptedLLMClient.always(Reply.text("unused"))

    val error = client.requireCapabilities(gpt4o).left.value.message
    List("Vision", "ParallelTools", "StructuredOutput", "PromptCaching", "SystemMessages").foreach { name =>
      error should include(s"openai/gpt-4o does not support $name")
    }
    (error should not).include("support Tools")
    client.requireCapabilities[Tools](gpt4o).value.capabilities shouldBe List(Capability.Tools)
    client.callCount shouldBe 0
  }

  it should "pass the registry's error through for a model it does not know" in {
    given ModelRegistryService = org.llm4s.capability.Fixtures.registry
    ScriptedLLMClient.always(Reply.text("unused")).requireCapabilities(gpt4o).left.value.message should include(
      "gpt-4o"
    )
  }
}
