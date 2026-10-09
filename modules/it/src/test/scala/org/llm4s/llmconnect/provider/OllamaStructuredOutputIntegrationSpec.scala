package org.llm4s.llmconnect.provider

import org.llm4s.it.Tier
import org.llm4s.it.tags.Ollama
import org.llm4s.llmconnect.config.OllamaConfig
import org.llm4s.llmconnect.model._
import org.llm4s.model.ModelRegistryService
import org.llm4s.toolapi.{ ObjectSchema, Schema }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.{ macroRW, ReadWriter }

import scala.util.Try

/**
 * Live checks of structured output against a real Ollama server (the `format` field of `/api/chat`).
 *
 * Non-determinism strategy: only STRUCTURE is asserted (types, non-blank fields, parseable JSON),
 * never the generated text.
 */
@Ollama
class OllamaStructuredOutputIntegrationSpec extends AnyFlatSpec with Matchers {

  import OllamaStructuredOutputIntegrationSpec._

  private given ModelRegistryService = ModelRegistryService.default().toOption.get

  private val testModel = "qwen2.5:0.5b"
  private val baseUrl   = "http://localhost:11434"

  private val config =
    OllamaConfig(model = testModel, baseUrl = baseUrl, contextWindow = 8192, reserveCompletion = 4096)

  private lazy val ollamaAvailable: Boolean =
    Try {
      val connection =
        java.net.URI.create(s"$baseUrl/api/tags").toURL.openConnection().asInstanceOf[java.net.HttpURLConnection]
      connection.setConnectTimeout(3000)
      connection.setReadTimeout(3000)
      connection.setRequestMethod("GET")
      if (connection.getResponseCode == 200) {
        val source = scala.io.Source.fromInputStream(connection.getInputStream)
        try source.mkString.contains(testModel)
        finally source.close()
      } else false
    }.getOrElse(false)

  private def conversation(msg: String): Conversation = Conversation(Seq(UserMessage(msg)))

  private val options = CompletionOptions().withMaxTokens(200).withTemperature(0.0)

  private def withClient[T](f: OllamaClient => T): T = {
    val client = new OllamaClient(config)
    try f(client)
    finally client.close()
  }

  private val personSchema: ObjectSchema[Person] = Schema
    .`object`[Person]("A person")
    .withRequiredField("name", Schema.string("Full name"))
    .withRequiredField("city", Schema.string("City of residence"))

  private val profileSchema: ObjectSchema[Profile] = Schema
    .`object`[Profile]("A profile")
    .withRequiredField("name", Schema.string("Full name"))
    .withRequiredField("hobbies", Schema.array("Hobbies", Schema.string("A hobby")))
    .withRequiredField("level", Schema.string("Skill level").withEnum(Seq("beginner", "advanced")))

  "OllamaClient.completeStructured" should "return a parsed case class for a simple schema" in {
    Tier.require(ollamaAvailable, s"Ollama not available with model $testModel")
    withClient { client =>
      val result =
        client
          .completeStructured[Person](conversation("Invent a person with a name and a city."), personSchema, options)
      withClue(s"completeStructured failed: ${result.swap.toOption}")(result.isRight shouldBe true)
      val person = result.toOption.get
      person.name.trim should not be empty
      person.city.trim should not be empty
    }
  }

  it should "return a parsed value for a nested schema with a list and an enum" in {
    Tier.require(ollamaAvailable, s"Ollama not available with model $testModel")
    withClient { client =>
      val result = client.completeStructured[Profile](
        conversation("Invent a profile: a name, a list of hobbies and a level (beginner or advanced)."),
        profileSchema,
        options
      )
      withClue(s"completeStructured failed: ${result.swap.toOption}")(result.isRight shouldBe true)
      val profile = result.toOption.get
      profile.name.trim should not be empty
      Seq("beginner", "advanced") should contain(profile.level)
    }
  }

  "OllamaClient with ResponseFormat.Json" should "yield parseable JSON" in {
    Tier.require(ollamaAvailable, s"Ollama not available with model $testModel")
    withClient { client =>
      val result = client.complete(
        conversation("Reply with a JSON object describing a cat, with keys name and age."),
        options.withResponseFormat(ResponseFormat.Json)
      )
      withClue(s"complete failed: ${result.swap.toOption}")(result.isRight shouldBe true)
      val parsed = Try(ujson.read(result.toOption.get.content))
      withClue(s"content was not JSON: ${result.toOption.get.content}")(parsed.isSuccess shouldBe true)
      parsed.get.objOpt should not be empty
    }
  }

  "OllamaClient with a strict ResponseFormat.JsonSchema" should "accept additionalProperties/required keywords" in {
    Tier.require(ollamaAvailable, s"Ollama not available with model $testModel")
    val strictSchema = personSchema.toJsonSchema(strict = true)
    // The keywords whose acceptance by Ollama is under test must really be in what is sent.
    (strictSchema.obj.keySet should contain).allOf("additionalProperties", "required")
    withClient { client =>
      val result = client.complete(
        conversation("Invent a person with a name and a city."),
        options.withResponseFormat(ResponseFormat.JsonSchema(strictSchema))
      )
      withClue(s"server rejected the strict schema: ${result.swap.toOption}")(result.isRight shouldBe true)
      val parsed = Try(ujson.read(result.toOption.get.content))
      withClue(s"content was not JSON: ${result.toOption.get.content}")(parsed.isSuccess shouldBe true)
    }
  }

  "OllamaClient with no response format" should "return a plain completion" in {
    Tier.require(ollamaAvailable, s"Ollama not available with model $testModel")
    withClient { client =>
      val result = client.complete(conversation("Say hi in one word"), options)
      withClue(s"complete failed: ${result.swap.toOption}")(result.isRight shouldBe true)
      result.toOption.get.content should not be empty
    }
  }
}

object OllamaStructuredOutputIntegrationSpec {
  final case class Person(name: String, city: String)
  object Person { implicit val rw: ReadWriter[Person] = macroRW }

  final case class Profile(name: String, hobbies: List[String], level: String)
  object Profile { implicit val rw: ReadWriter[Profile] = macroRW }
}
