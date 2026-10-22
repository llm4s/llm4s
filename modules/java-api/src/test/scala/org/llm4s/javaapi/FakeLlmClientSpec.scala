package org.llm4s.javaapi

import org.llm4s.llmconnect.model.{ Conversation, StreamedChunk, UserMessage }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.{ Callable, Executors, TimeUnit }
import scala.jdk.CollectionConverters.*

/** `FakeLlmClient` (#1497): a scripted fake model for testing Java code that calls llm4s. */
class FakeLlmClientSpec extends AnyFlatSpec with Matchers {

  "FakeLlmClient.replying" should "answer each call with the next reply, and record each prompt" in {
    val fake = FakeLlmClient.replying("one", "two")
    fake.client().complete("first").get() shouldBe "one"
    fake.client().completion("second").get().content() shouldBe "two"
    fake.callCount() shouldBe 2
    fake.prompts().asScala.toList shouldBe List("first", "second")
    fake.lastPrompt().get() shouldBe "second"
  }

  it should "fail a call beyond the script with a message naming the call and its prompt" in {
    val fake = FakeLlmClient.replying("only")
    fake.client().complete("a").get() shouldBe "only"
    val result = fake.client().complete("an extra question")
    result.isFailure shouldBe true
    result.getError().getMessage should include("no reply scripted for call 2 (the script has 1)")
    result.getError().getMessage should include("an extra question")
    fake.callCount() shouldBe 2
  }

  it should "start with no calls" in {
    val fake = FakeLlmClient.replying()
    fake.callCount() shouldBe 0
    fake.prompts().isEmpty shouldBe true
    fake.lastPrompt().isPresent shouldBe false
  }

  "FakeLlmClient.builder" should "fail a call with an error of each kind, which getKind reads back" in {
    LlmErrorKind.values().foreach { kind =>
      val result = FakeLlmClient.builder().failWith(kind, "scripted").build().client().complete("q")
      withClue(kind) {
        result.isFailure shouldBe true
        result.getError().getKind shouldBe kind
      }
    }
  }

  it should "mix replies and failures in order, and read a null kind as OTHER" in {
    val fake = FakeLlmClient.builder().reply("ok").failWith(null, null).reply("again").build()
    fake.client().complete("1").get() shouldBe "ok"
    fake.client().complete("2").getError().getKind shouldBe LlmErrorKind.OTHER
    fake.client().complete("3").get() shouldBe "again"
  }

  it should "record the last user message of a conversation, and an empty prompt when there is none" in {
    val fake = FakeLlmClient.replying("a", "b")
    fake.client().complete(Conversation(Seq(UserMessage("earlier"), UserMessage("latest")))).get() shouldBe "a"
    fake.client().complete(Conversation(Seq.empty)).get() shouldBe "b"
    fake.prompts().asScala.toList shouldBe List("latest", "")
  }

  it should "stream its reply as one chunk, and report a context window" in {
    val fake   = FakeLlmClient.replying("streamed")
    val chunks = List.newBuilder[StreamedChunk]
    fake.client().underlying.streamComplete(Conversation(Seq(UserMessage("s"))), onChunk = c => chunks += c) match {
      case Right(c) => c.content shouldBe "streamed"
      case Left(e)  => fail(e.message)
    }
    chunks.result().flatMap(_.content) shouldBe List("streamed")
    fake.client().underlying.getContextWindow() should be > 0
    fake.client().underlying.getReserveCompletion() should be > 0
  }

  it should "give each of several concurrent calls its own reply" in {
    val replies = (1 to 50).map(i => s"r$i")
    val fake    = FakeLlmClient.replying(replies*)
    val pool    = Executors.newFixedThreadPool(8)
    try {
      val answers = pool
        .invokeAll((1 to 50).map(i => (() => fake.client().complete(s"q$i").get()): Callable[String]).asJava)
        .asScala
        .map(_.get(30, TimeUnit.SECONDS))
      answers.toSet shouldBe replies.toSet
      fake.callCount() shouldBe 50
    } finally pool.shutdownNow(): Unit
  }

  "The Java guide's testing example" should "pass" in {
    val test = new GuideSnippets.CalculatorTest()
    test.answersFromTheModel()
    test.reportsAProviderFailure()
  }
}
