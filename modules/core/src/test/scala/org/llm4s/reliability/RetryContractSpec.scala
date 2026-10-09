package org.llm4s.reliability

import org.llm4s.error.*
import org.llm4s.llmconnect.{ LLMClient, LLMClientRetry }
import org.llm4s.llmconnect.model.*
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
 * `LLMError.isRecoverable` says an error may succeed if tried again, possibly after the caller does something
 * first. The retry layers retry the *identical* request, with nobody in the loop, so they retry a subset of what is
 * recoverable. That subset is `RetryPolicy`'s default rule, and `LLMClientRetry` uses the same one. This spec
 * fails when a new error type is added without a row here, and when a recoverable error is not retried without a
 * documented reason.
 */
class RetryContractSpec extends AnyFlatSpec with Matchers {

  /** A row of the contract: the error, whether it is recoverable, and whether the default rule retries it. */
  final private case class Row(label: String, error: LLMError, recoverable: Boolean, retried: Boolean)

  private val rows: Seq[Row] = Seq(
    Row("APIError, no status", APIError("openai", "failed"), recoverable = true, retried = true),
    Row("APIError 503", APIError("openai", "unavailable", Some(503)), recoverable = true, retried = true),
    Row("APIError 502", APIError("openai", "bad gateway", Some(502)), recoverable = true, retried = true),
    Row("APIError 429", APIError("openai", "slow down", Some(429)), recoverable = true, retried = true),
    Row("APIError 408", APIError("openai", "request timeout", Some(408)), recoverable = true, retried = true),
    Row("APIError 400", APIError("openai", "bad request", Some(400)), recoverable = true, retried = false),
    Row("APIError 404", APIError("openai", "no such model", Some(404)), recoverable = true, retried = false),
    Row(
      "AuthenticationError",
      AuthenticationError("openai", "invalid key", "401"),
      recoverable = false,
      retried = false
    ),
    Row("CancelledError", CancelledError("op", None), recoverable = false, retried = false),
    Row(
      "ConfigurationError",
      ConfigurationError("missing key", List("OPENAI_API_KEY")),
      recoverable = false,
      retried = false
    ),
    Row("ContextError", ContextError.tokenBudgetExceeded(200, 100), recoverable = false, retried = false),
    Row("ExecutionError", ExecutionError("failed", "run", Some(2)), recoverable = true, retried = true),
    Row("InvalidInputError", InvalidInputError("field", "value", "reason"), recoverable = false, retried = false),
    Row("NetworkError", NetworkError("refused", None, "https://llm.example/v1"), recoverable = true, retried = true),
    Row("NotFoundError", NotFoundError("missing", "key-1"), recoverable = false, retried = false),
    Row("OptimisticLockFailure", OptimisticLockFailure("conflict", "mem-1", 3L), recoverable = true, retried = false),
    Row("ProcessingError", ProcessingError("op", "failed"), recoverable = false, retried = false),
    Row("RateLimitError", RateLimitError("anthropic", 60.seconds), recoverable = true, retried = true),
    Row("ServiceError 503", ServiceError(503, "p", "unavailable"), recoverable = true, retried = true),
    Row("ServiceError 500", ServiceError(500, "p", "internal"), recoverable = true, retried = true),
    Row("ServiceError 429", ServiceError(429, "p", "slow down"), recoverable = true, retried = true),
    Row("ServiceError 408", ServiceError(408, "p", "request timeout"), recoverable = true, retried = true),
    Row("ServiceError 400", ServiceError(400, "p", "bad request"), recoverable = true, retried = false),
    Row("ServiceError 404", ServiceError(404, "p", "not found"), recoverable = true, retried = false),
    Row("SimpleError", SimpleError("plain"), recoverable = false, retried = false),
    Row("SystemError", SystemError("system"), recoverable = true, retried = true),
    Row("TimeoutError", TimeoutError("slow", 5.seconds, "complete"), recoverable = true, retried = true),
    Row("TokenizerError", TokenizerError.notFound("tok"), recoverable = false, retried = false),
    Row("UnknownError", UnknownError("weird", new RuntimeException("x")), recoverable = false, retried = false),
    Row("ValidationError", ValidationError("field", "reason"), recoverable = false, retried = false)
  )

  /**
   * Recoverable but not retried by the default rule, each with its reason. Anything else that is recoverable must
   * be retried.
   */
  private def documentedNotRetried(error: LLMError): Option[String] = error match {
    case s: ServiceError if !isRetryableStatus(s.httpStatus) =>
      Some("a client-error response means the request itself is wrong: repeating it unchanged cannot succeed")
    case a: APIError if a.statusCode.exists(!isRetryableStatus(_)) =>
      Some("a client-error response means the request itself is wrong: repeating it unchanged cannot succeed")
    case _: OptimisticLockFailure =>
      Some("the caller must re-read the record first; the same update fails again until it does")
    case _ => None
  }

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

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)

    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 1024
  }

  "LLMError.isRecoverable" should "say what each error type declares" in {
    rows.foreach(row => withClue(row.label)(LLMError.isRecoverable(row.error) shouldBe row.recoverable))
  }

  "The default retry policies" should "retry exactly the errors the contract says" in {
    for {
      (name, policy) <- policies
      row            <- rows
    } withClue(s"$name / ${row.label}")(policy.isRetryable(row.error) shouldBe row.retried)
  }

  it should "never retry an error that is not recoverable" in {
    for {
      (name, policy) <- policies
      row            <- rows
      if policy.isRetryable(row.error)
    } withClue(s"$name / ${row.label}")(LLMError.isRecoverable(row.error) shouldBe true)
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

  "The HTTP status rule" should "be the same for ServiceError's own check and the retry rule, for every status" in {
    (0 to 699).foreach { status =>
      withClue(s"status $status") {
        ServiceError(status, "p", "m").isRecoverableStatus shouldBe RetryPolicy.isRetryableStatus(status)
        RetryPolicy.isTransient(ServiceError(status, "p", "m")) shouldBe RetryPolicy.isRetryableStatus(status)
      }
    }
  }

  "LLMClientRetry" should "retry exactly the errors the default policy retries" in {
    rows.foreach { row =>
      withClue(row.label) {
        val client = new AlwaysFailing(row.error)
        val result = LLMClientRetry.completeWithRetry(
          client,
          Conversation(Seq(UserMessage("hi"))),
          CompletionOptions(),
          maxAttempts = 3,
          baseDelay = 1.millis,
          sleepFn = _ => ()
        )

        result shouldBe Left(row.error)
        client.calls.get() shouldBe (if (row.retried) 3 else 1)
      }
    }
  }

  "A recoverable error that is not retried" should "be one the contract documents, with a reason" in {
    val unexplained = rows.filter(r => r.recoverable && !r.retried && documentedNotRetried(r.error).isEmpty)

    unexplained.map(_.label) shouldBe Nil
  }

  it should "be exactly the documented ones" in {
    val notRetried = rows.filter(r => r.recoverable && !r.retried).map(_.label)

    notRetried should contain theSameElementsAs rows.collect {
      case r if documentedNotRetried(r.error).isDefined => r.label
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
