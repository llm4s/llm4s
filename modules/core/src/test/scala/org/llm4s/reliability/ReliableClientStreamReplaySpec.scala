package org.llm4s.reliability

import org.llm4s.error.{ ContentFilteredError, NetworkError, TimeoutError }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._
import scala.util.Try

/**
 * A stream that fails after a chunk reached the caller is not retried: a retry would deliver the
 * reply again from the start, so the caller would see "HelHello", and the provider would bill it
 * twice. `LLMClientRetry.streamCompleteWithRetry` keeps the same rule. A failure before the first
 * chunk is retried as before.
 */
class ReliableClientStreamReplaySpec extends AnyFlatSpec with Matchers {

  private val conversation = Conversation(Seq(UserMessage("hi")))

  private def completion(text: String) =
    Completion(id = "c", created = Instant.EPOCH, content = text, model = "m", message = AssistantMessage(text))

  private val timeout = TimeoutError("connection reset", 1.second, "test")

  /** Each attempt plays the next script: the chunks it delivers, then how it ends. */
  final private class Scripted(attempts: (List[String], Result[Completion])*) extends LLMClient {
    val calls = new AtomicInteger(0)
    def complete(c: Conversation, o: CompletionOptions): Result[Completion] = {
      val (_, end) = attempts(math.min(calls.getAndIncrement(), attempts.size - 1))
      end
    }
    def streamComplete(c: Conversation, o: CompletionOptions, onChunk: StreamedChunk => Unit): Result[Completion] = {
      val (chunks, end) = attempts(math.min(calls.getAndIncrement(), attempts.size - 1))
      // a client turns an exception from onChunk into a Left, as OpenAICompatibleClient does
      Try(chunks.foreach(t => onChunk(StreamedChunk(id = "c", content = Some(t))))).toEither.left
        .map(e => NetworkError(s"onChunk failed: ${e.getMessage}", Some(e), "test"))
        .flatMap(_ => end)
    }
    def getContextWindow(): Int     = 8192
    def getReserveCompletion(): Int = 1024
  }

  private def reliable(inner: LLMClient, deadline: Option[FiniteDuration]) =
    new ReliableClient(
      inner,
      "test",
      ReliabilityConfig(
        retryPolicy = RetryPolicy.fixedDelay(maxAttempts = 3, delay = 1.millis),
        circuitBreaker = CircuitBreakerConfig.disabled,
        deadline = deadline
      ),
      None
    )

  /** A client whose circuit breaker opens on the first failure and stays open for the test. */
  private def breaking(inner: LLMClient, deadline: Option[FiniteDuration]) =
    new ReliableClient(
      inner,
      "test",
      ReliabilityConfig(
        retryPolicy = RetryPolicy.fixedDelay(maxAttempts = 3, delay = 1.millis),
        circuitBreaker = CircuitBreakerConfig(failureThreshold = 1, recoveryTimeout = 1.hour, successThreshold = 1),
        deadline = deadline
      ),
      None
    )

  for (deadline <- List(None, Some(30.seconds))) {
    val path = if (deadline.isEmpty) "without a deadline" else "with a deadline"

    s"ReliableClient.streamComplete $path" should "not retry once a chunk has reached the caller" in {
      val inner  = new Scripted(List("Hel") -> Left(timeout), List("Hello") -> Right(completion("Hello")))
      val seen   = ListBuffer.empty[String]
      val result = reliable(inner, deadline).streamComplete(conversation, onChunk = c => seen ++= c.content)
      result shouldBe Left(timeout)
      seen.mkString shouldBe "Hel"
      inner.calls.get() shouldBe 1
    }

    it should "still retry a failure that came before any chunk" in {
      val inner  = new Scripted(Nil -> Left(timeout), List("Hel", "lo") -> Right(completion("Hello")))
      val seen   = ListBuffer.empty[String]
      val result = reliable(inner, deadline).streamComplete(conversation, onChunk = c => seen ++= c.content)
      result shouldBe Right(completion("Hello"))
      seen.mkString shouldBe "Hello"
      inner.calls.get() shouldBe 2
    }

    it should "not retry when the caller's own onChunk fails, so the call is not run and billed again" in {
      val inner = new Scripted(List("Hel") -> Right(completion("Hello")))
      val result =
        reliable(inner, deadline).streamComplete(
          conversation,
          onChunk = _ => throw new IllegalStateException("client gone")
        )
      result.left.toOption.map(_.message).getOrElse("") should include("client gone")
      inner.calls.get() shouldBe 1
    }

    it should "not count the caller's own onChunk failure against the circuit breaker" in {
      val inner   = new Scripted(List("Hel") -> Right(completion("Hello")), Nil -> Right(completion("ok")))
      val client  = breaking(inner, deadline)
      val boom    = new IllegalStateException("client gone")
      val result  = client.streamComplete(conversation, onChunk = _ => throw boom)
      val message = result.left.toOption.map(_.message).getOrElse("")
      message should include("client gone") // the client saw the caller's exception, rethrown unchanged
      // one failure would open this breaker; the caller's failure is not one, so the next call reaches the provider
      client.complete(conversation) shouldBe Right(completion("ok"))
      inner.calls.get() shouldBe 2
    }

    it should "still count a provider failure after a chunk against the circuit breaker" in {
      val inner  = new Scripted(List("Hel") -> Left(timeout), Nil -> Right(completion("ok")))
      val client = breaking(inner, deadline)
      client.streamComplete(conversation, onChunk = _ => ()) shouldBe Left(timeout)
      client.complete(conversation).isLeft shouldBe true // the breaker is open
      inner.calls.get() shouldBe 1
    }

    it should "not count a content-filter block against the circuit breaker" in {
      val filtered = ContentFilteredError.reply("Gemini", "SAFETY")
      val inner    = new Scripted(List("Hel") -> Left(filtered), Nil -> Left(filtered), Nil -> Right(completion("ok")))
      val client   = breaking(inner, deadline)
      client.streamComplete(conversation, onChunk = _ => ()) shouldBe Left(filtered)
      client.complete(conversation) shouldBe Left(filtered) // not retried: it is non-recoverable
      // one failure would open this breaker; neither block is one, so the next call reaches the provider
      client.complete(conversation) shouldBe Right(completion("ok"))
      inner.calls.get() shouldBe 3
    }

    "ReliableClient.complete " + path should "still retry, since nothing reached the caller" in {
      val inner = new Scripted(Nil -> Left(timeout), Nil -> Right(completion("ok")))
      reliable(inner, deadline).complete(conversation) shouldBe Right(completion("ok"))
      inner.calls.get() shouldBe 2
    }
  }
}
