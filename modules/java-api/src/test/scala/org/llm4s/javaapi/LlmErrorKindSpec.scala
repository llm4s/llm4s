package org.llm4s.javaapi

import org.llm4s.error._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.lang.reflect.Modifier
import java.nio.file.{ Files, Paths }
import java.time.Duration
import java.util.{ Optional, OptionalInt }
import java.util.jar.JarFile
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters._
import scala.util.Using

/**
 * `LlmException.getKind`, `isRetryable`, `getRetryAfter` and `getStatusCode` (#1487).
 *
 * `LLMError` is an open trait, so the compiler cannot check that every error class has a kind. This spec does instead:
 * it lists every concrete `LLMError` class compiled into `org.llm4s.error` and fails when one has no entry in
 * `LlmErrorKinds.byClass` - a class added to core without a kind would otherwise read as `OTHER` without anyone
 * deciding so.
 */
@org.llm4s.tags.DocsSpec
class LlmErrorKindSpec extends AnyFlatSpec with Matchers {
  import LlmErrorKindSpec.SpeechLikeError

  /** The names of the class files directly in `org/llm4s/error`, from a classes directory or a jar. */
  private def errorPackageClassNames: List[String] = {
    val prefix   = "org/llm4s/error/"
    val location = Paths.get(classOf[LLMError].getProtectionDomain.getCodeSource.getLocation.toURI)
    val entries: List[String] =
      if (Files.isDirectory(location))
        Using.resource(Files.list(location.resolve(prefix)))(_.iterator.asScala.map(p => prefix + p.getFileName).toList)
      else
        Using.resource(new JarFile(location.toFile))(_.entries.asScala.map(_.getName).toList)
    entries.collect {
      case name if name.startsWith(prefix) && name.endsWith(".class") && !name.stripPrefix(prefix).contains('/') =>
        name.stripSuffix(".class").replace('/', '.')
    }
  }

  /** Every concrete `LLMError` class in `org.llm4s.error`. */
  private lazy val concreteErrorClasses: Set[Class[?]] =
    errorPackageClassNames
      .map(name => Class.forName(name, false, getClass.getClassLoader))
      .filter(c => classOf[LLMError].isAssignableFrom(c) && !c.isInterface && !Modifier.isAbstract(c.getModifiers))
      .toSet

  /** One error of each class, with the kind it reads as and whether core calls it retryable. */
  private val oneOfEach: List[(LLMError, LlmErrorKind, Boolean)] = List(
    (AuthenticationError("openai", "bad key"), LlmErrorKind.AUTHENTICATION, false),
    (RateLimitError("openai"), LlmErrorKind.RATE_LIMIT, true),
    (TimeoutError("slow", 5.seconds, "complete"), LlmErrorKind.TIMEOUT, true),
    (NetworkError("down", None, "http://x"), LlmErrorKind.NETWORK, true),
    (ServiceError(503, "openai", "overloaded"), LlmErrorKind.SERVICE, true),
    (ValidationError.required("query"), LlmErrorKind.VALIDATION, false),
    (InvalidInputError("image", "x", "not an image"), LlmErrorKind.VALIDATION, false),
    (UnsupportedContentPartError("ollama", "image (url, image/png)", "user"), LlmErrorKind.VALIDATION, false),
    (ContentFilteredError.reply("Gemini", "SAFETY"), LlmErrorKind.CONTENT_FILTER, false),
    (ContextLengthError("openai", "too long"), LlmErrorKind.CONTEXT_LENGTH, false),
    (ModelNotFoundError("openai", "no such model"), LlmErrorKind.MODEL_NOT_FOUND, false),
    (ConfigurationError("no provider"), LlmErrorKind.CONFIGURATION, false),
    (EmbeddingError(Some("503"), "overloaded", "voyage"), LlmErrorKind.SERVICE, true),
    (CancelledError("complete"), LlmErrorKind.CANCELLED, false),
    (ContextError.artifactStoreFailed("store", "key", "disk full"), LlmErrorKind.OTHER, false),
    (ExecutionError("exit 1", "shell"), LlmErrorKind.OTHER, true),
    (NotFoundError("Memory not found", "m1"), LlmErrorKind.OTHER, false),
    (OptimisticLockFailure("stale", "m1", 2L), LlmErrorKind.OTHER, false),
    (CircuitOpenError.open("openai", 30.seconds), LlmErrorKind.OTHER, false),
    (ProcessingError("parse", "bad json"), LlmErrorKind.OTHER, false),
    (SimpleError("plain"), LlmErrorKind.OTHER, false),
    (TokenizerError.notAvailable("cl100k"), LlmErrorKind.OTHER, false),
    (UnknownError("what", new RuntimeException("x")), LlmErrorKind.OTHER, false),
    (ValidationUnsupportedError("BedrockClient"), LlmErrorKind.OTHER, false)
  )

  /** Provider error responses whose status gives them a kind other than their class's `SERVICE`. */
  private val remapped: List[LLMError] =
    List(401, 403, 400, 429).map(s => ServiceError(s, "p", "x"))

  "the scan of org.llm4s.error" should "find the error classes, so an empty scan cannot pass the exhaustiveness check" in {
    concreteErrorClasses.size should be >= 19
    (concreteErrorClasses should contain).allOf(classOf[ServiceError], classOf[RateLimitError], classOf[CancelledError])
    // traits and companions are not error classes
    concreteErrorClasses should not contain classOf[LLMError]
    concreteErrorClasses.exists(_.getName.endsWith("$")) shouldBe false
  }

  "every concrete LLMError class in org.llm4s.error" should "map to a kind: a new class fails here until it is given one" in {
    val unmapped = concreteErrorClasses -- LlmErrorKinds.byClass.keySet
    withClue(s"give these a kind in LlmErrorKinds.byClass: ${unmapped.map(_.getName).toList.sorted.mkString(", ")}\n") {
      unmapped shouldBe empty
    }
    // and the table names nothing that is not there
    LlmErrorKinds.byClass.keySet.map(c => c: Class[?]) shouldBe concreteErrorClasses
  }

  "getKind" should "read each error class as its kind, and isRetryable agree with core" in {
    oneOfEach.map(_._1.getClass).toSet shouldBe concreteErrorClasses
    oneOfEach.foreach { case (error, kind, retryable) =>
      val e = new LlmException(error)
      withClue(error.getClass.getSimpleName) {
        e.getKind shouldBe kind
        e.getKind shouldBe LlmErrorKinds.byClass(error.getClass)
        e.isRetryable shouldBe retryable
        e.isRetryable shouldBe error.isRetryable
      }
    }
  }

  it should "read a provider's error response by the status core gives a class of its own" in {
    def kind(error: LLMError): LlmErrorKind = new LlmException(error).getKind

    Seq(401, 403).foreach(s => kind(ServiceError(s, "p", "no")) shouldBe LlmErrorKind.AUTHENTICATION)
    kind(ServiceError(429, "p", "slow down")) shouldBe LlmErrorKind.RATE_LIMIT
    kind(ServiceError(400, "p", "bad request")) shouldBe LlmErrorKind.VALIDATION
    Seq(404, 500, 502, 503).foreach(s => kind(ServiceError(s, "p", "x")) shouldBe LlmErrorKind.SERVICE)

  }

  it should "say a status that gives another kind is retryable only when the status is" in {
    // a provider client may report a rejected key as a plain ServiceError; it reads as AUTHENTICATION, and is no
    // more retryable than an AuthenticationError: the one rule follows the status, not the class
    val forbidden = new LlmException(ServiceError(403, "bedrock", "access denied"))
    (forbidden.getKind, forbidden.isRetryable) shouldBe ((LlmErrorKind.AUTHENTICATION, false))
    CompletionCheck.failure(forbidden).asScala.toList shouldBe
      List("kind:authentication", "retryable:false", "retryAfter:none", "status:403", "requestId:none")

    remapped.foreach { error =>
      withClue(error) {
        new LlmException(error).isRetryable shouldBe error.httpStatus.contains(429)
        new LlmException(error).getKind should not be LlmErrorKind.SERVICE
      }
    }
  }

  it should "read an embedding provider's error response by its status, as a ServiceError with that status reads" in {
    def kind(code: String): LlmErrorKind = new LlmException(EmbeddingError(Some(code), "x", "voyage")).getKind

    Seq("401", "403").foreach(kind(_) shouldBe LlmErrorKind.AUTHENTICATION)
    kind("429") shouldBe LlmErrorKind.RATE_LIMIT
    kind("400") shouldBe LlmErrorKind.VALIDATION
    Seq("404", "500", "503", " 502 ").foreach(kind(_) shouldBe LlmErrorKind.SERVICE)
    Seq("401", "429", "500").foreach(code =>
      new LlmException(EmbeddingError(Some(code), "x", "jina")).getKind shouldBe
        new LlmException(ServiceError(code.toInt, "jina", "x")).getKind
    )
  }

  it should "read an embedding error with no status - an unreachable provider, an unparsable reply - as OTHER" in {
    def error(code: Option[String]): LlmException =
      new LlmException(EmbeddingError(code, "HTTP request failed", "openai"))

    // no code, a code that is not a number, and numbers that are not HTTP statuses
    Seq(None, Some("timeout"), Some(""), Some("99"), Some("600"), Some("-1")).foreach { code =>
      withClue(code) {
        error(code).getKind shouldBe LlmErrorKind.OTHER
        error(code).getStatusCode shouldBe OptionalInt.empty()
      }
    }
    Seq("100", "599").foreach(code => error(Some(code)).getStatusCode shouldBe OptionalInt.of(code.toInt))
  }

  it should "leave whether an embedding error is retryable to core, which follows its status" in {
    val limited = new LlmException(EmbeddingError(Some("429"), "slow down", "cohere"))
    CompletionCheck.failure(limited).asScala.toList shouldBe
      List("kind:rate-limit", "retryable:true", "retryAfter:none", "status:429", "requestId:none")
    (EmbeddingError(Some("503"), "x", "p")).isRetryable shouldBe true
    (EmbeddingError(Some("401"), "x", "p")).isRetryable shouldBe false
    (EmbeddingError(None, "x", "p")).isRetryable shouldBe false
  }

  it should "read an error from another module, or one implemented in Java, as OTHER" in {
    new LlmException(SpeechLikeError("no voice")).getKind shouldBe LlmErrorKind.OTHER
    val javaError = new JavaInteropCheck.JavaError("from Java")
    new LlmException(javaError).getKind shouldBe LlmErrorKind.OTHER
    new LlmException(javaError).isRetryable shouldBe false
  }

  it should "be switched over from Java source with no default, every kind covered" in {
    LlmErrorKind.values.toList.map(CompletionCheck.describe) shouldBe List(
      "authentication",
      "rate-limit",
      "timeout",
      "network",
      "service",
      "validation",
      "content-filter",
      "context-length",
      "model-not-found",
      "configuration",
      "cancelled",
      "other"
    )
  }

  "getRetryAfter" should "give the delay a rate limit or a provider's error response asked for, as a java.time.Duration" in {
    new LlmException(RateLimitError("p", 1500.millis)).getRetryAfter shouldBe Optional.of(Duration.ofMillis(1500))
    new LlmException(ServiceError(503, "p", "x").withRetryAfter(2.minutes)).getRetryAfter shouldBe
      Optional.of(Duration.ofMinutes(2))
  }

  it should "be empty when the provider gave no delay - not the library's default backoff - and for other errors" in {
    org.llm4s.reliability.RetryPolicy.DefaultRateLimitDelay shouldBe 30.seconds
    RateLimitError("p").retryAfter shouldBe None
    new LlmException(RateLimitError("p")).getRetryAfter shouldBe Optional.empty()
    new LlmException(ServiceError(503, "p", "x")).getRetryAfter shouldBe Optional.empty()
    new LlmException(NetworkError("down", None, "x")).getRetryAfter shouldBe Optional.empty()
  }

  "getStatusCode" should "give the HTTP status of a provider's error response, and be empty otherwise" in {
    new LlmException(ServiceError(502, "p", "x")).getStatusCode shouldBe OptionalInt.of(502)
    new LlmException(ServiceError(429, "p", "x")).getStatusCode shouldBe OptionalInt.of(429)
    new LlmException(NetworkError("down", None, "x")).getStatusCode shouldBe OptionalInt.empty()
    new LlmException(AuthenticationError("p", "x")).getStatusCode shouldBe OptionalInt.empty()
  }

  it should "give the status of every error mapped from a response, whatever its kind" in {
    new LlmException(AuthenticationError("p", "x", "401")).getStatusCode shouldBe OptionalInt.of(401)
    new LlmException(RateLimitError("p").withHttpStatus(429)).getStatusCode shouldBe OptionalInt.of(429)
    new LlmException(ValidationError("request", "bad").withHttpStatus(400)).getStatusCode shouldBe OptionalInt.of(400)
    new LlmException(ContextLengthError("p", "too long").withHttpStatus(400)).getStatusCode shouldBe
      OptionalInt.of(400)
    new LlmException(ModelNotFoundError("p", "no such model").withHttpStatus(404)).getStatusCode shouldBe
      OptionalInt.of(404)
  }

  "getRequestId" should "give the provider's id for the failed request, and be empty when it sent none" in {
    new LlmException(ServiceError(500, "p", "x", "req-1")).getRequestId shouldBe Optional.of("req-1")
    new LlmException(RateLimitError("p").withRequestId("req-2")).getRequestId shouldBe Optional.of("req-2")
    new LlmException(ServiceError(500, "p", "x")).getRequestId shouldBe Optional.empty()
    new LlmException(NetworkError("down", None, "x")).getRequestId shouldBe Optional.empty()
  }

  "the new kinds" should "tell a content filter, a context-length overflow and an unknown model from VALIDATION" in {
    new LlmException(ContentFilteredError.prompt("Gemini", "SAFETY")).getKind shouldBe LlmErrorKind.CONTENT_FILTER
    // a 400 that is a context-length overflow is not read by its status as VALIDATION
    new LlmException(ContextLengthError("p", "too long").withHttpStatus(400)).getKind shouldBe
      LlmErrorKind.CONTEXT_LENGTH
    new LlmException(ModelNotFoundError("p", "nope").withHttpStatus(404)).getKind shouldBe LlmErrorKind.MODEL_NOT_FOUND
    // a missing configuration value or record is not a model
    new LlmException(NotFoundError("Memory not found", "m1")).getKind shouldBe LlmErrorKind.OTHER
  }

  "a failure read from Java" should "give the kind, whether it is retryable, the retry delay, status and request id with Java types only" in {
    def fromJava(error: LLMError): List[String] = CompletionCheck.failure(new LlmException(error)).asScala.toList

    fromJava(RateLimitError("p", 3.seconds)) shouldBe
      List("kind:rate-limit", "retryable:true", "retryAfter:3000", "status:none", "requestId:none")
    fromJava(ServiceError(503, "p", "x")) shouldBe
      List("kind:service", "retryable:true", "retryAfter:none", "status:503", "requestId:none")
    fromJava(AuthenticationError("p", "bad key")) shouldBe
      List("kind:authentication", "retryable:false", "retryAfter:none", "status:none", "requestId:none")
  }

  it should "come from a failed client call" in {
    val failed = LlmResult.failure[JCompletion](TimeoutError("slow", 30.seconds, "complete"))
    failed.getError().getKind shouldBe LlmErrorKind.TIMEOUT
    failed.getError().isRetryable shouldBe true
  }

  "the kind table of docs/guide/java.md" should "say of each kind what isRetryable says of its errors" in {
    val GuideFile = "docs/guide/java.md"
    val row       = "(?m)^\\| `([A-Z_]+)` \\| [^|]+ \\| ([^|]+) \\|$".r
    val retryableColumn: Map[LlmErrorKind, String] =
      row
        .findAllMatchIn(GuideDocs.read(GuideFile, GuideFile))
        .map(m => LlmErrorKind.valueOf(m.group(1)) -> m.group(2).trim)
        .toMap
    retryableColumn.keySet shouldBe LlmErrorKind.values.toSet

    val embedding = (None :: List(400, 401, 403, 429, 500).map(s => Some(s.toString)))
      .map(code => EmbeddingError(code, "x", "voyage"))
    (oneOfEach.map(_._1) ++ remapped ++ embedding).foreach { error =>
      val e         = new LlmException(error)
      val cell = retryableColumn(e.getKind)
      withClue(s"$error reads as ${e.getKind}, whose row says '$cell': ") {
        cell match {
          case "yes"                        => e.isRetryable shouldBe true
          case "no"                         => e.isRetryable shouldBe false
          case c if c.startsWith("depends") => succeed
          case c =>
            c shouldBe "for status 408, 429 and 5xx"
            e.isRetryable shouldBe error.httpStatus.exists(s => s == 408 || s == 429 || s >= 500)
        }
      }
    }
  }
}

object LlmErrorKindSpec {

  /** An error of the kind another llm4s module defines outside `org.llm4s.error`, as `llm4s-speech` does. */
  final case class SpeechLikeError(message: String) extends LLMError
}
