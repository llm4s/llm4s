package org.llm4s.llmconnect.provider

import org.llm4s.context.{ ArtifactStore, ContextTestFixtures, TokenWindow, ToolOutputCompressor }
import org.llm4s.llmconnect.model._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.{ read, write }

/**
 * Sealed thinking is valid only after the history it was produced after: Anthropic validates a
 * thinking block against the system prompt, the tools and every earlier message, and Bedrock
 * documents its reasoning signature as a hash of the conversation. [[ThinkingReplay]] binds it when
 * it arrives and unseals it at send time once anything before it has changed - whoever changed it.
 */
class ThinkingReplaySpec extends AnyFlatSpec with Matchers {

  private val options = CompletionOptions()
  private val call    = ToolCall("tc-1", "get_weather", ujson.Obj("city" -> "Paris"))
  private val sealedThinking =
    Seq(ThinkingBlock.Text("Weather.", Some("sig-1")), ThinkingBlock.Redacted("opaque-1"))

  private val origin = ReplayOrigin("anthropic", "claude-sonnet-4-5")

  private def answering(history: Message*)(message: AssistantMessage): AssistantMessage =
    ThinkingReplay.bind(origin, message, history, options)

  private def signedTurn(history: Message*): AssistantMessage =
    answering(history*)(AssistantMessage(None, Seq(call)).withThinking(sealedThinking))

  /** The sealed state of each assistant message once `messages` are made replayable. */
  private def sealedStates(
    messages: Seq[Message],
    opts: CompletionOptions = options,
    to: ReplayOrigin = origin
  ): Seq[Boolean] =
    ThinkingReplay.replayable(to, messages, opts).collect { case am: AssistantMessage => am.hasSealedThinking }

  "bind" should "bind sealed thinking only" in {
    signedTurn(UserMessage("hi")).thinkingBinding shouldBe defined
    answering(UserMessage("hi"))(AssistantMessage("plain").withThinking("text")).thinkingBinding shouldBe None
  }

  "replayable" should "keep sealed thinking while messages are only appended after it" in {
    val ask  = UserMessage("Weather?")
    val turn = signedTurn(ask)
    val conv = Seq(ask, turn, ToolMessage("sunny", "tc-1"), AssistantMessage("Sunny."), UserMessage("Thanks"))
    ThinkingReplay.replayable(origin, conv, options) shouldBe conv
  }

  it should "unseal a turn whose earlier history was pruned" in {
    val history = Seq(UserMessage("Hello"), AssistantMessage("Hi."), UserMessage("Weather?"))
    val turn    = signedTurn(history*)
    val sent =
      ThinkingReplay.replayable(origin, Seq(UserMessage("Weather?"), turn, ToolMessage("sunny", "tc-1")), options)
    val unsealed = sent(1).asInstanceOf[AssistantMessage]
    unsealed.hasSealedThinking shouldBe false
    unsealed.thinkingBinding shouldBe None
    unsealed.thinking shouldBe Seq(ThinkingBlock.Text("Weather."))
    unsealed.toolCalls shouldBe Seq(call)
  }

  it should "keep a turn sent back to the provider and model that produced it" in {
    val turn = signedTurn(UserMessage("Weather?"))
    sealedStates(Seq(UserMessage("Weather?"), turn), to = ReplayOrigin("anthropic", "claude-sonnet-4-5")) shouldBe
      Seq(true)
  }

  it should "unseal a turn sent to another provider, the history and options unchanged" in {
    // Anthropic must not be sent a Bedrock signature, nor Bedrock an Anthropic one
    val ask     = UserMessage("Weather?")
    val turn    = signedTurn(ask)
    val bedrock = ReplayOrigin("bedrock", "claude-sonnet-4-5")
    sealedStates(Seq(ask, turn), to = bedrock) shouldBe Seq(false)
    val fromBedrock = ThinkingReplay.bind(bedrock, turn, Seq(ask), options)
    fromBedrock.thinkingBinding should not be turn.thinkingBinding
    sealedStates(Seq(ask, fromBedrock)) shouldBe Seq(false)
  }

  it should "unseal a turn sent to another model of the same provider" in {
    val turn = signedTurn(UserMessage("Weather?"))
    sealedStates(Seq(UserMessage("Weather?"), turn), to = ReplayOrigin("anthropic", "claude-opus-4-1")) shouldBe
      Seq(false)
  }

  it should "after a model switch, replay each turn only to the model that produced it" in {
    val ask1  = UserMessage("Weather?")
    val turn1 = signedTurn(ask1)
    val res1  = ToolMessage("sunny", "tc-1")
    val ask2  = UserMessage("Again?")
    val other = ReplayOrigin("anthropic", "claude-opus-4-1")
    // the second turn came from the other model, which was sent the first unsealed
    val turn2 = ThinkingReplay.bind(
      other,
      AssistantMessage(None, Seq(call)).withThinking(sealedThinking),
      Seq(ask1, turn1, res1, ask2),
      options
    )
    sealedStates(Seq(ask1, turn1, res1, ask2, turn2), to = other) shouldBe Seq(false, true)
    // back on the first model: its own turn keeps, the other model's goes
    sealedStates(Seq(ask1, turn1, res1, ask2, turn2)) shouldBe Seq(true, false)
  }

  it should "unseal a turn after an earlier message was edited, or one was inserted before it" in {
    val turn = signedTurn(UserMessage("Weather?"))
    sealedStates(Seq(UserMessage("Weather in Paris?"), turn)) shouldBe Seq(false)
    sealedStates(Seq(UserMessage("Context: ..."), UserMessage("Weather?"), turn)) shouldBe Seq(false)
  }

  it should "unseal a turn after the system prompt changed, wherever the system message sits" in {
    val turn = signedTurn(SystemMessage("Be brief."), UserMessage("Weather?"))
    sealedStates(Seq(SystemMessage("Be brief."), UserMessage("Weather?"), turn)) shouldBe Seq(true)
    sealedStates(Seq(SystemMessage("Be terse."), UserMessage("Weather?"), turn)) shouldBe Seq(false)
    // the providers lift every system message into one prompt, so a later one changes it too
    sealedStates(
      Seq(SystemMessage("Be brief."), UserMessage("Weather?"), turn, SystemMessage("Also: metric."))
    ) shouldBe Seq(false)
  }

  it should "unseal a turn after a system message moved, since some clients send them inline" in {
    val system = SystemMessage("Be brief.")
    val ask    = UserMessage("Weather?")
    val turn   = signedTurn(system, ask)
    turn.thinkingBinding should not be signedTurn(ask, system).thinkingBinding
    sealedStates(Seq(system, ask, turn)) shouldBe Seq(true)
    sealedStates(Seq(ask, system, turn)) shouldBe Seq(false)
  }

  it should "unseal a turn after the response format changed" in {
    val turn = signedTurn(UserMessage("Weather?"))
    sealedStates(Seq(UserMessage("Weather?"), turn), options.withResponseFormat(ResponseFormat.Json)) shouldBe
      Seq(false)
  }

  it should "unseal sealed thinking no client bound" in {
    val handBuilt = AssistantMessage(None, Seq(call)).withThinking(sealedThinking)
    sealedStates(Seq(UserMessage("Weather?"), handBuilt)) shouldBe Seq(false)
  }

  it should "keep the turns before a change and unseal every turn after it, leaving no gap" in {
    val ask1  = UserMessage("Weather?")
    val turn1 = signedTurn(ask1)
    val res1  = ToolMessage("sunny", "tc-1")
    val ask2  = UserMessage("Again?")
    val turn2 = signedTurn(ask1, turn1, res1, ask2)
    val res2  = ToolMessage("still sunny", "tc-1")
    val ask3  = UserMessage("Once more?")
    val turn3 = signedTurn(ask1, turn1, res1, ask2, turn2, res2, ask3)

    sealedStates(Seq(ask1, turn1, res1, ask2, turn2, res2, ask3, turn3)) shouldBe Seq(true, true, true)
    // the second turn edited (unsealing it): the third, after it, must go too
    val edited = turn2.withContent("Checking again.")
    sealedStates(Seq(ask1, turn1, res1, ask2, edited, res2, ask3, turn3)) shouldBe Seq(true, false, false)
    // a result between the first and second turns rewritten: the first keeps, the rest go
    sealedStates(Seq(ask1, turn1, ToolMessage("[compressed]", "tc-1"), ask2, turn2, res2, ask3, turn3)) shouldBe
      Seq(true, false, false)
  }

  it should "bind to the request as it was sent, so a turn unsealed then stays consistent later" in {
    // turn1 was bound to a history since pruned; the next request sent it unsealed
    val turn1   = signedTurn(UserMessage("Hello"), UserMessage("Weather?"))
    val ask     = UserMessage("Weather?")
    val res1    = ToolMessage("sunny", "tc-1")
    val ask2    = UserMessage("Again?")
    val request = Seq(ask, turn1, res1, ask2)
    val turn2   = signedTurn(request*)
    // turn1 is unsealed again when sent, which is the history turn2 was produced after
    sealedStates(request :+ turn2) shouldBe Seq(false, true)
  }

  "context rewriters" should "unseal a later turn when TokenWindow trims the oldest messages" in {
    val history = Seq(UserMessage("A" * 400), AssistantMessage("B" * 400), UserMessage("Weather?"))
    val turn    = signedTurn(history*)
    val conv    = Conversation(history ++ Seq(turn, ToolMessage("sunny", "tc-1")))
    val window  = TokenWindow.trimToBudget(conv, ContextTestFixtures.createSimpleCounter(), 150).toOption.get
    window.wasTrimmed shouldBe true
    window.conversation.messages should contain(turn)
    sealedStates(window.conversation.messages) shouldBe Seq(false)
  }

  it should "unseal a later turn when ToolOutputCompressor rewrites an earlier tool result" in {
    val big        = ToolMessage("x" * 20000, "tc-0")
    val history    = Seq(UserMessage("Fetch"), AssistantMessage(None, Seq(ToolCall("tc-0", "fetch", ujson.Obj()))), big)
    val turn       = signedTurn((history :+ UserMessage("Weather?"))*)
    val conv       = history ++ Seq(UserMessage("Weather?"), turn, ToolMessage("sunny", "tc-1"))
    val compressed = ToolOutputCompressor.compressToolOutputs(conv, ArtifactStore.inMemory()).toOption.get
    compressed should not be conv
    compressed should contain(turn)
    sealedStates(conv).last shouldBe true
    sealedStates(compressed).last shouldBe false
  }

  "AssistantMessage" should "round-trip its binding through the codec, and keep JSON without it readable" in {
    val turn = signedTurn(UserMessage("hi"))
    read[AssistantMessage](write(turn)) shouldBe turn
    read[Message](write(turn: Message)) shouldBe turn
    (write(AssistantMessage("plain")) should not).include("thinkingBinding")
  }

  it should "drop the binding when its content, tool calls or thinking change" in {
    val turn = signedTurn(UserMessage("hi"))
    turn.withContent("changed").thinkingBinding shouldBe None
    turn.withToolCalls(Seq.empty).thinkingBinding shouldBe None
    turn.withThinking(sealedThinking).thinkingBinding shouldBe None
    turn.withContent(turn.contentOpt) shouldBe turn
  }
}
