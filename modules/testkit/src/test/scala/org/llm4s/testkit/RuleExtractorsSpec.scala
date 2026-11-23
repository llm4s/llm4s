package org.llm4s.testkit

import org.llm4s.llmconnect.model._
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** The extractors for `ScriptedLLMClient.respondingTo` rules (#2730), and `Conversation.lastUserText`. */
class RuleExtractorsSpec extends AnyFlatSpec with Matchers with EitherValues {

  private val options = CompletionOptions()

  private def conversation(messages: Message*): Conversation = Conversation(messages)

  private def call(id: String, name: String): ToolCall = ToolCall(id, name, ujson.Obj())

  private def calling(calls: ToolCall*): AssistantMessage = AssistantMessage("", calls)

  private val correction = OutputCorrection.message(Seq("$.total: expected a number"))

  "Conversation.lastUserText" should "be the text of the latest user message, wherever it is" in {
    conversation(
      SystemMessage("be brief"),
      UserMessage("first"),
      AssistantMessage("one"),
      UserMessage("second"),
      AssistantMessage("two")
    ).lastUserText shouldBe Some("second")
  }

  it should "be None for a conversation without a user message" in {
    conversation(SystemMessage("be brief"), AssistantMessage("hello")).lastUserText shouldBe None
    conversation().lastUserText shouldBe None
  }

  it should "be what RecordedCall.lastUserText reports" in {
    val sent = conversation(UserMessage("first"), AssistantMessage("one"), UserMessage("second"))
    RecordedCall(sent, options).lastUserText shouldBe sent.lastUserText
    RecordedCall(conversation(), options).lastUserText shouldBe None
  }

  "EndsWithUser" should "extract the text of a user message that ends the conversation" in {
    EndsWithUser.unapply(conversation(SystemMessage("be brief"), UserMessage("take lamp"))) shouldBe Some("take lamp")
  }

  it should "not match when the conversation ends with anything else, or is empty" in {
    EndsWithUser.unapply(conversation(UserMessage("take lamp"), AssistantMessage("taken"))) shouldBe None
    EndsWithUser.unapply(
      conversation(UserMessage("take lamp"), calling(call("c1", "add")), ToolMessage("ok", "c1"))
    ) shouldBe None
    EndsWithUser.unapply(conversation()) shouldBe None
  }

  it should "match the agent's correction request too, which is a user message" in {
    EndsWithUser.unapply(
      conversation(UserMessage("bill Acme"), AssistantMessage("{}"), UserMessage(correction))
    ) shouldBe
      Some(correction)
  }

  "LastUser" should "extract the latest user message even when later messages follow it" in {
    LastUser.unapply(
      conversation(UserMessage("take lamp"), calling(call("c1", "add")), ToolMessage("ok", "c1"))
    ) shouldBe Some("take lamp")
  }

  it should "not match a conversation without a user message" in {
    LastUser.unapply(conversation(SystemMessage("be brief"))) shouldBe None
  }

  "AfterToolResult" should "extract the tool's name and its result when a tool result ends the conversation" in {
    AfterToolResult.unapply(
      conversation(UserMessage("take lamp"), calling(call("c1", "add_inventory_item")), ToolMessage("added", "c1"))
    ) shouldBe Some(("add_inventory_item", "added"))
  }

  it should "extract the last result when several tool calls were answered at once" in {
    AfterToolResult.unapply(
      conversation(
        UserMessage("take lamp and look"),
        calling(call("c1", "add_inventory_item"), call("c2", "look")),
        ToolMessage("added", "c1"),
        ToolMessage("a dark room", "c2")
      )
    ) shouldBe Some(("look", "a dark room"))
  }

  it should "name the tool by the call's id, not by the order of the results" in {
    AfterToolResult.unapply(
      conversation(
        UserMessage("take lamp and look"),
        calling(call("c1", "add_inventory_item"), call("c2", "look")),
        ToolMessage("a dark room", "c2"),
        ToolMessage("added", "c1")
      )
    ) shouldBe Some(("add_inventory_item", "added"))
  }

  it should "resolve the name from the nearest assistant message, so an id used again in a later turn is not confused" in {
    AfterToolResult.unapply(
      conversation(
        UserMessage("look"),
        calling(call("call-1", "look")),
        ToolMessage("a dark room", "call-1"),
        AssistantMessage("It is dark."),
        UserMessage("take lamp"),
        calling(call("call-1", "add_inventory_item")),
        ToolMessage("added", "call-1")
      )
    ) shouldBe Some(("add_inventory_item", "added"))
  }

  it should "not match when the conversation does not end with a tool result" in {
    AfterToolResult.unapply(
      conversation(
        UserMessage("take lamp"),
        calling(call("c1", "add")),
        ToolMessage("added", "c1"),
        AssistantMessage("Taken.")
      )
    ) shouldBe None
    AfterToolResult.unapply(conversation(UserMessage("take lamp"))) shouldBe None
    AfterToolResult.unapply(conversation()) shouldBe None
  }

  it should "not match a result whose tool call cannot be found, rather than guess a name" in {
    // no assistant message before it
    AfterToolResult.unapply(conversation(UserMessage("take lamp"), ToolMessage("added", "c1"))) shouldBe None
    // the assistant message before it made a different call
    AfterToolResult.unapply(
      conversation(UserMessage("take lamp"), calling(call("c9", "look")), ToolMessage("added", "c1"))
    ) shouldBe None
    // the call is in an earlier turn, not in the message these results answer
    AfterToolResult.unapply(
      conversation(
        calling(call("c1", "add")),
        ToolMessage("added", "c1"),
        AssistantMessage("Taken."),
        ToolMessage("again", "c1")
      )
    ) shouldBe None
  }

  "AfterToolResults" should "extract every result that ends the conversation, in conversation order" in {
    AfterToolResults.unapply(
      conversation(
        UserMessage("take lamp and look"),
        calling(call("c1", "add_inventory_item"), call("c2", "look")),
        ToolMessage("added", "c1"),
        ToolMessage("a dark room", "c2")
      )
    ) shouldBe Some(Seq("add_inventory_item" -> "added", "look" -> "a dark room"))
  }

  it should "extract only the results of the last round of tool calls" in {
    AfterToolResults.unapply(
      conversation(
        calling(call("c1", "look")),
        ToolMessage("a dark room", "c1"),
        calling(call("c2", "add")),
        ToolMessage("added", "c2")
      )
    ) shouldBe Some(Seq("add" -> "added"))
  }

  it should "not match when the conversation does not end with a tool result" in {
    AfterToolResults.unapply(conversation(UserMessage("look"))) shouldBe None
    AfterToolResults.unapply(conversation()) shouldBe None
  }

  it should "not match when any of the results cannot be named" in {
    AfterToolResults.unapply(
      conversation(calling(call("c1", "look")), ToolMessage("a dark room", "c1"), ToolMessage("added", "c2"))
    ) shouldBe None
  }

  "AfterCorrection" should "match a conversation that ends with the agent's correction request" in {
    AfterCorrection.unapply(
      conversation(UserMessage("bill Acme"), AssistantMessage("""{"total":"lots"}"""), UserMessage(correction))
    ) shouldBe true
  }

  it should "not match any other ending" in {
    AfterCorrection.unapply(conversation(UserMessage("bill Acme"))) shouldBe false
    AfterCorrection.unapply(conversation(UserMessage(correction), AssistantMessage("{}"))) shouldBe false
    AfterCorrection.unapply(conversation(AssistantMessage(correction))) shouldBe false
    AfterCorrection.unapply(conversation()) shouldBe false
  }

  "The extractors" should "script the issue's example through respondingTo" in {
    val answer = "You take the lamp."
    val client = ScriptedLLMClient.respondingTo {
      case EndsWithUser(s"take $item")              => Reply.toolCall("add_inventory_item", s"""{"item":"$item"}""")
      case AfterToolResult("add_inventory_item", _) => Reply.text(answer)
    }

    val first = client.complete(conversation(UserMessage("take lamp")), options).value
    first.toolCalls.map(_.name) shouldBe Seq("add_inventory_item")
    first.toolCalls.map(_.arguments) shouldBe Seq(ujson.Obj("item" -> "lamp"))

    val second = client
      .complete(
        conversation(UserMessage("take lamp"), first.message, ToolMessage("added", first.toolCalls.head.id)),
        options
      )
      .value
    second.content shouldBe answer

    client.complete(conversation(UserMessage("drop lamp")), options).left.value shouldBe a[UnscriptedCallError]
  }

  it should "tell a turn's first call, its tool round, its correction and a later turn apart" in {
    val client = ScriptedLLMClient.respondingTo {
      case AfterCorrection()                             => Reply.text("corrected")
      case AfterToolResults(results) if results.size > 1 => Reply.text(results.map(_._1).mkString("+"))
      case AfterToolResult(tool, content)                => Reply.text(s"$tool said $content")
      case EndsWithUser(text)                            => Reply.text(s"asked $text")
      case LastUser(text)                                => Reply.text(s"still about $text")
    }
    def reply(messages: Message*): String = client.complete(conversation(messages*), options).value.content

    reply(UserMessage("look")) shouldBe "asked look"
    reply(UserMessage("look"), calling(call("c1", "look")), ToolMessage("dark", "c1")) shouldBe "look said dark"
    reply(
      UserMessage("look"),
      calling(call("c1", "look"), call("c2", "listen")),
      ToolMessage("dark", "c1"),
      ToolMessage("quiet", "c2")
    ) shouldBe "look+listen"
    reply(UserMessage("look"), AssistantMessage("{}"), UserMessage(correction)) shouldBe "corrected"
    reply(UserMessage("look"), AssistantMessage("It is dark.")) shouldBe "still about look"
  }
}
