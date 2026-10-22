package org.llm4s.llmconnect.provider

import org.llm4s.http.HttpResponse
import org.llm4s.llmconnect.model.*
import org.llm4s.model.ModelRegistryService
import org.scalatest.EitherValues
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable.ListBuffer

/**
 * Reading a reply's reasoning: `reasoning_content` (else `reasoning`) on `complete`'s message and on a stream's
 * deltas, as thinking. The replies follow the ones IBM's Java SDK recorded from `openai/gpt-oss-120b`
 * (watsonx-ai-java-sdk, test resources `gpt_oss_thinking_response.txt` and `gpt_oss_thinking_streaming_response.txt`).
 */
class WatsonXReasoningSpec extends AnyFunSuite with Matchers with EitherValues:
  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()
  import StubHttp.*
  import WatsonXTestConfig.config

  private val gptOss = config.copy(model = "openai/gpt-oss-120b")
  private val ask    = Conversation(Seq(UserMessage("Horoscope for Aries?")))

  private def complete(reply: String): Completion =
    val http = routed(_ => Right(iamToken()), _ => Right(HttpResponse(200, reply)))
    new WatsonXClient(gptOss, httpClient = http).complete(ask, CompletionOptions()).value

  private def reply(message: String): String =
    s"""{"id":"chatcmpl-b41f","object":"chat.completion","model_id":"openai/gpt-oss-120b","choices":[{"index":0,"message":$message,"finish_reason":"stop"}],"usage":{"completion_tokens":522,"prompt_tokens":89,"total_tokens":611}}"""

  test("complete: message.reasoning_content is the completion's thinking, the content its text") {
    val c = complete(
      reply(
        """{"role":"assistant","content":"Aries Daily Horoscope","reasoning_content":"Need to give horoscope for Aries."}"""
      )
    )
    c.content shouldBe "Aries Daily Horoscope"
    c.thinking shouldBe Some("Need to give horoscope for Aries.")
    c.message.thinkingText shouldBe Some("Need to give horoscope for Aries.")
    c.message.hasSealedThinking shouldBe false
    c.fullContent shouldBe "<thinking>\nNeed to give horoscope for Aries.\n</thinking>\n\nAries Daily Horoscope"
  }

  test("complete: the usage carries no reasoning token count, so thinkingTokens stays empty") {
    val c = complete(reply("""{"role":"assistant","content":"a","reasoning_content":"r"}""")).usage.get
    c.promptTokens shouldBe 89
    c.completionTokens shouldBe 522
    c.totalTokens shouldBe 611
    c.thinkingTokens shouldBe None
  }

  test("complete: `reasoning` is read when `reasoning_content` is absent, and loses to it when both are there") {
    complete(reply("""{"role":"assistant","content":"a","reasoning":"from reasoning"}""")).thinking shouldBe
      Some("from reasoning")
    complete(reply("""{"role":"assistant","content":"a","reasoning_content":"rc","reasoning":"r"}""")).thinking shouldBe
      Some("rc")
    complete(reply("""{"role":"assistant","content":"a","reasoning_content":"","reasoning":"r"}""")).thinking shouldBe
      Some("r")
  }

  test("complete: no, empty, null or non-string reasoning is no thinking") {
    Seq(
      """{"role":"assistant","content":"a"}""",
      """{"role":"assistant","content":"a","reasoning_content":""}""",
      """{"role":"assistant","content":"a","reasoning_content":null}""",
      """{"role":"assistant","content":"a","reasoning_content":{"x":1},"reasoning":[1]}"""
    ).foreach { message =>
      val c = complete(reply(message))
      withClue(message) {
        c.thinking shouldBe None
        c.message.hasThinking shouldBe false
        c.content shouldBe "a"
      }
    }
  }

  test("complete: a reply of only reasoning and tool calls keeps both") {
    val c = complete(
      reply(
        """{"role":"assistant","reasoning_content":"I should add.","tool_calls":[{"id":"call-1","type":"function","function":{"name":"add","arguments":"{\"a\":1}"}}]}"""
      )
    )
    c.thinking shouldBe Some("I should add.")
    c.toolCalls.map(_.id) shouldBe List("call-1")
    c.message.content shouldBe ""
  }

  // ---- streams

  private def event(id: Int, delta: String, finish: String = "null"): String =
    s"""id: $id
       |event: message
       |data: {"id":"chatcmpl-3956","object":"chat.completion.chunk","model_id":"openai/gpt-oss-120b","choices":[{"index":0,"finish_reason":$finish,"delta":$delta}]}
       |
       |""".stripMargin

  private val usageEvent =
    """id: 99
      |event: message
      |data: {"id":"chatcmpl-3956","object":"chat.completion.chunk","model_id":"openai/gpt-oss-120b","choices":[],"usage":{"completion_tokens":27,"prompt_tokens":85,"total_tokens":112}}
      |
      |""".stripMargin

  private val recorded = Seq(
    event(1, """{"role":"assistant","content":""}"""),
    event(2, """{"reasoning_content":""}"""),
    event(3, """{"reasoning_content":"User"}"""),
    event(4, """{"reasoning_content":" wants"}"""),
    event(5, """{"reasoning_content":" translation"}"""),
    event(6, """{"reasoning_content":"."}"""),
    event(7, """{"reasoning_content":""}"""),
    event(8, """{"content":"Hola"}"""),
    event(9, """{"content":"!"}"""),
    event(10, """{"reasoning_content":""}""", "\"stop\""),
    usageEvent
  ).mkString

  private def stream(sse: String): (Completion, List[StreamedChunk]) =
    val chunks = ListBuffer.empty[StreamedChunk]
    val result = new WatsonXClient(gptOss, httpClient = streaming(streamOf(bytes(sse))))
      .streamComplete(ask, CompletionOptions().withReasoning(ReasoningEffort.Low), chunks += _)
    (result.value, chunks.toList)

  test("stream: delta.reasoning_content pieces become thinking deltas, in order, and the completion's thinking") {
    val (completion, chunks) = stream(recorded)
    chunks.flatMap(_.thinkingDelta) shouldBe List("User", " wants", " translation", ".")
    chunks.flatMap(_.content) shouldBe List("Hola", "!")
    completion.thinking shouldBe Some("User wants translation.")
    completion.content shouldBe "Hola!"
    completion.message.content shouldBe "Hola!"
  }

  test("stream: empty reasoning_content pieces are not passed on, and a thinking chunk carries no content") {
    val (_, chunks) = stream(recorded)
    chunks.filter(_.thinkingDelta.isDefined).foreach { chunk =>
      chunk.content shouldBe None
      chunk.toolCall shouldBe None
      chunk.finishReason shouldBe None
      chunk.id shouldBe "chatcmpl-3956"
    }
    chunks.exists(_.thinkingDelta.contains("")) shouldBe false
  }

  test("stream: the usage is read and reports no reasoning tokens") {
    val usage = stream(recorded)._1.usage.get
    (usage.promptTokens, usage.completionTokens, usage.thinkingTokens) shouldBe (85, 27, None)
  }

  test("stream: `reasoning` on a delta is read when `reasoning_content` is absent") {
    val sse = Seq(
      event(1, """{"reasoning":"think"}"""),
      event(2, """{"reasoning_content":"ing"}"""),
      event(3, """{"content":"done"}""", "\"stop\"")
    ).mkString
    val (completion, chunks) = stream(sse)
    chunks.flatMap(_.thinkingDelta) shouldBe List("think", "ing")
    completion.thinking shouldBe Some("thinking")
  }

  test("stream: a reply with no reasoning has no thinking") {
    val (completion, chunks) = stream(event(1, """{"content":"Hi"}""", "\"stop\""))
    chunks.flatMap(_.thinkingDelta) shouldBe empty
    completion.thinking shouldBe None
    completion.message.hasThinking shouldBe false
  }

  test("thinking from an earlier turn is not sent back: the assistant message has no field for it") {
    val turn = AssistantMessage("Hola!").withThinking("User wants translation.")
    val sent = new WatsonXClient(gptOss, httpClient = routed(_ => Right(iamToken()), _ => Right(chatReply)))
      .createRequestBody(Conversation(Seq(UserMessage("u"), turn, UserMessage("again"))), CompletionOptions())
      .value
    sent("messages")(1) shouldBe ujson.Obj("role" -> "assistant", "content" -> "Hola!")
    (sent.render() should not).include("User wants translation.")
  }
