package org.llm4s.llmconnect.provider

import org.llm4s.error.{ ProcessingError, ValidationError }
import org.llm4s.http.HttpResponse
import org.llm4s.llmconnect.model.*
import org.llm4s.model.ModelRegistryService
import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolFunction }
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import upickle.default.*

/** Behaviour a caller relies on: tools work end to end, abnormal endings fail, malformed replies are errors. */
class WatsonXContractSpec extends AnyFunSuite with Matchers:
  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()
  import StubHttp.*
  import WatsonXTestConfig.{ config, ApiKey }

  final private case class Pong(ok: Boolean)
  private given ReadWriter[Pong] = macroRW

  private val tool: ToolFunction[Map[String, Any], Pong] =
    ToolBuilder[Map[String, Any], Pong]("ping", "ping", Schema.`object`[Map[String, Any]]("none"))
      .withHandler(_ => Right(Pong(true)))
      .buildSafe()
      .getOrElse(fail("tool"))

  private val hi = Conversation(Seq(UserMessage("Hi")))

  private def reply(message: String, finish: String = "tool_calls"): HttpResponse =
    HttpResponse(
      200,
      s"""{"id":"g","choices":[{"index":0,"message":$message,"finish_reason":"$finish"}],"usage":{"prompt_tokens":7,"completion_tokens":2,"total_tokens":9}}"""
    )

  private def complete(response: HttpResponse, options: CompletionOptions = CompletionOptions()) =
    new WatsonXClient(config, httpClient = routed(_ => Right(iamToken()), _ => Right(response)))
      .complete(hi, options)

  test("tools are accepted: they go over the wire and the model's tool call comes back") {
    val http = routed(
      _ => Right(iamToken()),
      _ =>
        Right(
          reply(
            """{"role":"assistant","content":null,"tool_calls":[{"id":"call-1","type":"function","function":{"name":"ping","arguments":"{\"x\":1}"}}]}"""
          )
        )
    )
    val result = new WatsonXClient(config, httpClient = http).complete(hi, CompletionOptions().withTools(Seq(tool)))
    val call   = ToolCall("call-1", "ping", ujson.Obj("x" -> 1))
    val c      = result.getOrElse(fail(s"expected success, got $result"))
    c.toolCalls shouldBe List(call)
    c.message.toolCalls shouldBe Seq(call)
    c.content shouldBe ""
    c.usage.map(u => (u.promptTokens, u.completionTokens, u.totalTokens)) shouldBe Some((7, 2, 9))
    ujson.read(http.modelRequests.head.body)("tools")(0)("function")("name").str shouldBe "ping"
  }

  test("a reply with text and several calls keeps both, in order") {
    val c = complete(
      reply(
        """{"role":"assistant","content":"Checking.","tool_calls":[{"id":"a","function":{"name":"ping","arguments":"{}"}},{"id":"b","function":{"name":"ping","arguments":{"k":1}}}]}"""
      )
    ).getOrElse(fail("expected success"))
    c.content shouldBe "Checking."
    c.toolCalls.map(_.id) shouldBe List("a", "b")
    c.toolCalls.map(_.arguments) shouldBe List(ujson.Obj(), ujson.Obj("k" -> 1))
    c.message.content shouldBe "Checking."
  }

  test("a call without an id gets a generated one; an empty or null tool_calls is no calls") {
    val withoutId = complete(reply("""{"content":"x","tool_calls":[{"function":{"name":"ping"}}]}"""))
    withoutId.map(_.toolCalls.map(call => (call.id.startsWith("call_"), call.name, call.arguments))) shouldBe
      Right(List((true, "ping", ujson.Obj())))
    Seq("""{"content":"x","tool_calls":[]}""", """{"content":"x","tool_calls":null}""", """{"content":"x"}""")
      .foreach(message => withClue(message)(complete(reply(message)).map(_.toolCalls) shouldBe Right(Nil)))
  }

  test("a malformed tool call is a ProcessingError that does not echo the arguments or the key") {
    val malformed = Seq(
      """{"tool_calls":"nope"}""",
      """{"tool_calls":[1]}""",
      """{"tool_calls":[{"id":"a"}]}""",
      """{"tool_calls":[{"id":"a","function":{}}]}""",
      """{"tool_calls":[{"id":"a","function":{"name":""}}]}""",
      """{"tool_calls":[{"id":"a","function":{"name":"ping","arguments":"SECRETARGS"}}]}""",
      """{"tool_calls":[{"id":"a","function":{"name":"ping","arguments":"[1]"}}]}""",
      """{"tool_calls":[{"id":"a","function":{"name":"ping","arguments":5}}]}"""
    )
    malformed.foreach { message =>
      withClue(message) {
        complete(reply(message)).left.toOption match
          case Some(e: ProcessingError) =>
            (e.message + e.toString should not).include("SECRETARGS")
            (e.message + e.toString should not).include(ApiKey)
          case other => fail(s"expected ProcessingError, got $other")
      }
    }
  }

  test("a reply with a null or missing content is empty text, not a failure") {
    Seq("""{"role":"assistant","content":null}""", """{"role":"assistant"}""", "null").foreach { message =>
      withClue(message)(complete(reply(message, "stop")).map(_.content) shouldBe Right(""))
    }
  }

  test("a reply without usage still succeeds, with no usage") {
    val body =
      """{"id":"g","choices":[{"index":0,"message":{"role":"assistant","content":"x"},"finish_reason":"stop"}]}"""
    complete(HttpResponse(200, body)).map(c => (c.content, c.usage)) shouldBe Right(("x", None))
  }

  test("usage without a total sums the prompt and completion tokens") {
    val body =
      """{"choices":[{"message":{"content":"x"},"finish_reason":"stop"}],"usage":{"prompt_tokens":5,"completion_tokens":4}}"""
    complete(HttpResponse(200, body)).map(_.usage.map(_.totalTokens)) shouldBe Right(Some(9))
  }

  test("a reply with no choices is a ValidationError") {
    Seq("""{"choices":[]}""", "{}", """{"choices":[1]}""").foreach { body =>
      withClue(body)(
        complete(HttpResponse(200, body)).left.toOption.exists(_.isInstanceOf[ValidationError]) shouldBe true
      )
    }
  }

  test("error messages for abnormal endings and malformed replies never contain the API key") {
    val abnormal  = complete(reply("""{"content":"x"}""", "error"))
    val malformed = complete(reply("""{"tool_calls":"nope"}"""))
    val streamed = new WatsonXClient(config, httpClient = streaming(streamOf(bytes(""))))
      .streamComplete(hi, CompletionOptions(), _ => ())
    Seq(abnormal, malformed, streamed).foreach { r =>
      r.isLeft shouldBe true
      val text = r.left.toOption.map(e => e.toString + e.message).getOrElse("")
      (text should not).include(ApiKey)
    }
  }
