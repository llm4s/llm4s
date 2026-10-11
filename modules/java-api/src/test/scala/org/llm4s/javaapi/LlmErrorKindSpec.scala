package org.llm4s.javaapi

import org.llm4s.error._
import org.llm4s.llmconnect.model.EmbeddingError
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
 * `LlmException.getKind`, `isRecoverable`, `getRetryAfter` and `getStatusCode` (#1487).
 *
 * `LLMError` is an open trait, so the compiler cannot check that every error class has a kind. This spec does instead:
 * it lists every concrete `LLMError` class compiled into `org.llm4s.error` and fails when one has no entry in
 * `LlmErrorKinds.byClass` - a class added to core without a kind would otherwise read as `OTHER` without anyone
 * deciding so.
 */
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

  /** One error of each class, with the kind it reads as and whether core calls it recoverable. */
  private val oneOfEach: List[(LLMError, LlmErrorKind, Boolean)] = List(
    (AuthenticationError("openai", "bad key"), LlmErrorKind.AUTHENTICATION, false),
    (RateLimitError("openai"), LlmErrorKind.RATE_LIMIT, true),
    (TimeoutError("slow", 5.seconds, "complete"), LlmErrorKind.TIMEOUT, true),
    (NetworkError("down", None, "http://x"), LlmErrorKind.NETWORK, true),
    (ServiceError(503, "openai", "overloaded"), LlmErrorKind.SERVICE, true),
    (APIError("openai", "odd reply"), LlmErrorKind.SERVICE, true),
    (ValidationError.required("query"), LlmErrorKind.VALIDATION, false),
    (InvalidInputError("image", "x", "not an image"), LlmErrorKind.VALIDATION, false),
    (ConfigurationError("no provider"), LlmErrorKind.CONFIGURATION, false),
    (CancelledError("complete"), LlmErrorKind.CANCELLED, false),
    (ContextError.invalidTrimming("empty"), LlmErrorKind.OTHER, false),
    (ExecutionError("exit 1", "shell"), LlmErrorKind.OTHER, true),
    (NotFoundError("Memory not found", "m1"), LlmErrorKind.OTHER, false),
    (OptimisticLockFailure("stale", "m1", 2L), LlmErrorKind.OTHER, true),
    (ProcessingError("parse", "bad json"), LlmErrorKind.OTHER, false),
    (SimpleError("plain"), LlmErrorKind.OTHER, false),
    (SystemError("disk full"), LlmErrorKind.OTHER, true),
    (TokenizerError.notFound("cl100k"), LlmErrorKind.OTHER, false),
    (UnknownError("what", new RuntimeException("x")), LlmErrorKind.OTHER, false)
  )

  /** Provider error responses whose status gives them a kind other than their class's `SERVICE`. */
  private val remapped: List[LLMError] =
    List(401, 403, 400, 429).flatMap(s => List(ServiceError(s, "p", "x"), APIError("p", "x", Some(s))))

  "the scan of org.llm4s.error" should "find the error classes, so an empty scan cannot pass the exhaustiveness check" in {
    concreteErrorClasses.size should be >= 19
    (concreteErrorClasses should contain).allOf(classOf[ServiceError], classOf[RateLimitError], classOf[CancelledError])
    // traits and companions are not error classes
    concreteErrorClasses should not contain classOf[RecoverableError]
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

  "getKind" should "read each error class as its kind, and isRecoverable agree with core" in {
    oneOfEach.map(_._1.getClass).toSet shouldBe concreteErrorClasses
    oneOfEach.foreach { case (error, kind, recoverable) =>
      val e = new LlmException(error)
      withClue(error.getClass.getSimpleName) {
        e.getKind shouldBe kind
        e.getKind shouldBe LlmErrorKinds.byClass(error.getClass)
        e.isRecoverable shouldBe recoverable
        e.isRecoverable shouldBe LLMError.isRecoverable(error)
      }
    }
  }

  it should "read a provider's error response by the status core gives a class of its own" in {
    def kind(error: LLMError): LlmErrorKind = new LlmException(error).getKind

    Seq(401, 403).foreach(s => kind(ServiceError(s, "p", "no")) shouldBe LlmErrorKind.AUTHENTICATION)
    kind(ServiceError(429, "p", "slow down")) shouldBe LlmErrorKind.RATE_LIMIT
    kind(ServiceError(400, "p", "bad request")) shouldBe LlmErrorKind.VALIDATION
    Seq(404, 500, 502, 503).foreach(s => kind(ServiceError(s, "p", "x")) shouldBe LlmErrorKind.SERVICE)

    kind(APIError("p", "no", Some(401))) shouldBe LlmErrorKind.AUTHENTICATION
    kind(APIError("p", "slow", Some(429))) shouldBe LlmErrorKind.RATE_LIMIT
    kind(APIError("p", "bad", Some(400))) shouldBe LlmErrorKind.VALIDATION
    kind(APIError("p", "down", Some(500))) shouldBe LlmErrorKind.SERVICE
  }

  it should "keep the error class's recoverability when a status gives it another kind" in {
    // a provider client may report a rejected key as a plain ServiceError; it reads as AUTHENTICATION, yet a
    // ServiceError is a RecoverableError, so isRecoverable stays true - the reason the guide says to call it
    val forbidden = new LlmException(ServiceError(403, "bedrock", "access denied"))
    (forbidden.getKind, forbidden.isRecoverable) shouldBe ((LlmErrorKind.AUTHENTICATION, true))
    CompletionCheck.failure(forbidden).asScala.toList shouldBe
      List("kind:authentication", "recoverable:true", "retryAfter:none", "status:403")

    remapped.foreach { error =>
      withClue(error) {
        new LlmException(error).isRecoverable shouldBe true
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

  it should "leave an embedding error's recoverability to core, which calls none recoverable" in {
    val limited = new LlmException(EmbeddingError(Some("429"), "slow down", "cohere"))
    CompletionCheck.failure(limited).asScala.toList shouldBe
      List("kind:rate-limit", "recoverable:false", "retryAfter:none", "status:429")
    LLMError.isRecoverable(EmbeddingError(Some("503"), "x", "p")) shouldBe false
  }

  it should "read an error from another module, or one implemented in Java, as OTHER" in {
    new LlmException(SpeechLikeError("no voice")).getKind shouldBe LlmErrorKind.OTHER
    val javaError = new JavaInteropCheck.JavaError("from Java")
    new LlmException(javaError).getKind shouldBe LlmErrorKind.OTHER
    new LlmException(javaError).isRecoverable shouldBe false
  }

  it should "be switched over from Java source with no default, every kind covered" in {
    LlmErrorKind.values.toList.map(CompletionCheck.describe) shouldBe List(
      "authentication",
      "rate-limit",
      "timeout",
      "network",
      "service",
      "validation",
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
    RateLimitError("p").retryDelay shouldBe Some(RateLimitError.DefaultRetryDelay)
    new LlmException(RateLimitError("p")).getRetryAfter shouldBe Optional.empty()
    new LlmException(ServiceError(503, "p", "x")).getRetryAfter shouldBe Optional.empty()
    new LlmException(NetworkError("down", None, "x")).getRetryAfter shouldBe Optional.empty()
  }

  "getStatusCode" should "give the HTTP status of a provider's error response, and be empty otherwise" in {
    new LlmException(ServiceError(502, "p", "x")).getStatusCode shouldBe OptionalInt.of(502)
    new LlmException(ServiceError(429, "p", "x")).getStatusCode shouldBe OptionalInt.of(429)
    new LlmException(APIError("p", "x", Some(500))).getStatusCode shouldBe OptionalInt.of(500)
    new LlmException(APIError("p", "x")).getStatusCode shouldBe OptionalInt.empty()
    new LlmException(AuthenticationError("p", "x")).getStatusCode shouldBe OptionalInt.empty()
  }

  "a failure read from Java" should "give the kind, recoverability, retry delay and status with Java types only" in {
    def fromJava(error: LLMError): List[String] = CompletionCheck.failure(new LlmException(error)).asScala.toList

    fromJava(RateLimitError("p", 3.seconds)) shouldBe
      List("kind:rate-limit", "recoverable:true", "retryAfter:3000", "status:none")
    fromJava(ServiceError(503, "p", "x")) shouldBe
      List("kind:service", "recoverable:true", "retryAfter:none", "status:503")
    fromJava(AuthenticationError("p", "bad key")) shouldBe
      List("kind:authentication", "recoverable:false", "retryAfter:none", "status:none")
  }

  it should "come from a failed client call" in {
    val failed = LlmResult.failure[JCompletion](TimeoutError("slow", 30.seconds, "complete"))
    failed.getError().getKind shouldBe LlmErrorKind.TIMEOUT
    failed.getError().isRecoverable shouldBe true
  }

  "the kind table of docs/guide/java.md" should "say of each kind what isRecoverable says of its errors" in {
    val GuideFile = "docs/guide/java.md"
    val row       = "(?m)^\\| `([A-Z_]+)` \\| [^|]+ \\| ([^|]+) \\|$".r
    val recoverableColumn: Map[LlmErrorKind, String] =
      row
        .findAllMatchIn(GuideDocs.read(GuideFile, GuideFile))
        .map(m => LlmErrorKind.valueOf(m.group(1)) -> m.group(2).trim)
        .toMap
    recoverableColumn.keySet shouldBe LlmErrorKind.values.toSet

    val embedding = (None :: List(400, 401, 403, 429, 500).map(s => Some(s.toString)))
      .map(code => EmbeddingError(code, "x", "voyage"))
    (oneOfEach.map(_._1) ++ remapped ++ embedding).foreach { error =>
      val e         = new LlmException(error)
      val cell      = recoverableColumn(e.getKind)
      val embedding = error.isInstanceOf[EmbeddingError]
      withClue(s"$error reads as ${e.getKind}, whose row says '$cell': ") {
        cell match {
          case "yes"                                             => e.isRecoverable shouldBe true
          case "yes, unless it is an embedding provider's error" => e.isRecoverable shouldBe !embedding
          case "no"                                              => e.isRecoverable shouldBe false
          case c if c.startsWith("depends")                      => succeed
          case c =>
            c should startWith("no, unless it is a provider's ")
            c should endWith(" reported as a service error")
            // recoverable exactly when it is a service error with one of the statuses the row names
            e.isRecoverable shouldBe (!embedding && LlmErrorKinds.statusCode(error).exists(s => c.contains(s"`$s`")))
        }
      }
    }
  }
}

object LlmErrorKindSpec {

  /** An error of the kind another llm4s module defines outside `org.llm4s.error`, as `llm4s-speech` does. */
  final case class SpeechLikeError(message: String) extends LLMError
}
