package org.llm4s.llmconnect.provider

import com.sun.net.httpserver.HttpServer
import org.llm4s.llmconnect.config.{ DeepSeekConfig, OpenAIConfig, ZaiConfig }
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, UserMessage }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

final class ProviderStreamingArgumentsSpec extends AnyFlatSpec with Matchers {

  private def toolCallEvent(callId: String, args: String): ujson.Value =
    ujson.Obj(
      "id" -> "chatcmpl-1",
      "choices" -> ujson.Arr(
        ujson.Obj(
          "delta" -> ujson.Obj(
            "tool_calls" -> ujson.Arr(
              ujson.Obj(
                "id"   -> callId,
                "type" -> "function",
                "function" -> ujson.Obj(
                  "name"      -> "test",
                  "arguments" -> args
                )
              )
            )
          )
        )
      )
    )

  private def ssePayload(events: Seq[ujson.Value]): String =
    events.map(event => s"data: ${ujson.write(event)}\n\n").mkString + "data: [DONE]\n\n"

  private class SseServerResource(payload: String) extends AutoCloseable {
    private val server = HttpServer.create(new InetSocketAddress("localhost", 0), 0)
    server.createContext(
      "/chat/completions",
      exchange => {
        val bytes = payload.getBytes(StandardCharsets.UTF_8)
        exchange.getResponseHeaders.add("Content-Type", "text/event-stream")
        exchange.sendResponseHeaders(200, bytes.length)
        val os = exchange.getResponseBody
        os.write(bytes)
        os.close()
      }
    )
    server.start()

    val baseUrl: String = s"http://localhost:${server.getAddress.getPort}"

    override def close(): Unit = server.stop(0)
  }

  private def withSseServer(payload: String)(test: String => Unit): Unit =
    scala.util.Using.resource(new SseServerResource(payload))(resource => test(resource.baseUrl))

  "OpenRouterClient" should "preserve invalid tool-call arguments as raw string" in {
    val payload = ssePayload(
      Seq(
        toolCallEvent("call-1", ""),
        toolCallEvent("call-2", "{not json")
      )
    )

    withSseServer(payload) { baseUrl =>
      val config = OpenAIConfig(
        apiKey = "test-key",
        model = "test-model",
        organization = None,
        baseUrl = baseUrl,
        contextWindow = 8192,
        reserveCompletion = 4096
      )

      val client   = new OpenRouterClient(config)
      val captured = scala.collection.mutable.ListBuffer.empty[ujson.Value]

      val result = client.streamComplete(
        Conversation(Seq(UserMessage("hello"))),
        CompletionOptions(),
        chunk => chunk.toolCall.foreach(tc => captured += tc.arguments)
      )

      result.isRight shouldBe true
      captured.toList shouldBe List(ujson.Obj(), ujson.Str("{not json"))
    }
  }

  "ZaiClient" should "preserve invalid tool-call arguments as raw string" in {
    val payload = ssePayload(
      Seq(
        toolCallEvent("call-1", ""),
        toolCallEvent("call-2", "{not json")
      )
    )

    withSseServer(payload) { baseUrl =>
      val config = ZaiConfig(
        apiKey = "test-key",
        model = "test-model",
        baseUrl = baseUrl,
        contextWindow = 8192,
        reserveCompletion = 4096
      )

      val client   = new ZaiClient(config)
      val captured = scala.collection.mutable.ListBuffer.empty[ujson.Value]

      val result = client.streamComplete(
        Conversation(Seq(UserMessage("hello"))),
        CompletionOptions(),
        chunk => chunk.toolCall.foreach(tc => captured += tc.arguments)
      )

      result.isRight shouldBe true
      captured.toList shouldBe List(ujson.Obj(), ujson.Str("{not json"))
    }
  }

  "DeepSeekClient" should "preserve invalid tool-call arguments as raw string" in {
    val payload = ssePayload(
      Seq(
        toolCallEvent("call-1", ""),
        toolCallEvent("call-2", "{not json")
      )
    )

    withSseServer(payload) { baseUrl =>
      val config = DeepSeekConfig(
        apiKey = "test-key",
        model = "test-model",
        baseUrl = baseUrl,
        contextWindow = 8192,
        reserveCompletion = 4096
      )

      val client   = new DeepSeekClient(config)
      val captured = scala.collection.mutable.ListBuffer.empty[ujson.Value]

      val result = client.streamComplete(
        Conversation(Seq(UserMessage("hello"))),
        CompletionOptions(),
        chunk => chunk.toolCall.foreach(tc => captured += tc.arguments)
      )

      result.isRight shouldBe true
      captured.toList shouldBe List(ujson.Obj(), ujson.Str("{not json"))
    }
  }
}
