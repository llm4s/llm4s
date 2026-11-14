package org.llm4s.javaapi.services

import org.llm4s.javaapi.{ FakeLlmClient, GuideDocs, LlmErrorKind, Llm4s }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.*
import org.llm4s.testutil.TestTimeLimit
import org.llm4s.types.Result
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters.*

/**
 * `docs/guide/java-ai-services.md`, compiled and run (#2277). Its Java blocks are in `AiServiceSnippets.java`, which
 * javac compiles; this spec checks that each block is in that file, word for word, and runs the snippets against
 * scripted clients, pinning what the prose says they do.
 */
class JavaAiServicesGuideSpec extends AnyWordSpec with TestTimeLimit with Matchers {

  private val GuideFile = "docs/guide/java-ai-services.md"

  private lazy val guide: String = GuideDocs.read(GuideFile, GuideFile)

  private lazy val snippets: String = GuideDocs.squash(
    GuideDocs.read(GuideFile, "modules/java-api/src/test/java/org/llm4s/javaapi/services/AiServiceSnippets.java")
  )

  /** A client answering call `n` with `script(n)`, recording every conversation. */
  final private class Scripted(script: Int => AssistantMessage) extends LLMClient {
    val sent = ArrayBuffer.empty[Conversation]
    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
      val n       = sent.synchronized { sent += conversation; sent.size - 1 }
      val message = script(n)
      Right(Completion(s"s$n", Instant.EPOCH, message.content, "scripted", message))
    }
    override def streamComplete(c: Conversation, o: CompletionOptions, f: StreamedChunk => Unit): Result[Completion] =
      complete(c, o)
    override def getContextWindow(): Int     = 128000
    override def getReserveCompletion(): Int = 4096
  }

  "The guide" should {
    "hold only Java blocks that AiServiceSnippets.java compiles" in {
      val blocks = GuideDocs.blocksIn(guide, "java")
      blocks should not be empty
      blocks.foreach { block =>
        withClue(s"This block of $GuideFile is not in AiServiceSnippets.java:\n$block\n") {
          snippets should include(GuideDocs.squash(block))
        }
      }
    }
  }

  "The snippets" should {
    "build a service and call each of its methods" in {
      val fake = FakeLlmClient.replying(
        "Use the reset link.",
        """{"value":"POSITIVE"}""",
        """{"title":"Double charge","priority":1}"""
      )
      AiServiceSnippets.building(fake.client()) shouldBe "Use the reset link. | POSITIVE | Double charge"
      fake.prompts().asScala.toList shouldBe List(
        "How do I reset my password?",
        "Classify the sentiment of: The new release is great",
        "I was charged twice this month"
      )
    }

    "catch a failure, or read it from an LlmResult" in {
      val failing = FakeLlmClient
        .builder()
        .failWith(LlmErrorKind.RATE_LIMIT, "slow down")
        .failWith(LlmErrorKind.RATE_LIMIT, "slow down")
        .reply("""{"title":"Refund","priority":2}""")
        .build()
      val assistant =
        Llm4s.aiService(classOf[AiServiceSnippets.SupportAssistant]).client(failing.client()).build()
      AiServiceSnippets.failures(assistant) shouldBe "failed: RATE_LIMIT"
      AiServiceSnippets.failuresAsResult(assistant) shouldBe "try again later"
      AiServiceSnippets.failuresAsResult(assistant) shouldBe "Refund"
    }

    "answer asynchronously" in {
      val fake   = FakeLlmClient.replying("Leaves fall")
      val writer = Llm4s.aiService(classOf[AiServiceSnippets.Writer]).client(fake.client()).build()
      AiServiceSnippets.async(writer) shouldBe "Leaves fall"
      fake.lastPrompt().get() shouldBe "Write a haiku about autumn"
    }

    "keep each guest's conversation apart" in {
      val client = new Scripted(n => AssistantMessage(Seq("Hello Ada.", "Hello Alan.", "You are Ada.")(n)))
      AiServiceSnippets.memory(Llm4s.wrapClient(client)) shouldBe "You are Ada."
      client.sent(2).messages.map(_.content).toList shouldBe List(
        "You are a hotel concierge.",
        "My name is Ada.",
        "Hello Ada.",
        "What is my name?"
      )
    }

    "run a tool in the agent turn" in {
      val client = new Scripted(n =>
        if (n == 0)
          AssistantMessage(toolCalls = Seq(ToolCall("c1", "orderStatus", ujson.Obj("orderId" -> "42"))))
        else AssistantMessage("It shipped yesterday.")
      )
      AiServiceSnippets.tools(Llm4s.wrapClient(client)) shouldBe "It shipped yesterday."
      client.sent(1).messages.collect { case t: ToolMessage => t.content }.mkString should include(
        "Order 42 shipped yesterday"
      )
    }

    "fail at build() for an unknown placeholder" in {
      AiServiceSnippets.mistakes(FakeLlmClient.replying().client()) should include(
        "Mistaken.summarise: {{document}} is named by no @V parameter"
      )
    }

    "test a service with FakeLlmClient" in {
      AiServiceSnippets.testing().asScala.toList shouldBe List("POSITIVE", "Classify the sentiment of: Love it")
    }
  }
}
