package org.llm4s.testkit

import org.llm4s.error.{ AuthenticationError, LLMError, RateLimitError, TimeoutError, ValidationError }
import org.llm4s.llmconnect.model._
import org.llm4s.reliability.{ ReliabilityConfig, ReliableClient }
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.{ CountDownLatch, Executors, TimeUnit }
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration._
import scala.util.Using

class ScriptedLLMClientSpec extends AnyFlatSpec with Matchers with EitherValues {

  private def conversation(text: String): Conversation = Conversation(Seq(UserMessage(text)))
  private val options                                  = CompletionOptions()

  private def ask(client: ScriptedLLMClient, text: String): Either[LLMError, Completion] =
    client.complete(conversation(text), options)

  private def unscripted(result: Either[LLMError, Completion]): String = {
    val error = result.left.value
    error shouldBe a[ValidationError]
    error.message
  }

  "ScriptedLLMClient.sequence" should "answer each call with the next reply, in order" in {
    val client = ScriptedLLMClient.sequence(Reply.text("one"), Reply.text("two"), Reply.text("three"))
    List("a", "b", "c").map(q => ask(client, q).value.content) shouldBe List("one", "two", "three")
  }

  it should "build a completion from the reply" in {
    val usage  = TokenUsage(promptTokens = 10, completionTokens = 5, totalTokens = 15)
    val client = ScriptedLLMClient.sequence(Reply.text("hi").withModel("gpt-test").withUsage(usage))
    val result = ask(client, "q").value
    result.content shouldBe "hi"
    result.message shouldBe AssistantMessage("hi")
    result.model shouldBe "gpt-test"
    result.usage shouldBe Some(usage)
    result.toolCalls shouldBe empty
    result.id shouldBe "scripted-1"
  }

  it should "report the default model and no usage unless the reply sets them" in {
    val result = ask(ScriptedLLMClient.sequence(Reply.text("hi")), "q").value
    result.model shouldBe Reply.DefaultModel
    result.usage shouldBe None
  }

  it should "return a tool-call reply as tool calls on both the completion and its message, numbering empty ids" in {
    val client = ScriptedLLMClient.sequence(
      Reply.text("first"),
      Reply.toolCalls(ToolCall("", "one", ujson.Obj("a" -> 1)), ToolCall("keep", "two", ujson.Obj()))
    )
    ask(client, "q1").value
    val result = ask(client, "q2").value
    result.toolCalls shouldBe List(
      ToolCall("call-2-1", "one", ujson.Obj("a" -> 1)),
      ToolCall("keep", "two", ujson.Obj())
    )
    result.message.toolCalls shouldBe result.toolCalls
    result.message.contentOpt shouldBe None
    result.content shouldBe ""
  }

  it should "keep the text of an answer that also calls a tool" in {
    val reply  = Reply.toolCall("t").withContent("let me check")
    val result = ask(ScriptedLLMClient.sequence(reply), "q").value
    result.content shouldBe "let me check"
    result.message.contentOpt shouldBe Some("let me check")
    result.toolCalls.map(_.name) shouldBe List("t")
  }

  it should "return the error of a failure reply, exactly as given" in {
    val rateLimit = RateLimitError("openai")
    val timeout   = TimeoutError("slow", 30.seconds, "complete")
    val auth      = AuthenticationError("openai", "bad key")
    val client    = ScriptedLLMClient.sequence(Reply.failure(rateLimit), Reply.failure(timeout), Reply.failure(auth))
    List("a", "b", "c").map(q => ask(client, q).left.value) shouldBe List(rateLimit, timeout, auth)
  }

  it should "fail a call past the end of the script and say which call, how long the script was, and what was asked" in {
    val client = ScriptedLLMClient.sequence(Reply.text("one"), Reply.text("two"))
    ask(client, "a").value
    ask(client, "b").value
    val message = unscripted(ask(client, "What is the capital of France?"))
    message should include("call 3")
    message should include("the script has 2 replies")
    message should include("What is the capital of France?")
    message should include("user")
  }

  it should "say 'reply' for a one-reply script" in {
    val client = ScriptedLLMClient.sequence(Reply.text("one"))
    ask(client, "a").value
    unscripted(ask(client, "b")) should include("the script has 1 reply")
  }

  it should "fail every call of an empty script and name the empty conversation" in {
    val client  = ScriptedLLMClient.sequence()
    val message = unscripted(client.complete(Conversation(Seq.empty), options))
    message should include("call 1")
    message should include("the script has 0 replies")
    message should include("the conversation has no messages")
  }

  it should "cut a long last message in the failure text" in {
    val long    = "x" * 200
    val message = unscripted(ask(ScriptedLLMClient.sequence(), long))
    message should include("x" * 77 + "...")
    (message should not).include("x" * 78)
  }

  it should "record a call it could not answer" in {
    val client = ScriptedLLMClient.sequence()
    ask(client, "a")
    client.callCount shouldBe 1
  }

  "ScriptedLLMClient.respondingTo" should "choose the reply from the conversation, whatever came before" in {
    val client = ScriptedLLMClient.respondingTo {
      case c if c.messages.lastOption.exists(_.content.contains("capital")) => Reply.text("Paris")
      case c if c.messages.lastOption.exists(_.content.contains("weather")) => Reply.text("sunny")
    }
    ask(client, "the weather?").value.content shouldBe "sunny"
    ask(client, "the capital?").value.content shouldBe "Paris"
    ask(client, "the weather again?").value.content shouldBe "sunny"
  }

  it should "use the first case that matches" in {
    val client = ScriptedLLMClient.respondingTo {
      case c if c.messages.nonEmpty => Reply.text("first")
      case _                        => Reply.text("second")
    }
    ask(client, "q").value.content shouldBe "first"
  }

  it should "fail a conversation no case matches, naming the call and the last message" in {
    val client  = ScriptedLLMClient.respondingTo { case c if c.messages.isEmpty => Reply.text("never") }
    val message = unscripted(ask(client, "an unexpected question"))
    message should include("call 1")
    message should include("no rule matched")
    message should include("an unexpected question")
  }

  "ScriptedLLMClient.always" should "give every call the same reply" in {
    val client = ScriptedLLMClient.always(Reply.text("same"))
    (1 to 5).map(i => ask(client, s"q$i").value.content).toSet shouldBe Set("same")
  }

  "the recorded calls" should "hold each request in order with its conversation and options" in {
    val client = ScriptedLLMClient.sequence(Reply.text("a"), Reply.text("b"))
    val first  = conversation("first question")
    val second = Conversation(Seq(SystemMessage("be brief"), UserMessage("second question")))
    val opts   = CompletionOptions().withTemperature(0.25)
    client.complete(first, options)
    client.complete(second, opts)
    client.callCount shouldBe 2
    client.calls.map(_.conversation) shouldBe Vector(first, second)
    client.calls.map(_.options) shouldBe Vector(options, opts)
    client.calls.map(_.streamed) shouldBe Vector(false, false)
    client.lastCall.map(_.conversation) shouldBe Some(second)
  }

  it should "start empty" in {
    val client = ScriptedLLMClient.sequence(Reply.text("a"))
    client.calls shouldBe empty
    client.callCount shouldBe 0
    client.lastCall shouldBe None
  }

  it should "mark a request that came through streamComplete" in {
    val client = ScriptedLLMClient.sequence(Reply.text("a"), Reply.text("b"))
    client.complete(conversation("q1"), options)
    client.streamComplete(conversation("q2"), options, _ => ())
    client.calls.map(_.streamed) shouldBe Vector(false, true)
  }

  "RecordedCall.lastUserText" should "be the last user message even when other messages follow it" in {
    val call = RecordedCall(
      Conversation(
        Seq(
          UserMessage("early"),
          UserMessage("the question"),
          AssistantMessage("a reply"),
          ToolMessage("42", "call-1")
        )
      ),
      options
    )
    call.lastUserText shouldBe Some("the question")
    call.messages.size shouldBe 4
  }

  it should "be empty for a conversation without a user message" in {
    RecordedCall(Conversation(Seq(SystemMessage("s"))), options).lastUserText shouldBe None
  }

  "streamComplete" should "replay the text in chunks of at most chunkSize characters that join back to the text" in {
    val client = ScriptedLLMClient.sequence(Reply.text("abcdefghij")).withChunkSize(3)
    val chunks = ArrayBuffer.empty[StreamedChunk]
    val result = client.streamComplete(conversation("q"), options, chunks += _).value
    chunks.flatMap(_.content).toList shouldBe List("abc", "def", "ghi", "j")
    chunks.flatMap(_.content).mkString shouldBe "abcdefghij"
    result.content shouldBe "abcdefghij"
  }

  it should "end with one chunk that carries the finish reason 'stop'" in {
    val client = ScriptedLLMClient.sequence(Reply.text("hello"))
    val chunks = ArrayBuffer.empty[StreamedChunk]
    client.streamComplete(conversation("q"), options, chunks += _).value
    chunks.last.finishReason shouldBe Some("stop")
    chunks.last.content shouldBe None
    chunks.init.flatMap(_.finishReason) shouldBe empty
  }

  it should "emit each tool call as a chunk and finish with 'tool_calls'" in {
    val client = ScriptedLLMClient.sequence(
      Reply.toolCalls(ToolCall("", "one", ujson.Obj()), ToolCall("", "two", ujson.Obj()))
    )
    val chunks = ArrayBuffer.empty[StreamedChunk]
    val result = client.streamComplete(conversation("q"), options, chunks += _).value
    chunks.flatMap(_.toolCall).map(_.name).toList shouldBe List("one", "two")
    chunks.flatMap(_.toolCall).map(_.id).toList shouldBe List("call-1-1", "call-1-2")
    chunks.last.finishReason shouldBe Some("tool_calls")
    chunks.flatMap(_.content) shouldBe empty
    result.toolCalls.map(_.id) shouldBe List("call-1-1", "call-1-2")
  }

  it should "give every chunk the id of the completion" in {
    val client = ScriptedLLMClient.sequence(Reply.text("abcd")).withChunkSize(2)
    val chunks = ArrayBuffer.empty[StreamedChunk]
    val result = client.streamComplete(conversation("q"), options, chunks += _).value
    chunks.map(_.id).toSet shouldBe Set(result.id)
  }

  it should "return the same completion complete would" in {
    val reply     = Reply.text("same").withModel("m")
    val streamed  = ScriptedLLMClient.sequence(reply).streamComplete(conversation("q"), options, _ => ()).value
    val completed = ScriptedLLMClient.sequence(reply).complete(conversation("q"), options).value
    streamed shouldBe completed
  }

  it should "send no chunk for a failure reply" in {
    val client = ScriptedLLMClient.sequence(Reply.failure(RateLimitError("p")))
    val chunks = ArrayBuffer.empty[StreamedChunk]
    client.streamComplete(conversation("q"), options, chunks += _).left.value shouldBe RateLimitError("p")
    chunks shouldBe empty
  }

  it should "send no chunk for an unscripted call" in {
    val chunks = ArrayBuffer.empty[StreamedChunk]
    unscripted(ScriptedLLMClient.sequence().streamComplete(conversation("q"), options, chunks += _))
    chunks shouldBe empty
  }

  it should "send no text chunk for a reply with empty text" in {
    val chunks = ArrayBuffer.empty[StreamedChunk]
    ScriptedLLMClient.sequence(Reply.text("")).streamComplete(conversation("q"), options, chunks += _).value
    chunks.map(_.finishReason) shouldBe List(Some("stop"))
  }

  "the configuration" should "report a 128000-token context window and reserve 4096 by default" in {
    val client = ScriptedLLMClient.sequence()
    client.getContextWindow() shouldBe 128000
    client.getReserveCompletion() shouldBe 4096
    client.chunkSize shouldBe 16
  }

  it should "change through the with* methods, each on a fresh client with no recorded calls and the same script" in {
    val client = ScriptedLLMClient.sequence(Reply.text("one"), Reply.text("two"))
    ask(client, "q").value
    val changed = client.withContextWindow(1000)
    changed.getContextWindow() shouldBe 1000
    changed.getReserveCompletion() shouldBe 4096
    changed.callCount shouldBe 0
    ask(changed, "q").value.content shouldBe "one"
    client.withReserveCompletion(50).getReserveCompletion() shouldBe 50
    client.withReserveCompletion(50).getContextWindow() shouldBe 128000
    client.withChunkSize(5).chunkSize shouldBe 5
    client.callCount shouldBe 1
  }

  it should "treat a chunk size below 1 as 1" in {
    ScriptedLLMClient.sequence().withChunkSize(0).chunkSize shouldBe 1
    ScriptedLLMClient.sequence().withChunkSize(-4).chunkSize shouldBe 1
  }

  it should "compute the context budget from the window and the reserve" in {
    val client = ScriptedLLMClient.sequence().withContextWindow(10000).withReserveCompletion(1000)
    client.getContextWindow() - client.getReserveCompletion() shouldBe 9000
  }

  "a ScriptedLLMClient under ReliableClient" should "be retried after a rate limit and then succeed" in {
    val scripted = ScriptedLLMClient.sequence(Reply.failure(RateLimitError("p")), Reply.text("recovered"))
    val reliable = new ReliableClient(scripted, "p", ReliabilityConfig.default, sleep = _ => ())
    reliable.complete(conversation("q"), options).value.content shouldBe "recovered"
    scripted.callCount shouldBe 2
  }

  it should "not retry an authentication error" in {
    val scripted = ScriptedLLMClient.sequence(Reply.failure(AuthenticationError("p", "bad key")), Reply.text("unused"))
    val reliable = new ReliableClient(scripted, "p", ReliabilityConfig.default, sleep = _ => ())
    reliable.complete(conversation("q"), options).left.value shouldBe an[AuthenticationError]
    scripted.callCount shouldBe 1
  }

  "concurrent calls" should "each receive their own position in the script and all be recorded" in {
    val threads   = 8
    val perThread = 50
    val total     = threads * perThread
    val client    = ScriptedLLMClient.sequence((1 to total).map(i => Reply.text(s"reply-$i"))*)
    val start     = new CountDownLatch(1)
    val answers   = new java.util.concurrent.ConcurrentLinkedQueue[String]()
    val errors    = new AtomicInteger(0)
    Using.resource(Executors.newFixedThreadPool(threads)) { pool =>
      val futures = (1 to threads).map { t =>
        pool.submit(new Runnable {
          override def run(): Unit = {
            start.await()
            (1 to perThread).foreach { i =>
              client.complete(conversation(s"t$t-$i"), options) match {
                case Right(c) => answers.add(c.content); ()
                case Left(_)  => errors.incrementAndGet(); ()
              }
            }
          }
        })
      }
      start.countDown()
      futures.foreach(_.get(60, TimeUnit.SECONDS))
    }
    errors.get shouldBe 0
    val received = answers.toArray.map(_.toString).toList
    received.size shouldBe total
    received.toSet shouldBe (1 to total).map(i => s"reply-$i").toSet
    client.callCount shouldBe total
    client.calls.map(_.conversation.messages.head.content).toSet.size shouldBe total
  }
}
