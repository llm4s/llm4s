package org.llm4s.java

import org.llm4s.llmconnect.model.{ AssistantMessage, SystemMessage, UserMessage }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ConversationBuilderSpec extends AnyFlatSpec with Matchers {

  "ConversationBuilder.create()" should "produce an empty conversation" in {
    ConversationBuilder.create().build().messages shouldBe empty
  }

  "system()" should "add a SystemMessage" in {
    val conv = ConversationBuilder.create().system("You are helpful.").build()
    conv.messages should have size 1
    conv.messages.head shouldBe a[SystemMessage]
    conv.messages.head.content shouldBe "You are helpful."
  }

  "user()" should "add a UserMessage" in {
    val conv = ConversationBuilder.create().user("Hello").build()
    conv.messages.head shouldBe a[UserMessage]
    conv.messages.head.content shouldBe "Hello"
  }

  "assistant()" should "add an AssistantMessage" in {
    val conv = ConversationBuilder.create().assistant("Hi there").build()
    conv.messages.head shouldBe a[AssistantMessage]
    conv.messages.head.content shouldBe "Hi there"
  }

  "chained calls" should "preserve insertion order" in {
    val conv = ConversationBuilder
      .create()
      .system("Be concise.")
      .user("What is Scala?")
      .assistant("A JVM language.")
      .user("And Kotlin?")
      .build()

    conv.messages should have size 4
    conv.messages(0) shouldBe a[SystemMessage]
    conv.messages(1) shouldBe a[UserMessage]
    conv.messages(2) shouldBe a[AssistantMessage]
    conv.messages(3) shouldBe a[UserMessage]
  }

  "build()" should "return independent snapshots" in {
    val builder = ConversationBuilder.create().user("First")
    val conv1   = builder.build()
    val conv2   = builder.user("Second").build()

    conv1.messages should have size 1
    conv2.messages should have size 2
  }
}
