package org.llm4s.testkit

import org.llm4s.error.SimpleError
import org.llm4s.http.Llm4sHttpClient
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.*
import org.llm4s.testkit.ToolMessageFormat.{ AnthropicMessages, OpenAIChat }
import org.llm4s.types.Result
import org.scalatest.exceptions.TestFailedException
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.*

class ToolResultContractSpec extends AnyFlatSpec with Matchers with ProviderModuleChecks:

  // ---- reference encoders, the shapes the two formats take ----

  private def openAI(messages: Seq[Message]): ujson.Value =
    ujson.Obj("messages" -> ujson.Arr.from(messages.map {
      case UserMessage(text)   => ujson.Obj("role" -> "user", "content" -> text)
      case SystemMessage(text) => ujson.Obj("role" -> "system", "content" -> text)
      case ToolMessage(text, id) =>
        ujson.Obj("role" -> "tool", "tool_call_id" -> id, "content" -> text)
      case a: AssistantMessage =>
        val calls = a.toolCalls.map { c =>
          ujson.Obj(
            "id"       -> c.id,
            "type"     -> "function",
            "function" -> ujson.Obj("name" -> c.name, "arguments" -> c.arguments.render())
          )
        }
        if calls.isEmpty then ujson.Obj("role" -> "assistant", "content" -> a.content)
        else ujson.Obj("role" -> "assistant", "content" -> ujson.Null, "tool_calls" -> ujson.Arr.from(calls))
    }))

  /** Consecutive tool messages share one user turn of `tool_result` blocks, as the Anthropic client sends them. */
  private def anthropic(messages: Seq[Message]): ujson.Value =
    val turns = messages.foldLeft(Vector.empty[ujson.Obj]) { (acc, message) =>
      message match
        case _: SystemMessage => acc
        case UserMessage(text) =>
          acc :+ ujson.Obj("role" -> "user", "content" -> ujson.Arr(ujson.Obj("type" -> "text", "text" -> text)))
        case a: AssistantMessage =>
          val text = Option.when(a.content.nonEmpty)(ujson.Obj("type" -> "text", "text" -> a.content)).toSeq
          val uses = a.toolCalls.map(c => ujson.Obj("type" -> "tool_use", "id" -> c.id, "name" -> c.name))
          acc :+ ujson.Obj("role" -> "assistant", "content" -> ujson.Arr.from(text ++ uses))
        case ToolMessage(text, id) =>
          val block = ujson.Obj("type" -> "tool_result", "tool_use_id" -> id, "content" -> text)
          acc.lastOption match
            case Some(last)
                if last("role").str == "user" && last("content").arr.forall(_("type").str == "tool_result") =>
              last("content").arr += block
              acc
            case _ => acc :+ ujson.Obj("role" -> "user", "content" -> ujson.Arr(block))
    }
    ujson.Obj("messages" -> ujson.Arr.from(turns))

  private def call(id: String) = ToolCall(id, "lookup", ujson.Obj())
  private val user             = UserMessage("go")

  "every case" should "be a conversation that keeps the contract in both formats" in {
    ToolResultContract.cases.foreach { c =>
      withClue(c.name) {
        Seq(OpenAIChat -> openAI(c.conversation.messages), AnthropicMessages -> anthropic(c.conversation.messages))
          .foreach { (format, body) =>
            ToolResultContract.violations(format, body) shouldBe empty
            ToolResultContract.toolCallCount(format, body) shouldBe c.toolCalls
            ToolResultContract.toolResultCount(format, body) shouldBe c.toolResults
          }
        Message.validateConversation(c.conversation.messages.toList) shouldBe Right(())
      }
    }
    ToolResultContract.cases.map(_.name).distinct should have size ToolResultContract.cases.size.toLong
  }

  "violations" should "find a call with no result, in either format" in {
    val messages =
      Seq(user, AssistantMessage(contentOpt = None, toolCalls = Seq(call("a"), call("b"))), ToolMessage("x", "a"))
    ToolResultContract.violations(OpenAIChat, openAI(messages)) shouldBe
      Vector("message 1: tool call 'b' has no result straight after it")
    ToolResultContract.violations(AnthropicMessages, anthropic(messages)) shouldBe
      Vector("message 1: tool call 'b' has no result straight after it")
  }

  it should "find a second result for one call, and a result that answers no call" in {
    val messages = Seq(
      user,
      AssistantMessage(contentOpt = None, toolCalls = Seq(call("a"))),
      ToolMessage("x", "a"),
      ToolMessage("y", "a"),
      ToolMessage("z", "q")
    )
    Seq(OpenAIChat -> openAI(messages), AnthropicMessages -> anthropic(messages)).foreach { (format, body) =>
      ToolResultContract.violations(format, body) shouldBe Vector(
        "message 1: tool call 'a' has 2 results",
        "message 1: a tool result for 'q' answers no call"
      )
    }
  }

  it should "find a result a user message separates from its call" in {
    val messages =
      Seq(
        user,
        AssistantMessage(contentOpt = None, toolCalls = Seq(call("a"))),
        UserMessage("wait"),
        ToolMessage("x", "a")
      )
    ToolResultContract.violations(OpenAIChat, openAI(messages)) shouldBe Vector(
      "message 1: tool call 'a' has no result straight after it",
      "message 3: a tool message follows no assistant message with tool_calls"
    )
    ToolResultContract.violations(AnthropicMessages, anthropic(messages)) shouldBe Vector(
      "message 1: tool call 'a' has no result straight after it",
      "message 3: tool_result blocks follow no message with tool_use blocks"
    )
  }

  it should "pair positionally, so a reused id does not hide a call with no result" in {
    val messages = Seq(
      user,
      AssistantMessage(contentOpt = None, toolCalls = Seq(call("call_0"))),
      ToolMessage("first", "call_0"),
      AssistantMessage("done"),
      UserMessage("again"),
      AssistantMessage(contentOpt = None, toolCalls = Seq(call("call_0")))
    )
    // the id-based check is satisfied by the first turn's result
    Message.validateConversation(messages.toList) shouldBe Right(())
    ToolResultContract.violations(OpenAIChat, openAI(messages)) shouldBe
      Vector("message 5: tool call 'call_0' has no result straight after it")
    ToolResultContract.violations(AnthropicMessages, anthropic(messages)) shouldBe
      Vector("message 5: tool call 'call_0' has no result straight after it")
  }

  it should "find a repeated and a blank call id" in {
    val messages =
      Seq(
        user,
        AssistantMessage(contentOpt = None, toolCalls = Seq(call("a"), call("a"), call(""))),
        ToolMessage("x", "a")
      )
    ToolResultContract.violations(OpenAIChat, openAI(messages)) shouldBe Vector(
      "message 1: a tool call has no id",
      "message 1: tool call id 'a' is used more than once"
    )
  }

  it should "find an Anthropic tool_result that follows a text block" in {
    val body = ujson.Obj(
      "messages" -> ujson.Arr(
        ujson.Obj("role" -> "user", "content"      -> "go"),
        ujson.Obj("role" -> "assistant", "content" -> ujson.Arr(ujson.Obj("type" -> "tool_use", "id" -> "a"))),
        ujson.Obj(
          "role" -> "user",
          "content" -> ujson.Arr(
            ujson.Obj("type" -> "text", "text"               -> "note"),
            ujson.Obj("type" -> "tool_result", "tool_use_id" -> "a")
          )
        )
      )
    )
    ToolResultContract.violations(AnthropicMessages, body) shouldBe
      Vector("message 2: a tool_result block follows another block")
  }

  it should "read a body with no messages, or string contents, as nothing to check" in {
    ToolResultContract.violations(OpenAIChat, ujson.Obj()) shouldBe empty
    ToolResultContract.violations(AnthropicMessages, ujson.Str("not an object")) shouldBe empty
    val body = ujson.Obj("messages" -> ujson.Arr(ujson.Obj("role" -> "assistant", "content" -> "hi")))
    ToolResultContract.toolCallCount(AnthropicMessages, body) shouldBe 0
    ToolResultContract.toolResultCount(OpenAIChat, body) shouldBe 0
  }

  // ---- the assertion, through a client posting to the local server ----

  /** Posts the conversation as `encode` renders it, after `rewrite`; or fails without posting. */
  final private class PostingClient(
    url: String,
    encode: Seq[Message] => ujson.Value,
    rewrite: Seq[Message] => Seq[Message] = identity,
    failWith: Option[String] = None
  ) extends LLMClient:
    private val http = Llm4sHttpClient.create()
    def complete(c: Conversation, o: CompletionOptions): Result[Completion] =
      failWith match
        case Some(message) => Left(SimpleError(message))
        case None =>
          http
            .post(url, Map("Content-Type" -> "application/json"), encode(rewrite(c.messages)).render(), 10.seconds)
            .map(_ => Completion("id", 0L, "done", "test", AssistantMessage("done")))
    def streamComplete(c: Conversation, o: CompletionOptions, onChunk: StreamedChunk => Unit): Result[Completion] =
      complete(c, o)
    def getContextWindow(): Int     = 1000
    def getReserveCompletion(): Int = 100

  "assertOneToolResultPerCall" should "pass for a client that sends every call and result, paired" in {
    assertOneToolResultPerCall(OpenAIChat, LocalProviderTestServer.openAICompletion("done"))(base =>
      PostingClient(base, openAI)
    )
    assertOneToolResultPerCall(AnthropicMessages, LocalProviderTestServer.anthropicMessage("done"), "/v1/messages")(
      base => PostingClient(s"$base/v1/messages", anthropic)
    )
  }

  it should "fail for a client that drops results it cannot pair, even though what it sends is valid" in {
    // drops every tool message, and the calls with them: a valid body that loses the conversation's tool use
    val dropping = (messages: Seq[Message]) =>
      messages.collect {
        case a: AssistantMessage if a.toolCalls.nonEmpty =>
          AssistantMessage(if a.content.nonEmpty then a.content else "(tools)")
        case m if !m.isInstanceOf[ToolMessage] => m
      }
    val failure = the[TestFailedException] thrownBy
      assertOneToolResultPerCall(OpenAIChat, LocalProviderTestServer.openAICompletion("done"))(base =>
        PostingClient(base, openAI, dropping)
      )
    failure.getMessage should include("but the request carries 0 calls and 0 native results")
  }

  it should "fail for a client whose request breaks the contract" in {
    val unanswered = (messages: Seq[Message]) => messages.filterNot(_.isInstanceOf[ToolMessage])
    val failure = the[TestFailedException] thrownBy
      assertOneToolResultPerCall(OpenAIChat, LocalProviderTestServer.openAICompletion("done"))(base =>
        PostingClient(base, openAI, unanswered)
      )
    failure.getMessage should include("breaks the tool-result contract")
    failure.getMessage should include("has no result straight after it")
  }

  it should "fail for a client whose call returns Left" in {
    val failure = the[TestFailedException] thrownBy
      assertOneToolResultPerCall(OpenAIChat, LocalProviderTestServer.openAICompletion("done"))(base =>
        PostingClient(base, openAI, failWith = Some("refused"))
      )
    failure.getMessage should include("complete returned Left(refused)")
  }

  "anthropicMessage" should "be a text response in the Anthropic Messages format" in {
    val json = ujson.read(LocalProviderTestServer.anthropicMessage("hi", "claude-x"))
    json("type").str shouldBe "message"
    json("model").str shouldBe "claude-x"
    json("content")(0)("text").str shouldBe "hi"
  }
