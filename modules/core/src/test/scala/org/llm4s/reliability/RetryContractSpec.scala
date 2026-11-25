package org.llm4s.reliability

import org.llm4s.error.*
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.*
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.lang.reflect.Modifier
import java.nio.file.{ Files, Paths }
import java.util.jar.JarFile
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * The one retry contract, pinned for every concrete `LLMError`.
 *
 * `LLMError.isRetryable` is the one rule: whether the identical request, sent again with nobody in the loop, may
 * succeed. `RetryPolicy`'s default rule is that member, and `ReliableClient` applies it. This spec fails when a new
 * error type is added without a row here.
 */
class RetryContractSpec extends AnyFlatSpec with Matchers {

  /** A row of the contract: the error, and whether it is retryable - which is whether the default rule retries it. */
  final private case class Row(label: String, error: LLMError, retried: Boolean)

  private val rows: Seq[Row] = Seq(
    Row(
      "AuthenticationError",
      AuthenticationError(ProviderId("openai"), "invalid key", "401"),
      retried = false
    ),
    Row("CancelledError", CancelledError("op", None), retried = false),
    Row(
      "ConfigurationError",
      ConfigurationError("missing key", List("OPENAI_API_KEY")),
      retried = false
    ),
    Row(
      "ContentFilteredError",
      ContentFilteredError.reply(ProviderId("gemini"), "SAFETY"),
      retried = false
    ),
    Row(
      "ContextError",
      ContextError.artifactStoreFailed("store", "key", "disk full"),
      retried = false
    ),
    Row("EmbeddingError 503", EmbeddingError(Some("503"), "unavailable", ProviderId("voyage")), retried = true),
    Row("EmbeddingError 401", EmbeddingError(Some("401"), "bad key", ProviderId("voyage")), retried = false),
    Row("EmbeddingError, no status", EmbeddingError(None, "unparseable", ProviderId("voyage")), retried = false),
    Row("ContextLengthError", ContextLengthError(ProviderId("openai"), "too long"), retried = false),
    Row("ModelNotFoundError", ModelNotFoundError(ProviderId("openai"), "no such model"), retried = false),
    Row("ExecutionError", ExecutionError("failed", "run", Some(2)), retried = true),
    Row("InvalidInputError", InvalidInputError("field", "value", "reason"), retried = false),
    Row(
      "UnsupportedContentPartError",
      UnsupportedContentPartError(ProviderId("ollama"), "image (url, image/png)", "user"),
      retried = false
    ),
    Row("CircuitOpenError", CircuitOpenError.open(ProviderId("anthropic"), 30.seconds), retried = false),
    Row("NetworkError", NetworkError("refused", None, "https://llm.example/v1"), retried = true),
    Row("NotFoundError", NotFoundError("missing", "key-1"), retried = false),
    Row("OptimisticLockFailure", OptimisticLockFailure("conflict", "mem-1", 3L), retried = false),
    Row("ProcessingError", ProcessingError("op", "failed"), retried = false),
    Row("RateLimitError", RateLimitError(ProviderId("anthropic"), 60.seconds), retried = true),
    Row("ServiceError 503", ServiceError(503, ProviderId("p"), "unavailable"), retried = true),
    Row("ServiceError 500", ServiceError(500, ProviderId("p"), "internal"), retried = true),
    Row("ServiceError 429", ServiceError(429, ProviderId("p"), "slow down"), retried = true),
    Row("ServiceError 408", ServiceError(408, ProviderId("p"), "request timeout"), retried = true),
    Row("ServiceError 400", ServiceError(400, ProviderId("p"), "bad request"), retried = false),
    Row("ServiceError 404", ServiceError(404, ProviderId("p"), "not found"), retried = false),
    Row("SimpleError", SimpleError("plain"), retried = false),
    Row("TimeoutError", TimeoutError("slow", 5.seconds, "complete"), retried = true),
    Row("TokenizerError", TokenizerError.notAvailable("tok"), retried = false),
    Row("UnknownError", UnknownError("weird", new RuntimeException("x")), retried = false),
    Row("ValidationError", ValidationError("field", "reason"), retried = false),
    Row("ValidationUnsupportedError", ValidationUnsupportedError("BedrockClient"), retried = false)
  )

  private def isRetryableStatus(status: Int): Boolean = status >= 500 || status == 429 || status == 408

  /**
   * Every factory of `RetryPolicy` that builds a policy retrying by rule, as it is built with no predicate of its
   * own. `noRetry` is the other factory: it retries nothing, and has its own test below.
   */
  private val policies: Seq[(String, RetryPolicy)] = Seq(
    "exponentialBackoff" -> RetryPolicy.exponentialBackoff(),
    "fixedDelay"         -> RetryPolicy.fixedDelay(3, 1.millis),
    "linearBackoff"      -> RetryPolicy.linearBackoff(3, 1.millis),
    "custom"             -> RetryPolicy.custom(3, (_, _) => 1.millis)
  )

  private class AlwaysFailing(error: LLMError) extends LLMClient {
    val calls = new AtomicInteger(0)

    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
      calls.incrementAndGet()
      Left(error)
    }

    override def streamComplete(conversation: Conversation, options: CompletionOptions)(
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)

    override def contextWindow: Int     = 4096
    override def reserveCompletion: Int = 1024
  }

  "LLMError.isRetryable" should "say what each error type declares" in {
    rows.foreach(row => withClue(row.label)(row.error.isRetryable shouldBe row.retried))
  }

  "The default retry policies" should "retry exactly the errors the contract says" in {
    for {
      (name, policy) <- policies
      row            <- rows
    } withClue(s"$name / ${row.label}")(policy.isRetryable(row.error) shouldBe row.retried)
  }

  it should "never retry an error that is not retryable" in {
    for {
      (name, policy) <- policies
      row            <- rows
      if policy.isRetryable(row.error)
    } withClue(s"$name / ${row.label}")(row.error.isRetryable shouldBe true)
  }

  "RetryPolicy.noRetry" should "retry no error at all" in {
    rows.foreach(row => withClue(row.label)(RetryPolicy.noRetry.isRetryable(row.error) shouldBe false))
  }

  "The factories of RetryPolicy" should "all be covered by this contract" in {
    // A factory added without a row here would carry its own idea of "retry this error" unchecked: fail the build.
    val factories = RetryPolicy.getClass.getDeclaredMethods.toList
      .filter(m => Modifier.isPublic(m.getModifiers) && classOf[RetryPolicy].isAssignableFrom(m.getReturnType))
      .map(_.getName)
      .toSet

    factories shouldBe (policies.map(_._1).toSet + "noRetry")
  }

  "A policy built with a custom delay and no predicate" should "retry what the default policies retry" in {
    val custom = RetryPolicy.custom(3, (_, _) => 1.millis)
    rows.foreach(row =>
      withClue(row.label)(
        custom.isRetryable(row.error) shouldBe RetryPolicy.exponentialBackoff().isRetryable(row.error)
      )
    )
  }

  "The HTTP status rule" should "retry a ServiceError for 408, 429 and 5xx only, for every status" in {
    (0 to 699).foreach { status =>
      withClue(s"status $status") {
        ServiceError(status, ProviderId("p"), "m").isRetryable shouldBe isRetryableStatus(status)
        RetryPolicy
          .exponentialBackoff()
          .isRetryable(ServiceError(status, ProviderId("p"), "m")) shouldBe isRetryableStatus(status)
      }
    }
  }

  "A backoff policy's delay" should "be the server's retryAfter when it gave one, whatever the error" in {
    val policy = RetryPolicy.fixedDelay(3, 1.millis)
    policy.delayFor(1, ServiceError(503, ProviderId("p"), "m").withRetryAfter(7.seconds)) shouldBe 7.seconds
    policy.delayFor(1, RateLimitError(ProviderId("p"), 9.seconds)) shouldBe 9.seconds
    policy.delayFor(1, ServiceError(503, ProviderId("p"), "m")) shouldBe 1.millis
  }

  it should "be the policy's rate-limit default for a rate limit the server gave no delay for" in {
    RetryPolicy
      .fixedDelay(3, 1.millis)
      .delayFor(1, RateLimitError(ProviderId("p"))) shouldBe RetryPolicy.DefaultRateLimitDelay
    RateLimitError(ProviderId("p")).retryAfter shouldBe None
  }

  "ReliableClient" should "retry exactly the errors the default policy retries, and return the last one unchanged" in {
    rows.foreach { row =>
      withClue(row.label) {
        val client = new AlwaysFailing(row.error)
        val reliable = ReliableClient(
          client,
          "contract",
          ReliabilityConfig(retryPolicy = RetryPolicy.exponentialBackoff(maxAttempts = 3, baseDelay = 1.millis))
        ).withSleep(_ => ())
        val result = reliable.complete(Conversation(Seq(UserMessage("hi"))), CompletionOptions())

        result shouldBe Left(row.error)
        client.calls.get() shouldBe (if (row.retried) 3 else 1)
      }
    }
  }

  "Every concrete LLMError the library defines" should "have a row in the contract" in {
    val covered = rows.map(_.error.getClass.getName).toSet
    val missing = concreteErrorClasses().filterNot(covered.contains)

    withClue("add a row to RetryContractSpec for: ")(missing shouldBe Nil)
  }

  /** The concrete `LLMError` classes of this package, found on the classpath so a new one cannot be forgotten. */
  private def concreteErrorClasses(): List[String] = {
    val location = classOf[LLMError].getProtectionDomain.getCodeSource.getLocation.toURI
    val root     = Paths.get(location)
    val names: List[String] =
      if (Files.isDirectory(root)) {
        val dir    = root.resolve("org/llm4s/error")
        val stream = Files.list(dir)
        try stream.iterator().asScala.map(_.getFileName.toString).toList
        finally stream.close()
      } else {
        val jar = new JarFile(root.toFile)
        try
          jar
            .entries()
            .asScala
            .map(_.getName)
            .filter(_.startsWith("org/llm4s/error/"))
            .map(_.stripPrefix("org/llm4s/error/"))
            .toList
        finally jar.close()
      }

    names
      .filter(n => n.endsWith(".class") && !n.contains("$"))
      .map(n => "org.llm4s.error." + n.stripSuffix(".class"))
      .filter { name =>
        val c = Class.forName(name)
        classOf[LLMError].isAssignableFrom(c) && !c.isInterface && !Modifier.isAbstract(c.getModifiers)
      }
      .sorted
  }

}
