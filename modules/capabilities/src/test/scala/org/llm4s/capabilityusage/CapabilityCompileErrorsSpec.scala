// Outside `org.llm4s.capability` on purpose: this is what code that uses the module sees, with no access to
// anything package-private.
package org.llm4s.capabilityusage

import org.llm4s.capability.*
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ ContentPart, Conversation, UserMessage }
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.compiletime.testing.Error

/** Misuse of a capability-typed client is a compile error, and correct use compiles. */
class CapabilityCompileErrorsSpec extends AnyFlatSpec with Matchers {

  private def messages(errors: List[Error]): String = errors.map(_.message).mkString("\n")

  // Referred to by the code the specs below type-check.
  def needsVision(c: Capable[Vision], image: ContentPart.Image): Result[String] =
    c.describe(image, "One-line alt text").map(_.content)

  def checkedOnce(client: LLMClient)(using ModelRegistryService): Result[Capable[Vision & Tools]] =
    client.requireCapabilities[Vision & Tools]("openai", "gpt-4o")

  "A Capable[Tools]" should "not be accepted where a Capable[Vision] is needed" in {
    val errors = scala.compiletime.testing.typeCheckErrors(
      "def f(c: Capable[Tools], image: ContentPart.Image) = needsVision(c, image)"
    )
    errors should not be empty
    messages(errors) should include("Capable[org.llm4s.capability.Vision]")
  }

  it should "not have the operations of a capability it was not checked for" in {
    scala.compiletime.testing.typeCheckErrors(
      "def f(c: Capable[Tools], image: ContentPart.Image) = c.describe(image, \"What is this?\")"
    ) should not be empty
    scala.compiletime.testing.typeCheckErrors(
      "def f(c: Capable[Vision], conversation: org.llm4s.llmconnect.model.Conversation) = c.completeWithTools(conversation, Nil)"
    ) should not be empty
  }

  it should "not have completeStructuredOf unless checked for StructuredOutput" in {
    scala.compiletime.testing.typeCheckErrors(
      "def f(c: Capable[Vision], conversation: Conversation) = c.completeStructuredOf[org.llm4s.capability.Caption](conversation)"
    ) should not be empty
    scala.compiletime.testing.typeCheckErrors(
      "def f(c: Capable[Vision & StructuredOutput], conversation: Conversation) = c.completeStructuredOf[org.llm4s.capability.Caption](conversation)"
    ) shouldBe Nil
  }

  it should "keep completeStructuredOf usable with llm4s-schema-derivation's own extension imported too" in {
    import org.llm4s.schema.*
    def onCapable(c: Capable[StructuredOutput], conversation: Conversation) =
      c.completeStructuredOf[org.llm4s.capability.Caption](conversation)
    def onClient(client: LLMClient, conversation: Conversation) =
      client.completeStructuredOf[org.llm4s.capability.Caption](conversation)
    val client       = ScriptedLLMClient.always(Reply.text("""{"text":"t","objects":[]}"""))
    val conversation = Conversation(Seq(UserMessage("hi")))
    onCapable(Capable.assume[StructuredOutput](client), conversation) shouldBe onClient(client, conversation)
  }

  "A Capable[Vision & Tools]" should "be accepted where a Capable[Vision] or a Capable[Tools] is needed" in {
    scala.compiletime.testing.typeCheckErrors(
      "def f(c: Capable[Vision & Tools], image: ContentPart.Image) = needsVision(c, image)"
    ) shouldBe Nil
    scala.compiletime.testing.typeCheckErrors(
      "def f(c: Capable[Vision & Tools], conversation: org.llm4s.llmconnect.model.Conversation) = c.completeWithTools(conversation, Nil)"
    ) shouldBe Nil
    summon[Capable[Vision & Tools] <:< Capable[Vision]]
    summon[Capable[Tools & Vision] <:< Capable[Vision & Tools]]
  }

  "A capability set" should "be refused when it holds a type that is not a capability, naming it" in {
    val errors = messages(scala.compiletime.testing.typeCheckErrors("Capable.assume[Vision & String](null: LLMClient)"))
    errors should include("String is not a capability")
    errors should include("Vision")
  }

  it should "be refused when it is not given at all" in {
    val errors = messages(
      scala.compiletime.testing.typeCheckErrors(
        """def f(client: LLMClient)(using ModelRegistryService) = client.requireCapabilities("openai", "gpt-4o")"""
      )
    )
    errors should include("No capability set was given")
  }

  "requireCapabilities" should "check a model of the bundled model data from outside the package" in {
    val registry = ModelRegistryService.default().fold(e => fail(e.message), identity)
    val client   = org.llm4s.testkit.ScriptedLLMClient.always(org.llm4s.testkit.Reply.text("ok"))
    checkedOnce(client)(using registry).map(_.capabilities) shouldBe Right(List(Capability.Vision, Capability.Tools))
  }

  it should "type its result with the set it checked" in {
    scala.compiletime.testing.typeCheckErrors(
      """def f(client: LLMClient)(using ModelRegistryService): Result[Capable[Vision & Tools]] = client.requireCapabilities[Vision & Tools]("openai", "gpt-4o")"""
    ) shouldBe Nil
    scala.compiletime.testing.typeCheckErrors(
      """def f(client: LLMClient)(using ModelRegistryService): Result[Capable[Vision & Tools]] = client.requireCapabilities[Vision]("openai", "gpt-4o")"""
    ) should not be empty
  }

  "The wildcard import" should "leave Predef.require usable" in {
    require(true, "x")
    scala.compiletime.testing.typeCheckErrors("""require(1 > 0, "positive")""") shouldBe Nil
  }
}
