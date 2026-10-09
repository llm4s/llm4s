package org.llm4s.llmconnect.provider

import com.sun.net.httpserver.HttpExchange
import org.llm4s.llmconnect.model.*
import org.llm4s.llmconnect.provider.BedrockTestSupport.*
import org.llm4s.model.ModelRegistryService
import org.llm4s.testkit.LocalProviderTestServer.{ sendJsonResponse, withServer }
import org.llm4s.toolapi.{ Schema, ToolBuilder }
import org.scalatest.OptionValues.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference

/**
 * What the client actually puts on the wire for Converse: golden request JSON for rich
 * conversations, tool-argument type fidelity, and the inference configuration. A local server
 * captures the request body the AWS SDK serialised, so the SDK's own marshalling runs.
 */
class BedrockConverseMappingSpec extends AnyWordSpec with Matchers {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private def read(exchange: HttpExchange): ujson.Value =
    ujson.read(new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8))

  /** The Converse request body the client sends for `conv`. */
  private def requestBody(conv: Conversation, options: CompletionOptions = CompletionOptions()): ujson.Value = {
    val seen = new AtomicReference[ujson.Value](ujson.Null)
    withServer("/") { ex =>
      seen.set(read(ex))
      sendJsonResponse(ex, 200, converseResponse("ok"))
    } { url =>
      val client = new BedrockClient(config(url))
      client.complete(conv, options).isRight shouldBe true
      client.close()
    }
    seen.get
  }

  private val weatherArgs = ujson.Obj(
    "city"   -> "Paris",
    "days"   -> 3,
    "ratio"  -> 0.5,
    "neg"    -> -7,
    "zero"   -> 0,
    "flag"   -> true,
    "none"   -> ujson.Null,
    "nested" -> ujson.Obj("a" -> ujson.Arr(1, 2.5, ujson.Obj("b" -> ujson.Null, "c" -> ujson.Arr()))),
    "uni"    -> "café \"quoted\"\n☃",
    "empty"  -> ujson.Obj()
  )

  "the Converse request" should {

    "keep tool-argument types exact: integers stay integers, floats stay floats, null and nesting survive" in {
      val conv = Conversation(
        Seq(
          UserMessage("go"),
          AssistantMessage(None, Seq(ToolCall("tc-1", "t", weatherArgs))),
          ToolMessage(toolCallId = "tc-1", content = "done")
        )
      )
      val rendered = requestBody(conv)("messages")(1)("content")(0)("toolUse")("input")
      rendered shouldBe weatherArgs
      // Equality of ujson.Num is numeric, so also pin the lexical form: an `integer` tool schema
      // rejects "3.0".
      val raw = rendered.render()
      raw should include("\"days\":3,")
      raw should include("\"ratio\":0.5")
      raw should include("\"neg\":-7")
      (raw should not).include("3.0")
    }

    "map a tool definition's schema, and omit toolConfig when there are no tools" in {
      val tool = ToolBuilder[Map[String, Any], String](
        "get_weather",
        "Returns current weather for a location",
        Schema
          .`object`[Map[String, Any]]("Weather params")
          .withProperty(Schema.property("location", Schema.string("City name")))
          .withProperty(Schema.property("days", Schema.integer("How many days"), required = false))
      ).withHandler(_ => Right("sunny")).buildSafe().toOption.value

      val conv    = Conversation(Seq(UserMessage("hi")))
      val withIt  = requestBody(conv, CompletionOptions(tools = Seq(tool)))
      val without = requestBody(conv)

      val spec = withIt("toolConfig")("tools")(0)("toolSpec")
      spec("name").str shouldBe "get_weather"
      spec("description").str shouldBe "Returns current weather for a location"
      val schema = spec("inputSchema")("json")
      schema("type").str shouldBe "object"
      schema("properties")("location")("type").str shouldBe "string"
      schema("properties")("days")("type").str shouldBe "integer"
      schema("required").arr.map(_.str).toSeq shouldBe Seq("location")
      without.obj.contains("toolConfig") shouldBe false
    }

    "send the model id in the path" in {
      val path = new AtomicReference[String]("")
      withServer("/") { ex =>
        path.set(ex.getRequestURI.getRawPath)
        sendJsonResponse(ex, 200, converseResponse("ok"))
      } { url =>
        val client = new BedrockClient(config(url, "us.anthropic.claude-3-5-sonnet-20241022-v2:0"))
        client.complete(Conversation(Seq(UserMessage("hi"))), CompletionOptions()).isRight shouldBe true
        client.close()
      }
      path.get should (startWith("/model/us.anthropic.claude-3-5-sonnet-20241022-v2").and(endWith("/converse")))
    }
  }

  "the Converse response" should {

    "decode tool-use input with nested values, floats, integers and null" in {
      val body =
        """{"output":{"message":{"role":"assistant","content":[{"text":"calling"},{"toolUse":{"toolUseId":"t1","name":"f","input":{"i":3,"f":2.5,"n":null,"o":{"a":[1,{"b":false}]},"s":"x"}}}]}},"stopReason":"tool_use","usage":{"inputTokens":1,"outputTokens":2,"totalTokens":3}}"""
      withServer("/")(sendJsonResponse(_, 200, body)) { url =>
        val client = new BedrockClient(config(url))
        val c      = client.complete(Conversation(Seq(UserMessage("x"))), CompletionOptions()).toOption.value
        client.close()
        c.content shouldBe "calling"
        c.toolCalls.head.arguments shouldBe ujson.Obj(
          "i" -> 3,
          "f" -> 2.5,
          "n" -> ujson.Null,
          "o" -> ujson.Obj("a" -> ujson.Arr(1, ujson.Obj("b" -> false))),
          "s" -> "x"
        )
      }
    }
  }
}
