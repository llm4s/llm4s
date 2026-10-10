package org.llm4s.testkit.client

import org.llm4s.error.SimpleError
import org.llm4s.llmconnect.model.{ AssistantMessage, Completion, CompletionOptions, Conversation, UserMessage }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.{ ConcurrentHashMap, Executors }
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters._

class ScriptedLLMClientSpec extends AnyFlatSpec with Matchers {

  private val options = CompletionOptions()

  private def conversation(text: String): Conversation = Conversation(List(UserMessage(text)))

  private def completion(id: String, content: String): Completion =
    Completion(id = id, created = 0L, content = content, model = "scripted", message = AssistantMessage(content))

  "returning" should "answer each call with the next completion, in order" in {
    val client = ScriptedLLMClient.returning(completion("c0", "first"), completion("c1", "second"))
    client.complete(conversation("a"), options) shouldBe Right(completion("c0", "first"))
    client.complete(conversation("b"), options) shouldBe Right(completion("c1", "second"))
  }

  "returningText" should "wrap each string in an AssistantMessage under an incrementing id" in {
    val client = ScriptedLLMClient.returningText("hi", "there")
    val first  = client.complete(conversation("a"), options).toOption.get
    val second = client.complete(conversation("b"), options).toOption.get
    first.id shouldBe "scripted-0"
    first.content shouldBe "hi"
    second.id shouldBe "scripted-1"
    second.content shouldBe "there"
  }

  "respondingWith" should "script a failed call as a Left, for error-handling tests" in {
    val failure = SimpleError("provider down")
    val client  = ScriptedLLMClient.respondingWith(Right(completion("c0", "ok")), Left(failure))
    client.complete(conversation("a"), options) shouldBe Right(completion("c0", "ok"))
    client.complete(conversation("b"), options) shouldBe Left(failure)
  }

  "complete" should "fail with SimpleError, not loop or repeat, once the script is exhausted" in {
    val client = ScriptedLLMClient.returning(completion("c0", "only"))
    client.complete(conversation("a"), options) shouldBe Right(completion("c0", "only"))
    client.complete(conversation("b"), options).left.toOption.get shouldBe a[SimpleError]
    client.complete(conversation("c"), options).left.toOption.get shouldBe a[SimpleError]
    client.remaining shouldBe 0
  }

  "complete and streamComplete" should "consume the same script position regardless of which is called" in {
    val client = ScriptedLLMClient.returning(completion("c0", "x"), completion("c1", "y"))
    var seen   = List.empty[String]
    client.streamComplete(conversation("a"), options, chunk => seen = chunk.content.get :: seen).toOption.get.id shouldBe "c0"
    client.complete(conversation("b"), options).toOption.get.id shouldBe "c1"
    seen shouldBe List("x")
  }

  "streamComplete" should "emit the scripted completion's content as a single chunk and return the completion" in {
    val client = ScriptedLLMClient.returning(completion("c0", "whole message"))
    var chunks = List.empty[String]
    val result = client.streamComplete(conversation("a"), options, chunk => chunks = chunks :+ chunk.content.get)
    result shouldBe Right(completion("c0", "whole message"))
    chunks shouldBe List("whole message")
  }

  "remaining" should "report how many scripted results are left, down to zero, never negative" in {
    val client = ScriptedLLMClient.returning(completion("c0", "a"), completion("c1", "b"))
    client.remaining shouldBe 2
    client.complete(conversation("x"), options)
    client.remaining shouldBe 1
    client.complete(conversation("y"), options)
    client.remaining shouldBe 0
    client.complete(conversation("z"), options)
    client.remaining shouldBe 0
  }

  "requests" should "record every conversation passed to complete or streamComplete, in call order" in {
    val client = ScriptedLLMClient.returning(completion("c0", "a"), completion("c1", "b"))
    client.complete(conversation("first"), options)
    client.streamComplete(conversation("second"), options, _ => ())
    client.requests shouldBe List(conversation("first"), conversation("second"))
  }

  it should "be safe to call from many threads at once, serving each scripted result exactly once" in {
    val scriptSize = 2000
    val completions = (0 until scriptSize).map(i => completion(s"c$i", s"content-$i"))
    val client      = ScriptedLLMClient.returning(completions: _*)
    val seenIds     = ConcurrentHashMap.newKeySet[String]()
    val failures    = new AtomicInteger(0)

    val pool = Executors.newFixedThreadPool(16)
    try {
      val futures = (0 until scriptSize).map(i =>
        pool.submit(new Runnable {
          def run(): Unit =
            client.complete(conversation(s"call-$i"), options) match {
              case Right(c) => if (!seenIds.add(c.id)) failures.incrementAndGet()
              case Left(_)  => failures.incrementAndGet()
            }
        })
      )
      futures.foreach(_.get())
    } finally pool.shutdown()

    failures.get() shouldBe 0
    seenIds.asScala should have size scriptSize.toLong
    client.requests should have size scriptSize.toLong
  }
}
