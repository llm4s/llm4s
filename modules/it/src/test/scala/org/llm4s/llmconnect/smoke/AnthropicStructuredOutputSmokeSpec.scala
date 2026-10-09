package org.llm4s.llmconnect.smoke

import org.llm4s.it.Tier
import org.llm4s.it.tags.Cloud
import org.llm4s.llmconnect.config.{ AnthropicConfig, ContextWindowResolver }
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, UserMessage }
import org.llm4s.llmconnect.provider.AnthropicClient
import org.llm4s.model.ModelRegistryService
import org.llm4s.toolapi.{ ObjectSchema, Schema }
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.{ macroRW, ReadWriter }

/**
 * Cloud smoke tests for `completeStructured` on Anthropic (prompt-instruction fallback plus
 * `extractJson`). Makes three small API calls in total. Only structure is asserted.
 *
 * Requires: `ANTHROPIC_API_KEY` environment variable.
 */
@Cloud
class AnthropicStructuredOutputSmokeSpec extends AnyFlatSpec with Matchers with EitherValues {

  import AnthropicStructuredOutputSmokeSpec._

  private given mrs: ModelRegistryService = ModelRegistryService.default().toOption.get
  private given ContextWindowResolver     = ContextWindowResolver(mrs)

  private val apiKey: Option[String] = Option(System.getenv("ANTHROPIC_API_KEY")).filter(_.nonEmpty)

  private def client(key: String): AnthropicClient =
    AnthropicClient(
      AnthropicConfig
        .fromValues(modelName = "claude-3-haiku-20240307", apiKey = key, baseUrl = "https://api.anthropic.com")
        .value
    ).value

  private val options = CompletionOptions().withMaxTokens(300).withTemperature(0.0)

  private val personSchema: ObjectSchema[Person] = Schema
    .`object`[Person]("A person")
    .withRequiredField("name", Schema.string("Full name"))
    .withRequiredField("city", Schema.string("City of residence"))

  private val profileSchema: ObjectSchema[Profile] = Schema
    .`object`[Profile]("A profile")
    .withRequiredField("name", Schema.string("Full name"))
    .withRequiredField("hobbies", Schema.array("Hobbies", Schema.string("A hobby")))
    .withRequiredField("level", Schema.string("Skill level").withEnum(Seq("beginner", "advanced")))

  "Anthropic completeStructured" should "return a parsed case class for a simple schema" in {
    Tier.require(apiKey.isDefined, "ANTHROPIC_API_KEY not set")
    val c      = client(apiKey.get)
    val result = c.completeStructured[Person](Conversation(Seq(UserMessage("Invent a person."))), personSchema, options)
    withClue(s"completeStructured failed: ${result.swap.toOption}")(result.isRight shouldBe true)
    result.toOption.get.name.trim should not be empty
    result.toOption.get.city.trim should not be empty
  }

  it should "return a parsed value for a nested schema with a list and an enum" in {
    Tier.require(apiKey.isDefined, "ANTHROPIC_API_KEY not set")
    val c = client(apiKey.get)
    val result = c.completeStructured[Profile](
      Conversation(Seq(UserMessage("Invent a profile."))),
      profileSchema,
      options
    )
    withClue(s"completeStructured failed: ${result.swap.toOption}")(result.isRight shouldBe true)
    val profile = result.toOption.get
    profile.name.trim should not be empty
    Seq("beginner", "advanced") should contain(profile.level)
  }

  it should "extract JSON when the prompt invites prose and a markdown fence" in {
    Tier.require(apiKey.isDefined, "ANTHROPIC_API_KEY not set")
    val c = client(apiKey.get)
    val result = c.completeStructured[Person](
      Conversation(
        Seq(
          UserMessage(
            "Please explain in one sentence what you are about to do, then give the person as JSON in a markdown code block."
          )
        )
      ),
      personSchema,
      options
    )
    withClue(s"completeStructured failed: ${result.swap.toOption}")(result.isRight shouldBe true)
    result.toOption.get.name.trim should not be empty
  }
}

object AnthropicStructuredOutputSmokeSpec {
  final case class Person(name: String, city: String)
  object Person { implicit val rw: ReadWriter[Person] = macroRW }

  final case class Profile(name: String, hobbies: List[String], level: String)
  object Profile { implicit val rw: ReadWriter[Profile] = macroRW }
}
