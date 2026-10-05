package org.llm4s.llmconnect.provider

import org.llm4s.it.Tier
import org.llm4s.it.tags.Ollama
import org.llm4s.llmconnect.config.OllamaConfig
import org.llm4s.llmconnect.model._
import org.llm4s.model.ModelRegistryService
import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolFunction }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.{ macroRW, ReadWriter }

import scala.collection.mutable.ListBuffer
import scala.util.Try

/**
 * Live checks of tool calling against a real Ollama server (`tools` and `message.tool_calls`
 * of `/api/chat`). Needs a tool-capable model; only STRUCTURE is asserted, since whether a
 * small model decides to call the tool is not deterministic.
 */
@Ollama
class OllamaToolCallingIntegrationSpec extends AnyFlatSpec with Matchers {

  import OllamaToolCallingIntegrationSpec._

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

  private val options = CompletionOptions().withMaxTokens(200).withTemperature(0.0).withTools(Seq(weatherTool))
  private val question =
    Conversation(Seq(UserMessage("What is the weather in Paris? Use the get_weather tool.")))

  private def withClient[T](f: OllamaClient => T): T = {
    val client = new OllamaClient(config)
    try f(client)
    finally client.close()
  }

  "OllamaClient" should "accept tools and, when the model calls one, return a well-formed ToolCall" in {
    Tier.require(ollamaAvailable, s"Ollama not available with model $testModel")
    withClient { client =>
      val result = client.complete(question, options)
      withClue(s"complete failed: ${result.swap.toOption}")(result.isRight shouldBe true)
      result.toOption.get.toolCalls.foreach { call =>
        call.id should not be empty
        call.name shouldBe "get_weather"
        call.arguments shouldBe a[ujson.Obj]
      }
    }
  }

  it should "stream a tool call with the same shape" in {
    Tier.require(ollamaAvailable, s"Ollama not available with model $testModel")
    withClient { client =>
      val chunks = ListBuffer.empty[StreamedChunk]
      val result = client.streamComplete(question, options, chunks += _)
      withClue(s"streamComplete failed: ${result.swap.toOption}")(result.isRight shouldBe true)
      result.toOption.get.toolCalls.foreach { call =>
        call.id should not be empty
        call.name shouldBe "get_weather"
      }
    }
  }

  it should "accept the result of a tool call and answer" in {
    Tier.require(ollamaAvailable, s"Ollama not available with model $testModel")
    withClient { client =>
      val first = client.complete(question, options).toOption.get
      first.toolCalls.headOption.foreach { call =>
        val followUp = Conversation(question.messages ++ Seq(first.message, ToolMessage("Sunny, 22C", call.id)))
        val result   = client.complete(followUp, options)
        withClue(s"follow-up failed: ${result.swap.toOption}")(result.isRight shouldBe true)
      }
    }
  }
}

private object OllamaToolCallingIntegrationSpec {
  final case class Weather(forecast: String)
  implicit private val weatherRW: ReadWriter[Weather] = macroRW

  val weatherTool: ToolFunction[Map[String, Any], Weather] =
    ToolBuilder[Map[String, Any], Weather](
      "get_weather",
      "Get the current weather in a location",
      Schema
        .`object`[Map[String, Any]]("Weather parameters")
        .withProperty(Schema.property("location", Schema.string("City or location name")))
    ).withHandler(extractor => extractor.getString("location").map(location => Weather(s"Sunny in $location")))
      .buildSafe()
      .fold(error => throw new IllegalStateException(error.message), identity)
}
