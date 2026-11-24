package org.llm4s.context

import org.llm4s.error.ContextError
import org.llm4s.llmconnect.model._
import org.llm4s.types.{ ArtifactKey, HeadroomPercent, SemanticBlockId }
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.Base64

/** Context compression never loses content it cannot give back, and keeps its documented promises (#2817). */
class ContextContentLossSpec extends AnyFlatSpec with Matchers with EitherValues {

  private val counter = ContextTestFixtures.createSimpleCounter()

  private def text(m: Message): String = m.content

  private val pointer = """\[EXTERNALIZED: (content_[0-9a-f]+)""".r

  // ============ Externalization needs a store ============

  "DeterministicCompressor.compressToCap" should "not externalize a large tool output when no store is given" in {
    val big      = (1 to 2000).map(i => s"line $i of the report").mkString("\n")
    val messages = Seq(UserMessage("report?"), ToolMessage(big, ToolCallId("call_1")))

    val out = DeterministicCompressor.compressToCap(messages, counter, capTokens = 100).value

    (out.map(text).mkString should not).include("[EXTERNALIZED")
  }

  it should "externalize into the given store, so the pointer can be resolved" in {
    val big      = (1 to 2000).map(i => s"line $i of the report").mkString("\n")
    val store    = ArtifactStore.inMemory()
    val messages = Seq(UserMessage("report?"), ToolMessage(big, ToolCallId("call_1")))

    val out = DeterministicCompressor.compressToCap(messages, counter, 100, Some(store)).value
    val key = pointer.findFirstMatchIn(out.map(text).mkString).map(_.group(1)).getOrElse(fail("no pointer"))

    store.retrieve(ArtifactKey(key)).value shouldBe Some(big)
  }

  "ContextManager" should "not externalize tool output when it has no artifact store" in {
    val big     = (1 to 2000).map(i => s"line $i of the report").mkString("\n")
    val manager = ContextManager.create(counter).value
    val managed =
      manager
        .manageContext(Conversation(Seq(UserMessage("report?"), ToolMessage(big, ToolCallId("call_1")))), 100000)
        .value

    (managed.conversation.messages.map(text).mkString should not).include("[EXTERNALIZED")
  }

  // ============ Binary detection ============

  "ToolOutputCompressor" should "not treat prose that mentions base64 as binary" in {
    val prose = ("The API returns the image as base64 in the `data` field; decode it before use. " * 40).trim
    val out =
      ToolOutputCompressor.compressToolOutputs(Seq(ToolMessage(prose, ToolCallId("c"))), ArtifactStore.inMemory()).value

    (out.head.content should not).include("BINARY CONTENT")
  }

  it should "store binary content it takes out of the conversation" in {
    val bytes   = Array.tabulate[Byte](3000)(i => (i * 31 % 256).toByte)
    val encoded = Base64.getEncoder.encodeToString(bytes)
    val store   = ArtifactStore.inMemory()

    val out = ToolOutputCompressor.compressToolOutputs(Seq(ToolMessage(encoded, ToolCallId("c"))), store).value
    val key = pointer.findFirstMatchIn(out.head.content).map(_.group(1)).getOrElse(fail("no pointer"))

    store.retrieve(ArtifactKey(key)).value shouldBe Some(encoded)
  }

  it should "store a data URI it takes out of the conversation" in {
    val uri   = "data:image/png;base64," + Base64.getEncoder.encodeToString(Array.fill[Byte](3000)(7))
    val store = ArtifactStore.inMemory()

    val out = ToolOutputCompressor.compressToolOutputs(Seq(ToolMessage(uri, ToolCallId("c"))), store).value
    val key = pointer.findFirstMatchIn(out.head.content).map(_.group(1)).getOrElse(fail("no pointer"))

    store.retrieve(ArtifactKey(key)).value shouldBe Some(uri)
  }

  // ============ TokenWindow ============

  "TokenWindow.trimToBudget" should "pin a leading system message, as its Scaladoc says" in {
    val system = SystemMessage("You are a careful assistant.")
    val conv   = Conversation(system +: ContextTestFixtures.largeConversation.messages)

    val window = TokenWindow.trimToBudget(conv, counter, 100).value

    window.conversation.messages.head shouldBe system
  }

  it should "pin every leading digest, not only the first" in {
    val d1   = SystemMessage("[HISTORY_SUMMARY]\nfirst block")
    val d2   = SystemMessage("[HISTORY_SUMMARY]\nsecond block")
    val conv = Conversation(Seq(d1, d2) ++ ContextTestFixtures.largeConversation.messages)

    val window = TokenWindow.trimToBudget(conv, counter, 100).value

    window.conversation.messages.take(2) shouldBe Seq(d1, d2)
  }

  it should "not start the kept history at a tool result whose call was dropped" in {
    val call   = AssistantMessage("", toolCalls = List(ToolCall(ToolCallId("t1"), "lookup", "{}")))
    val result = ToolMessage("r" * 40, ToolCallId("t1"))
    val conv = Conversation(
      Seq(UserMessage("u" * 200), call, result, AssistantMessage("done"))
    )
    // Room for the tool result and the answer, not for the call as well.
    val budget = counter.countMessage(result) + counter.countMessage(AssistantMessage("done")) + 2

    val window = TokenWindow.trimToBudget(conv, counter, budget, headroom = HeadroomPercent.Zero).value

    window.conversation.messages.headOption.collect { case t: ToolMessage => t } shouldBe None
    window.conversation.messages shouldBe Seq(AssistantMessage("done"))
  }

  it should "fail when nothing but the pinned messages fits, instead of returning them alone" in {
    val conv = Conversation(Seq(SystemMessage("sys"), UserMessage("u" * 400)))

    TokenWindow.trimToBudget(conv, counter, 20, headroom = HeadroomPercent.Zero).left.value shouldBe a[ContextError]
  }

  it should "fail when the pinned messages alone exceed the budget" in {
    val conv = Conversation(Seq(SystemMessage("s" * 400), UserMessage("hi")))

    TokenWindow.trimToBudget(conv, counter, 20, headroom = HeadroomPercent.Zero).left.value shouldBe a[ContextError]
  }

  // ============ User messages are never modified ============

  "DeterministicCompressor with subjective edits" should "never modify a user message" in {
    val repetitive = UserMessage(("Please fix the bug. " * 30).trim)
    val messages   = Seq(repetitive, AssistantMessage("ok"))

    val out = DeterministicCompressor.compressToCap(messages, counter, 1, enableSubjectiveEdits = true).value

    out.head shouldBe repetitive
  }

  // ============ SemanticBlock ============

  "SemanticBlock.getBlockSummary" should "not throw on an empty block" in {
    SemanticBlock.apply(SemanticBlockId.generate(), Seq.empty, SemanticBlockType.StandaloneAssistant).getBlockSummary
    SemanticBlock.apply(SemanticBlockId.generate(), Seq.empty, SemanticBlockType.StandaloneTool).getBlockSummary
  }
}
