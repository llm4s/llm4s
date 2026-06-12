package org.llm4s.gradle

import org.llm4s.llmconnect.model.{ SystemMessage, UserMessage }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ConversationTemplatesSpec extends AnyFlatSpec with Matchers {

  "ConversationTemplates.codeReview" should "return Right with a 2-message conversation" in {
    val result = ConversationTemplates.codeReview("def foo(): Int = 42")
    result shouldBe a[Right[_, _]]
    val conv = result.toOption.get
    conv.messages should have size 2
    conv.messages.head shouldBe a[SystemMessage]
    conv.messages.head.content should include("code reviewer")
    conv.messages(1) shouldBe a[UserMessage]
    conv.messages(1).content should include("def foo(): Int = 42")
  }

  it should "return Left when code is blank" in {
    ConversationTemplates.codeReview("   ") shouldBe a[Left[_, _]]
  }

  "ConversationTemplates.translate" should "include the target language in the system prompt" in {
    val result = ConversationTemplates.translate("Hello world", "French")
    result shouldBe a[Right[_, _]]
    val conv = result.toOption.get
    conv.messages should have size 2
    conv.messages.head shouldBe a[SystemMessage]
    conv.messages.head.content should include("French")
    conv.messages(1) shouldBe a[UserMessage]
    conv.messages(1).content shouldBe "Hello world"
  }

  it should "work with different target languages" in {
    val result = ConversationTemplates.translate("Bonjour", "German")
    result shouldBe a[Right[_, _]]
    result.toOption.get.messages.head.content should include("German")
  }

  it should "return Left when text is blank" in {
    ConversationTemplates.translate("", "French") shouldBe a[Left[_, _]]
  }

  "ConversationTemplates.summarize" should "set the document as user message" in {
    val doc    = "A long document about Scala programming."
    val result = ConversationTemplates.summarize(doc)
    result shouldBe a[Right[_, _]]
    val conv = result.toOption.get
    conv.messages should have size 2
    conv.messages.head shouldBe a[SystemMessage]
    conv.messages.head.content should include("ummariz")
    conv.messages(1) shouldBe a[UserMessage]
    conv.messages(1).content shouldBe doc
  }

  it should "return Left when document is blank" in {
    ConversationTemplates.summarize("  ") shouldBe a[Left[_, _]]
  }

  "ConversationTemplates.questionAnswer" should "embed context in the system prompt and question as user message" in {
    val ctx    = "The capital of France is Paris."
    val q      = "What is the capital of France?"
    val result = ConversationTemplates.questionAnswer(ctx, q)
    result shouldBe a[Right[_, _]]
    val conv = result.toOption.get
    conv.messages should have size 2
    conv.messages.head shouldBe a[SystemMessage]
    conv.messages.head.content should include(ctx)
    conv.messages(1) shouldBe a[UserMessage]
    conv.messages(1).content shouldBe q
  }

  it should "return Left when context is blank" in {
    ConversationTemplates.questionAnswer("", "question") shouldBe a[Left[_, _]]
  }

  it should "return Left when question is blank" in {
    ConversationTemplates.questionAnswer("context", "") shouldBe a[Left[_, _]]
  }

  "ConversationTemplates.extractJson" should "embed the schema in the system prompt and input as user message" in {
    val schema = """{"type":"object","properties":{"name":{"type":"string"}}}"""
    val input  = "John Smith is a software engineer."
    val result = ConversationTemplates.extractJson(input, schema)
    result shouldBe a[Right[_, _]]
    val conv = result.toOption.get
    conv.messages should have size 2
    conv.messages.head shouldBe a[SystemMessage]
    conv.messages.head.content should include(schema)
    conv.messages(1) shouldBe a[UserMessage]
    conv.messages(1).content shouldBe input
  }

  it should "return Left when input is blank" in {
    ConversationTemplates.extractJson("", "schema") shouldBe a[Left[_, _]]
  }

  it should "return Left when schema is blank" in {
    ConversationTemplates.extractJson("input", "") shouldBe a[Left[_, _]]
  }
}
