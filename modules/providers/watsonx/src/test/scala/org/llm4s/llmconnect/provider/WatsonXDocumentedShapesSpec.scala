package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.model.*
import org.llm4s.model.ModelRegistryService
import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolFunction }
import org.scalatest.LoneElement
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/**
 * The shapes IBM's own documentation shows for tool calling on the chat API (the `DOCS` source in
 * [[WatsonXClient]]'s Scaladoc: "Building agent-driven workflows with the chat API",
 * https://www.ibm.com/docs/SSYOK8/wsj/analyze-data/fm-api-chat-tools.html). Its curl examples post to
 * `/ml/v1/text/chat?version=...` with `"tool_choice_option": "auto"`, and its Mistral Large reply carries
 * `"finish_reason": "tool_calls"` and the call
 * `{"id": "H6KoCbaZV", "type": "function", "function": {"name": "add", "arguments": "{\"a\": 2, \"b\": 4}"}}`,
 * whose `arguments` is a JSON string with spaces after the separators. The page quotes only that excerpt of the
 * reply; the envelope around it here (`id`, `choices`, `usage`) is the one the other suites use.
 */
class WatsonXDocumentedShapesSpec extends AnyFunSuite with Matchers with LoneElement:
  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()
  import StubHttp.*
  import WatsonXTestConfig.config

  private val addTool: ToolFunction[?, ?] = ToolBuilder[Map[String, Any], String](
    "add",
    "Adds two numbers",
    Schema
      .`object`[Map[String, Any]]("params")
      .withProperty(Schema.property("a", Schema.integer("first")))
      .withProperty(Schema.property("b", Schema.integer("second")))
  ).withHandler(_ => Right("6")).buildSafe().fold(e => fail(e.toString), identity)

  // the documented call, verbatim, inside the usual reply envelope
  private val documentedCall =
    """{"id": "H6KoCbaZV", "type": "function", "function": {"name": "add", "arguments": "{\"a\": 2, \"b\": 4}"}}"""

  private val documentedReply = org.llm4s.http.HttpResponse(
    200,
    s"""{"id":"chat-1","choices":[{"index":0,"message":{"role":"assistant","tool_calls":[$documentedCall]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":20,"completion_tokens":10,"total_tokens":30}}"""
  )

  private val conversation = Conversation(Seq(UserMessage("What is 2 plus 4?")))
  private val withTools    = CompletionOptions().withTools(Seq(addTool))

  test("a tool request goes to /ml/v1/text/chat with the version query parameter and tool_choice_option auto") {
    val http = routed(_ => Right(iamToken()), _ => Right(documentedReply))
    new WatsonXClient(config, httpClient = http).complete(conversation, withTools)
    val sent = http.modelRequests.loneElement
    sent.url shouldBe s"${config.baseUrl}/ml/v1/text/chat?version=${config.apiVersion}"
    val json = ujson.read(sent.body)
    json("tool_choice_option").str shouldBe "auto"
    json.obj.contains("tool_choice") shouldBe false
    val tool = json("tools").arr.loneElement
    tool("type").str shouldBe "function"
    tool("function")("name").str shouldBe "add"
  }

  test("the documented tool-call reply becomes one ToolCall with its id and parsed arguments") {
    val http       = routed(_ => Right(iamToken()), _ => Right(documentedReply))
    val completion = new WatsonXClient(config, httpClient = http).complete(conversation, withTools)
    val call       = completion.fold(e => fail(e.toString), identity).toolCalls.loneElement
    call.id shouldBe "H6KoCbaZV"
    call.name shouldBe "add"
    call.arguments shouldBe ujson.Obj("a" -> 2, "b" -> 4)
    completion.toOption.get.message.toolCalls.loneElement shouldBe call
    completion.toOption.get.content shouldBe ""
  }

  test("sending the documented call back: the assistant turn and the tool result use the documented field names") {
    val http = routed(_ => Right(iamToken()), _ => Right(chatReply))
    val followUp = Conversation(
      conversation.messages ++ Seq(
        AssistantMessage(None, Seq(ToolCall("H6KoCbaZV", "add", ujson.Obj("a" -> 2, "b" -> 4)))),
        ToolMessage("6", "H6KoCbaZV")
      )
    )
    new WatsonXClient(config, httpClient = http).complete(followUp, withTools).isRight shouldBe true
    val messages = ujson.read(http.modelRequests.loneElement.body)("messages").arr
    messages.map(_("role").str) shouldBe Seq("user", "assistant", "tool")
    val sentCall = messages(1)("tool_calls").arr.loneElement
    sentCall("id").str shouldBe "H6KoCbaZV"
    sentCall("type").str shouldBe "function"
    ujson.read(sentCall("function")("arguments").str) shouldBe ujson.Obj("a" -> 2, "b" -> 4)
    messages(2)("tool_call_id").str shouldBe "H6KoCbaZV"
    messages(2)("content").str shouldBe "6"
  }
