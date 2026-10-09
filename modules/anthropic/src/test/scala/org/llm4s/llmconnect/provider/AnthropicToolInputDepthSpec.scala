package org.llm4s.llmconnect.provider

import com.anthropic.core.ObjectMappers
import com.anthropic.models.messages.MessageCreateParams
import com.sun.net.httpserver.{ HttpExchange, HttpServer }
import org.llm4s.llmconnect.config.AnthropicConfig
import org.llm4s.llmconnect.model._
import org.llm4s.model.ModelRegistryService
import org.llm4s.testutil.SmallStack
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * A `tool_use` block's `input` is the model's own JSON. The SDK's Jackson parser admits it up to about
 * 1,000 levels of nesting, which is deeper than the 512 the library handles (#1562): a value that deep
 * can be accepted as a `ToolCall` and then rendered back to Anthropic on the next turn, which recurses
 * once per level. The input is therefore measured against the same bound as every other model-written
 * JSON before it is parsed, and an input over it is a malformed tool call, as it is for Ollama.
 */
class AnthropicToolInputDepthSpec extends AnyFlatSpec with Matchers {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private val Model = "claude-3-5-sonnet-latest"

  private def withServer[A](body: String)(test: AnthropicClient => A): A = {
    val server = HttpServer.create(new InetSocketAddress("localhost", 0), 0)
    server.createContext(
      "/v1/messages",
      (exchange: HttpExchange) => {
        val bytes = body.getBytes(StandardCharsets.UTF_8)
        exchange.getResponseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.length)
        val os = exchange.getResponseBody
        os.write(bytes)
        os.close()
      }
    )
    server.start()
    val client = new AnthropicClient(
      AnthropicConfig(
        apiKey = "test-key",
        model = Model,
        baseUrl = s"http://localhost:${server.getAddress.getPort}",
        contextWindow = 200000,
        reserveCompletion = 4096
      )
    )
    try test(client)
    finally server.stop(0)
  }

  /** A reply whose only block is a `tool_use` of `get_weather` with the given `input`. */
  private def toolUseReply(input: String): String =
    s"""{"id":"msg_1","type":"message","role":"assistant","model":"$Model",
       |"content":[{"type":"tool_use","id":"toolu_1","name":"get_weather","input":$input}],
       |"stop_reason":"tool_use","stop_sequence":null,"usage":{"input_tokens":8,"output_tokens":4}}""".stripMargin

  /** `{"a":{"a":...1...}}`, `n` objects deep. */
  private def nested(n: Int): String = "{\"a\":" * n + "1" + "}" * n

  private def ask(client: AnthropicClient): org.llm4s.types.Result[Completion] =
    client.complete(Conversation(Seq(UserMessage("weather?"))), CompletionOptions())

  /** The request body of the turn that answers `completion`'s tool call, as Anthropic would receive it. */
  private def nextTurnBody(client: AnthropicClient, assistant: AssistantMessage): ujson.Value = {
    val builder = MessageCreateParams.builder().model(Model).maxTokens(1024)
    val turn    = Conversation(Seq(UserMessage("weather?"), assistant, ToolMessage("Sunny", "toolu_1")))
    client.addMessagesToParams(turn, builder, CompletionOptions())
    ujson.read(ObjectMappers.jsonMapper().writeValueAsString(builder.build()._body()))
  }

  "AnthropicClient" should "refuse a tool_use input nested too deeply as a malformed call, never send it back" in {
    // 600 levels: past the library's 512, within what the SDK's parser admits. On a 1 MB stack, so that
    // an overflow anywhere in the round trip - reading the reply, or rendering the call back in the
    // next turn - is reported here as a Left instead of depending on the JVM's default stack size.
    withServer(toolUseReply(nested(600))) { client =>
      val outcome = SmallStack.run {
        ask(client) match {
          case Right(completion) =>
            val body = nextTurnBody(client, completion.message)
            Right(s"sent the call back in ${ujson.write(body).length} bytes")
          case Left(e) => Left((e.getClass.getSimpleName, e.message))
        }
      }
      outcome match {
        case Right(Left((kind, message))) =>
          kind shouldBe "ProcessingError"
          message should include("malformed tool call")
          // the site's own wording, not the parser error's: the refusal was told apart as too deep (#1651)
          message should include("arguments are nested more than 512 levels deep")
        case Right(Right(other)) => fail(s"expected a Left, but the reply was accepted and $other")
        case Left(thrown)        => fail(s"expected a Left, but the round trip threw $thrown")
      }
    }
  }

  it should "accept a tool_use input nested to the limit" in {
    withServer(toolUseReply(nested(512))) { client =>
      val completion = ask(client).fold(e => fail(s"expected a completion, got ${e.message}"), identity)
      completion.toolCalls.map(_.name) shouldBe List("get_weather")
      completion.toolCalls.head.arguments.obj.keySet shouldBe Set("a")
    }
  }

  it should "send an empty object for string arguments nested too deeply, never the parsed document" in {
    // Arguments that are a string are model text a stream left unparsed; one this deep is never parsed
    // into the request, because rendering the object it would build overflows the stack (#1562).
    val deep = ToolCall("toolu_1", "get_weather", ujson.Str(nested(100000)))
    withServer(toolUseReply("{}")) { client =>
      val outcome = SmallStack.run {
        val body  = nextTurnBody(client, AssistantMessage(None, Seq(deep)))
        val input = body("messages")(1)("content")(0)("input")
        (input == ujson.Obj(), ujson.write(body).length < 10000)
      }
      outcome shouldBe Right((true, true))
    }
  }
}
