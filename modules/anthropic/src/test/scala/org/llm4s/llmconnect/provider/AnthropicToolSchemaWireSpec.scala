package org.llm4s.llmconnect.provider

import org.llm4s.types.ProviderModelTypes.BaseUrl
import org.llm4s.types.ProviderModelTypes.ModelName
import com.sun.net.httpserver.HttpServer
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.config.AnthropicConfig
import org.llm4s.llmconnect.model.{ Completion, CompletionOptions, Conversation, UserMessage }
import org.llm4s.model.ModelRegistryService
import org.llm4s.security.Secret
import org.llm4s.toolapi.{ Schema, SchemaDefinition, ToolBuilder, ToolFunction }
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference

/** #2903: a tool's `input_schema` on the wire keeps its `required` lists, and a `Schema.raw` tool is sent as written. */
class AnthropicToolSchemaWireSpec extends AnyFlatSpec with Matchers {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private def tool(schema: SchemaDefinition[?]): ToolFunction[String] =
    ToolBuilder[String]("get_weather", "The weather in a city", schema)
      .withHandler(_ => Right("ok"))
      .buildSafe()
      .toOption
      .get

  private val reply =
    """{"id":"m","type":"message","role":"assistant","model":"x","content":[{"type":"text","text":"ok"}],
      |"stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":1,"output_tokens":1}}""".stripMargin

  private val streamReply =
    """event: message_start
      |data: {"type":"message_start","message":{"id":"m","type":"message","role":"assistant","model":"x","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":1,"output_tokens":0}}}
      |
      |event: content_block_start
      |data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}
      |
      |event: content_block_delta
      |data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"ok"}}
      |
      |event: content_block_stop
      |data: {"type":"content_block_stop","index":0}
      |
      |event: message_delta
      |data: {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":1}}
      |
      |event: message_stop
      |data: {"type":"message_stop"}
      |
      |""".stripMargin

  /** The request body sent (if any) and the result, against a local server; `stream` uses `streamComplete`. */
  private def sent(t: ToolFunction[?], stream: Boolean = false): (Option[ujson.Value], Result[Completion]) = {
    val seen   = new AtomicReference[String]()
    val server = HttpServer.create(new InetSocketAddress("localhost", 0), 0)
    server.createContext(
      "/v1/messages",
      exchange => {
        seen.set(new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8))
        val bytes = (if (stream) streamReply else reply).getBytes(StandardCharsets.UTF_8)
        exchange.getResponseHeaders.add("Content-Type", if (stream) "text/event-stream" else "application/json")
        exchange.sendResponseHeaders(200, bytes.length)
        exchange.getResponseBody.write(bytes)
        exchange.close()
      }
    )
    server.start()
    try {
      val config = AnthropicConfig(
        apiKey = Secret("test-key"),
        model = ModelName("claude-sonnet-4-6"),
        baseUrl = BaseUrl(s"http://localhost:${server.getAddress.getPort}"),
        contextWindow = 200000,
        reserveCompletion = 4096
      )
      val client       = new AnthropicClient(config)
      val conversation = Conversation(Seq(UserMessage("Weather in Paris?")))
      val options      = CompletionOptions(tools = Seq(t))
      val result =
        if (stream) client.streamComplete(conversation, options)(_ => ())
        else client.complete(conversation, options)
      (Option(seen.get()).map(ujson.read(_)), result)
    } finally server.stop(0)
  }

  private val weather = Schema
    .`object`[Map[String, Any]]("Weather query")
    .withProperty(Schema.property("city", Schema.string("City")))
    .withProperty(Schema.property("unit", Schema.string("Unit"), required = false))

  "A tool's input_schema" should "carry the top-level required list" in {
    val (body, result) = sent(tool(weather))
    result.isRight shouldBe true
    val schema = body.get("tools")(0)("input_schema")
    schema("type") shouldBe ujson.Str("object")
    schema("required") shouldBe ujson.Arr("city")
    schema("properties").obj.keySet shouldBe Set("city", "unit")
    schema.obj.keySet should not contain "additionalProperties"
    schema.obj.keySet should not contain "strict"
  }

  it should "carry the required list when streamed too" in {
    val (body, result) = sent(tool(weather), stream = true)
    result.isRight shouldBe true
    body.get("tools")(0)("input_schema")("required") shouldBe ujson.Arr("city")
  }

  it should "keep a nested object's required list intact" in {
    val schema = Schema
      .`object`[Map[String, Any]]("Order")
      .withProperty(Schema.property("id", Schema.string("Id")))
      .withProperty(
        Schema.property(
          "address",
          Schema
            .`object`[Map[String, Any]]("Address")
            .withProperty(Schema.property("street", Schema.string("Street")))
            .withProperty(Schema.property("note", Schema.string("Note"), required = false))
        )
      )
    val (body, _)  = sent(tool(schema))
    val sentSchema = body.get("tools")(0)("input_schema")
    sentSchema("required") shouldBe ujson.Arr("id", "address")
    sentSchema("properties")("address")("required") shouldBe ujson.Arr("street")
    sentSchema("properties")("address").obj.keySet should not contain "additionalProperties"
  }

  "A Schema.raw tool" should "be sent as written" in {
    val raw = ujson.Obj(
      "type" -> "object",
      "properties" -> ujson.Obj(
        "city" -> ujson.Obj("type" -> "string", "pattern" -> "^[A-Z]"),
        "unit" -> ujson.Obj("$ref" -> "#/$defs/unit")
      ),
      "required"             -> ujson.Arr("city"),
      "$defs"                -> ujson.Obj("unit" -> ujson.Obj("enum" -> ujson.Arr("c", "f"))),
      "additionalProperties" -> false
    )
    val (body, result) = sent(tool(Schema.raw(raw)))
    result.isRight shouldBe true
    body.get("tools")(0)("input_schema") shouldBe raw
  }

  it should "be sent as written when streamed" in {
    val raw            = ujson.Obj("type" -> "object", "properties" -> ujson.Obj(), "required" -> ujson.Arr())
    val (body, result) = sent(tool(Schema.raw(raw)), stream = true)
    result.isRight shouldBe true
    body.get("tools")(0)("input_schema") shouldBe raw
  }

  it should "fail the request with a Left, sending nothing, when it is not an object schema" in {
    val (body, result) = sent(tool(Schema.raw(ujson.Obj("type" -> "string"))))
    body shouldBe None
    result.left.toOption.get shouldBe a[ValidationError]
    result.left.toOption.get.message should include("get_weather")
  }

  it should "fail a streamed request with a Left too" in {
    val (body, result) = sent(tool(Schema.raw(ujson.Obj("type" -> "string"))), stream = true)
    body shouldBe None
    result.isLeft shouldBe true
  }
}
