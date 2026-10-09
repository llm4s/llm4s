package org.llm4s.llmconnect.provider

import com.sun.net.httpserver.HttpServer
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.config.OllamaConfig
import org.llm4s.llmconnect.model._
import org.llm4s.model.ModelRegistryService
import org.llm4s.toolapi.Schema
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import upickle.default.{ macroRW, ReadWriter }

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters._

/**
 * Ollama maps [[CompletionOptions.responseFormat]] to the `/api/chat` `format` field:
 * absent for `None`, the string "json" for `Json`, the schema object for `JsonSchema`.
 */
class OllamaStructuredOutputSpec extends AnyFunSuite with Matchers with BeforeAndAfterAll {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  case class Person(name: String, age: Int)
  object Person { implicit val rw: ReadWriter[Person] = macroRW }

  private val personSchema = Schema
    .`object`[Person]("A person")
    .withRequiredField("name", Schema.string("Name"))
    .withRequiredField("age", Schema.integer("Age"))

  // Nested objects, arrays, enums and non-ASCII text, to catch any re-wrapping or re-encoding.
  private val complexSchema: ujson.Value = ujson.read("""{
    "type": "object",
    "properties": {
      "city": {"type": "string", "enum": ["Zürich", "東京", "São Paulo"]},
      "tags": {"type": "array", "items": {"type": "string"}},
      "address": {
        "type": "object",
        "properties": {"street": {"type": "string", "description": "Straße \"quoted\""}, "zip": {"type": "integer"}},
        "required": ["street"],
        "additionalProperties": false
      }
    },
    "required": ["city", "address"],
    "additionalProperties": false
  }""")

  private val conv = Conversation(Seq(UserMessage("hi")))

  private def mkClient(baseUrl: String = "http://localhost:11434"): OllamaClient =
    new OllamaClient(OllamaConfig(model = "llama3.1", baseUrl = baseUrl, contextWindow = 4096, reserveCompletion = 512))

  private def body(rf: Option[ResponseFormat], stream: Boolean, opts: CompletionOptions = CompletionOptions()) =
    mkClient().createRequestBody(conv, rf.fold(opts)(opts.withResponseFormat), stream)

  // ---- request body ----

  test("createRequestBody: send no format key when responseFormat is None (both modes)") {
    body(None, stream = false).obj.contains("format") shouldBe false
    body(None, stream = true).obj.contains("format") shouldBe false
  }

  test("createRequestBody: send the string \"json\" for ResponseFormat.Json in both modes") {
    body(Some(ResponseFormat.Json), stream = false)("format") shouldBe ujson.Str("json")
    body(Some(ResponseFormat.Json), stream = true)("format") shouldBe ujson.Str("json")
  }

  test("createRequestBody: send exactly the schema object for JsonSchema in both modes, ignoring name and strict") {
    val a = body(Some(ResponseFormat.JsonSchema(complexSchema)), stream = false)
    val b = body(Some(ResponseFormat.JsonSchema(complexSchema, name = "other", strict = false)), stream = true)
    a("format") shouldBe complexSchema
    b("format") shouldBe complexSchema
    // no wrapper keys such as json_schema / name / strict
    b("format").obj.keySet shouldBe Set("type", "properties", "required", "additionalProperties")
    a("format")("properties")("city")("enum").arr.map(_.str).toSeq shouldBe Seq("Zürich", "東京", "São Paulo")
  }

  test("createRequestBody: keep the rest of the body intact when a format is set") {
    val opts = CompletionOptions(temperature = 0.3, topP = 0.8, maxTokens = Some(77))
    val b    = body(Some(ResponseFormat.JsonSchema(complexSchema)), stream = true, opts)
    b("model").str shouldBe "llama3.1"
    b("stream").bool shouldBe true
    b("messages").arr.map(m => (m("role").str, m("content").str)).toSeq shouldBe Seq(("user", "hi"))
    b("options")("temperature").num shouldBe 0.3
    b("options")("top_p").num shouldBe 0.8
    b("options")("num_predict").num shouldBe 77
    b.obj.keySet shouldBe Set("model", "messages", "stream", "options", "format")
  }

  test("createRequestBody: survive rendering and parsing with the schema unchanged (non-ASCII included)") {
    val rendered = body(Some(ResponseFormat.JsonSchema(complexSchema)), stream = false).render()
    ujson.read(rendered)("format") shouldBe complexSchema
  }

  // ---- over HTTP ----

  private val stub               = new OllamaStructuredOutputSpec.StubServer
  private def received           = stub.received
  override def beforeAll(): Unit = stub.server.start()
  override def afterAll(): Unit  = stub.server.stop(0)

  private def serverClient = mkClient(s"http://localhost:${stub.server.getAddress.getPort}")
  private def chatReply(content: String): String =
    ujson.Obj("message" -> ujson.Obj("role" -> "assistant", "content" -> content)).render()

  test("completeStructured: send the schema as format and parse a conforming reply") {
    received.clear()
    stub.reply = chatReply("""{"name":"Ada","age":36}""")
    serverClient.completeStructured[Person](conv, personSchema) shouldBe Right(Person("Ada", 36))
    val sent = ujson.read(received.asScala.toList.head)
    sent("stream").bool shouldBe false
    sent("format") shouldBe personSchema.toJsonSchema(strict = true)
    sent("format")("properties").obj.keySet shouldBe Set("name", "age")
  }

  test("completeStructured: recover a reply wrapped in a code fence") {
    stub.reply = chatReply("```json\n{\"name\":\"Ada\",\"age\":36}\n```")
    serverClient.completeStructured[Person](conv, personSchema) shouldBe Right(Person("Ada", 36))
  }

  test("completeStructured: recover a reply wrapped in prose") {
    stub.reply = chatReply("""Sure! Here you go: {"name":"Ada","age":36} Hope that helps.""")
    serverClient.completeStructured[Person](conv, personSchema) shouldBe Right(Person("Ada", 36))
  }

  test("completeStructured: return a ValidationError when the reply does not match the schema") {
    stub.reply = chatReply("""{"name":"Ada","age":"old"}""")
    serverClient.completeStructured[Person](conv, personSchema) match {
      case Left(e: ValidationError) => e.message should include("does not match expected schema")
      case other                    => fail(s"expected ValidationError, got $other")
    }
  }

  test("completeStructured: return a ValidationError when the reply is not JSON") {
    stub.reply = chatReply("I cannot do that")
    serverClient.completeStructured[Person](conv, personSchema).left.map(_.getClass) shouldBe
      Left(classOf[ValidationError])
  }

  test("complete: send format json over HTTP for ResponseFormat.Json and none by default") {
    received.clear()
    stub.reply = chatReply("{}")
    serverClient.complete(conv, CompletionOptions().withResponseFormat(ResponseFormat.Json)).isRight shouldBe true
    serverClient.complete(conv, CompletionOptions()).isRight shouldBe true
    val sent = received.asScala.toList.map(ujson.read(_))
    sent.head("format") shouldBe ujson.Str("json")
    sent(1).obj.contains("format") shouldBe false
  }

  test("streamComplete: send the schema as format on the streaming request") {
    received.clear()
    stub.reply = """{"message":{"content":"{\"name\":\"Ada\",\"age\":36}"},"done":true}""" + "\n"
    val res = serverClient.streamComplete(
      conv,
      CompletionOptions().withResponseFormat(ResponseFormat.JsonSchema(complexSchema)),
      _ => ()
    )
    res.isRight shouldBe true
    val sent = ujson.read(received.asScala.toList.head)
    sent("stream").bool shouldBe true
    sent("format") shouldBe complexSchema
  }
}

object OllamaStructuredOutputSpec {

  /** In-process stand-in for `/api/chat`: records request bodies and replies with `reply`. */
  final class StubServer {
    val received: ConcurrentLinkedQueue[String] = new ConcurrentLinkedQueue[String]()
    @volatile var reply: String                 = "{}"
    val server: HttpServer                      = HttpServer.create(new InetSocketAddress("localhost", 0), 0)
    server.createContext(
      "/api/chat",
      exchange => {
        received.add(new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8))
        val out = reply.getBytes(StandardCharsets.UTF_8)
        exchange.sendResponseHeaders(200, out.length.toLong)
        exchange.getResponseBody.write(out)
        exchange.close()
      }
    )
  }
}
