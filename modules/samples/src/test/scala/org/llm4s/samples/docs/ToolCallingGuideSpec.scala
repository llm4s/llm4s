package org.llm4s.samples.docs

import org.llm4s.agent.Agent
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolRegistry }
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ToolCallingGuideSpec extends AnyFlatSpec with Matchers {

  "Agent" should "invoke weather tool" in {
    var toolWasCalled                = false
    var capturedCity: Option[String] = None

    // A tool that records the arguments it was called with
    val weatherTool = ToolBuilder[Map[String, Any], String](
      "get_weather",
      "Get weather for a city",
      Schema
        .`object`[Map[String, Any]]("Weather query parameters")
        .withProperty(Schema.property("city", Schema.string("City name")))
    ).withHandler { extractor =>
      toolWasCalled = true
      extractor.getString("city").map { city =>
        capturedCity = Some(city)
        s"Weather in $city: 20°C"
      }
    }.buildSafe()

    // Mock client that calls the tool
    class ToolCallingMock extends LLMClient {
      override def getContextWindow(): Int     = 128000
      override def getReserveCompletion(): Int = 4096

      override def complete(
        conversation: Conversation,
        options: CompletionOptions = CompletionOptions()
      ): Result[Completion] = {
        val message = if (conversation.messages.exists(_.isInstanceOf[ToolMessage])) {
          AssistantMessage("Weather in London: 20°C")
        } else {
          AssistantMessage(
            contentOpt = None,
            toolCalls = Seq(ToolCall("call_1", "get_weather", ujson.Obj("city" -> "London")))
          )
        }
        Right(
          Completion(
            id = "mock-1",
            created = System.currentTimeMillis(),
            content = message.content,
            model = "mock-model",
            message = message,
            toolCalls = message.toolCalls.toList
          )
        )
      }
      override def streamComplete(
        conversation: Conversation,
        options: CompletionOptions = CompletionOptions(),
        onChunk: StreamedChunk => Unit
      ): Result[Completion] =
        complete(conversation, options)
    }

    val outcome = for {
      tool  <- weatherTool
      agent <- Agent.builder("test-agent", new ToolCallingMock).withTools(new ToolRegistry(Seq(tool))).build()
      _     <- agent.run("What's the weather in London?")
    } yield ()

    outcome.isRight shouldBe true
    toolWasCalled shouldBe true
    capturedCity shouldBe Some("London")
  }
}
