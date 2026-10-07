package org.llm4s.context

import org.llm4s.llmconnect.model._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class DeterministicCompressorSpec extends AnyFlatSpec with Matchers {

  val counter: ConversationTokenCounter = ContextTestFixtures.createSimpleCounter()

  // ============ Basic Compression ============

  "DeterministicCompressor.compressToCap" should "return messages unchanged when under cap" in {
    val messages = Seq(
      UserMessage("Hello"),
      AssistantMessage("Hi there!")
    )
    val cap = 1000 // Large cap

    val result = DeterministicCompressor.compressToCap(messages, counter, cap)

    result.isRight shouldBe true
    result.toOption.get shouldBe messages
  }

  it should "apply tool compaction by default" in {
    // Create tool message with large JSON output
    val largeJson = s"""{"data": "${"x" * 10000}"}"""
    val messages = Seq(
      UserMessage("Get data"),
      AssistantMessage("Fetching..."),
      ToolMessage(largeJson, "call_1"),
      AssistantMessage("Here's the data.")
    )
    val cap = 500

    val result = DeterministicCompressor.compressToCap(messages, counter, cap)

    result.isRight shouldBe true
    val processed = result.toOption.get
    // Tool message should be externalized/compressed
    processed(2).content should not be largeJson
  }

  it should "skip subjective edits when disabled (default)" in {
    val messages = Seq(
      UserMessage("Question"),
      AssistantMessage("Um, well, you know, basically the answer is 42.")
    )
    val cap = 10 // Very small cap

    val result = DeterministicCompressor.compressToCap(
      messages,
      counter,
      cap,
      enableSubjectiveEdits = false
    )

    result.isRight shouldBe true
    // Content should remain unchanged (no filler word removal)
    val content = result.toOption.get(1).content
    content should include("well")
    content should include("you know")
  }

  it should "apply subjective rules when enabled and over cap" in {
    val fillerContent = "Um, well, you know, basically, like, the answer is 42. Well, you know what I mean."
    val messages = Seq(
      UserMessage("Question"),
      AssistantMessage(fillerContent)
    )
    val startTokens = counter.countConversation(Conversation(messages))
    val cap         = startTokens - 5 // Just under to trigger compression

    val result = DeterministicCompressor.compressToCap(
      messages,
      counter,
      cap,
      enableSubjectiveEdits = true
    )

    result.isRight shouldBe true
  }

  // ============ CompressionRule.removeFillerWords ============

  "CompressionRule.removeFillerWords" should "clean transcript-like content" in {
    val messages = Seq(
      AssistantMessage("Um, well, you know, basically I think, like, the answer is 42.")
    )

    val result = CompressionRule.removeFillerWords.apply(messages)

    // Some fillers should be removed from transcript-like content
    result.length shouldBe 1
    // Note: the rule is conservative and only applies to "transcript-like" content
  }

  it should "preserve code blocks unchanged" in {
    val codeContent =
      """```scala
        |def well = "you know"
        |val basically = 42
        |```""".stripMargin
    val messages = Seq(AssistantMessage(codeContent))

    val result = CompressionRule.removeFillerWords.apply(messages)

    result.head.content shouldBe codeContent
  }

  it should "preserve JSON content unchanged" in {
    val jsonContent = """{"well": "you know", "basically": {"like": 42}}"""
    val messages    = Seq(AssistantMessage(jsonContent))

    val result = CompressionRule.removeFillerWords.apply(messages)

    result.head.content shouldBe jsonContent
  }

  it should "never modify user messages" in {
    val userContent = "Um, well, you know, I need help"
    val messages    = Seq(UserMessage(userContent))

    val result = CompressionRule.removeFillerWords.apply(messages)

    result.head.content shouldBe userContent
  }

  // ============ CompressionRule.compressRepetitiveContent ============

  "CompressionRule.compressRepetitiveContent" should "deduplicate repeated sentences" in {
    val repetitive = "The answer is 42. The answer is 42. The answer is 42."
    val messages   = Seq(AssistantMessage(repetitive))

    val result = CompressionRule.compressRepetitiveContent.apply(messages)

    result.length shouldBe 1
    // Should mark repetitions
    val content = result.head.content
    content should include("×")
  }

  it should "handle single sentence content" in {
    val single   = "Just one sentence here"
    val messages = Seq(AssistantMessage(single))

    val result = CompressionRule.compressRepetitiveContent.apply(messages)

    result.head.content shouldBe single
  }

  // ============ CompressionRule.truncateVerboseResponses ============

  "CompressionRule.truncateVerboseResponses" should "truncate very long assistant responses" in {
    // Create content that's very long (> 400 estimated tokens)
    val longContent = (1 to 100).map(i => s"Sentence number $i is about topic $i.").mkString(" ")
    val messages    = Seq(AssistantMessage(longContent))

    val result = CompressionRule.truncateVerboseResponses.apply(messages)

    result.length shouldBe 1
    // Should be summarized/truncated
    result.head.content.length should be <= longContent.length
  }

  it should "unseal a signed response it truncates, so the provider never sees a modified signed turn" in {
    val longContent = (1 to 100).map(i => s"Sentence number $i is about topic $i.").mkString(" ")
    val signed = AssistantMessage(longContent).withThinking(
      Seq(ThinkingBlock.Text("reasoning", Some("sig")), ThinkingBlock.Redacted("opaque"))
    )

    val result = CompressionRule.truncateVerboseResponses.apply(Seq(signed))

    val truncated = result.collect { case a: AssistantMessage => a }.head
    (truncated.content should not).be(longContent)
    truncated.hasSealedThinking shouldBe false
    truncated.thinking shouldBe Seq(ThinkingBlock.Text("reasoning"))
  }

  it should "leave a signed response it does not change untouched" in {
    val signed = AssistantMessage("This is a short answer.").withThinking(
      Seq(ThinkingBlock.Text("reasoning", Some("sig")), ThinkingBlock.Redacted("opaque"))
    )
    CompressionRule.truncateVerboseResponses.apply(Seq(signed)) shouldBe Seq(signed)
    CompressionRule.removeRedundantPhrases.apply(Seq(signed)) shouldBe Seq(signed)
  }

  it should "unseal a signed response another rule rewrites" in {
    val signed = AssistantMessage("Like I said, the answer is 42.").withThinking(
      Seq(ThinkingBlock.Text("reasoning", Some("sig")), ThinkingBlock.Redacted("opaque"))
    )
    val result =
      CompressionRule.removeRedundantPhrases.apply(Seq(signed)).collect { case a: AssistantMessage => a }.head
    (result.content should not).include("Like I said")
    result.hasSealedThinking shouldBe false
  }

  it should "not truncate short responses" in {
    val shortContent = "This is a short answer."
    val messages     = Seq(AssistantMessage(shortContent))

    val result = CompressionRule.truncateVerboseResponses.apply(messages)

    result.head.content shouldBe shortContent
  }

  it should "never truncate user messages" in {
    val longUser = "A" * 2000
    val messages = Seq(UserMessage(longUser))

    val result = CompressionRule.truncateVerboseResponses.apply(messages)

    result.head.content shouldBe longUser
  }

  it should "never truncate tool messages" in {
    val longTool = """{"data": """ + "\"" + ("x" * 2000) + "\"}"
    val messages = Seq(ToolMessage(longTool, "call_1"))

    val result = CompressionRule.truncateVerboseResponses.apply(messages)

    result.head.content shouldBe longTool
  }

  // ============ CompressionRule.consolidateExamples ============

  "CompressionRule.consolidateExamples" should "consolidate multiple example messages" in {
    val messages = Seq(
      AssistantMessage("Here's an example: first one"),
      AssistantMessage("Another example: second one"),
      AssistantMessage("One more example: third one")
    )

    val result = CompressionRule.consolidateExamples.apply(messages)

    // Should consolidate into fewer messages
    result.length should be < messages.length
  }

  it should "not consolidate non-example messages" in {
    val messages = Seq(
      AssistantMessage("Regular message one"),
      AssistantMessage("Regular message two")
    )

    val result = CompressionRule.consolidateExamples.apply(messages)

    result.length shouldBe messages.length
  }

  // ============ CompressionRule.removeRedundantPhrases ============

  "CompressionRule.removeRedundantPhrases" should "remove 'as I mentioned before'" in {
    val messages = Seq(
      AssistantMessage("As I mentioned before, the answer is 42.")
    )

    val result = CompressionRule.removeRedundantPhrases.apply(messages)

    (result.head.content should not).include("As I mentioned before")
    result.head.content should include("42")
  }

  it should "remove 'like I said'" in {
    val messages = Seq(
      AssistantMessage("Like I said, we should use Scala.")
    )

    val result = CompressionRule.removeRedundantPhrases.apply(messages)

    (result.head.content should not).include("Like I said")
    result.head.content should include("Scala")
  }

  // ============ Edge Cases ============

  "DeterministicCompressor" should "handle empty message list" in {
    val result = DeterministicCompressor.compressToCap(Seq.empty, counter, 100)

    result.isRight shouldBe true
    result.toOption.get shouldBe empty
  }

  it should "handle mixed message types" in {
    val messages = Seq(
      SystemMessage("You are helpful"),
      UserMessage("Hello"),
      AssistantMessage("Hi!"),
      ToolMessage("result", "call_1"),
      AssistantMessage("Done")
    )
    val cap = 1000

    val result = DeterministicCompressor.compressToCap(messages, counter, cap)

    result.isRight shouldBe true
    result.toOption.get.length shouldBe 5
  }

  it should "preserve message order" in {
    val messages = (1 to 5).flatMap(i => Seq(UserMessage(s"Q$i"), AssistantMessage(s"A$i")))
    val cap      = 1000

    val result = DeterministicCompressor.compressToCap(messages, counter, cap)

    result.isRight shouldBe true
    val processed = result.toOption.get
    processed.zipWithIndex.foreach { case (msg, idx) =>
      if (idx % 2 == 0) msg.content should startWith("Q")
      else msg.content should startWith("A")
    }
  }
}
