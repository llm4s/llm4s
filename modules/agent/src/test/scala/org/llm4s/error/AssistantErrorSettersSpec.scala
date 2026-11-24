package org.llm4s.error

import org.llm4s.error.AssistantError._
import org.llm4s.types.{ FilePath, SessionId }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * The growth-safe shape of the `AssistantError` cases (#2383): each companion `apply` keeps the previous defaults,
 * and each `with*` setter changes exactly its field.
 */
class AssistantErrorSettersSpec extends AnyFlatSpec with Matchers {

  private val boom  = new RuntimeException("boom")
  private val other = new IllegalStateException("other")

  "IOError.apply" should "default cause to None" in {
    IOError("m", "read").cause shouldBe None
  }

  "IOError setters" should "change exactly their field" in {
    val e = IOError("m", "read")
    e.withMessage("n") shouldBe IOError("n", "read")
    e.withOperation("write") shouldBe IOError("m", "write")
    e.withCause(boom) shouldBe IOError("m", "read", Some(boom))
    e.withCause(boom).withCause(None) shouldBe e
    e.withCause(Some(other)).cause shouldBe Some(other)
  }

  "EOFError.apply" should "default operation to read" in {
    EOFError("m").operation shouldBe "read"
  }

  "EOFError setters" should "change exactly their field" in {
    val e = EOFError("m")
    e.withMessage("n") shouldBe EOFError("n")
    e.withOperation("write") shouldBe EOFError("m", "write")
  }

  "DisplayError.apply" should "default cause to None" in {
    DisplayError("m", "welcome").cause shouldBe None
  }

  "DisplayError setters" should "change exactly their field" in {
    val e = DisplayError("m", "welcome")
    e.withMessage("n") shouldBe DisplayError("n", "welcome")
    e.withDisplayType("error") shouldBe DisplayError("m", "error")
    e.withCause(boom) shouldBe DisplayError("m", "welcome", Some(boom))
    e.withCause(boom).withCause(None) shouldBe e
    e.withCause(Some(other)).cause shouldBe Some(other)
  }

  "SessionError.apply" should "default cause and llmCause to None" in {
    val e = SessionError("m", SessionId("s1"), "load")
    e.cause shouldBe None
    e.llmCause shouldBe None
  }

  "SessionError setters" should "change exactly their field" in {
    val e   = SessionError("m", SessionId("s1"), "load")
    val llm = SimpleError("llm failed")
    e.withMessage("n") shouldBe SessionError("n", SessionId("s1"), "load")
    e.withSessionId(SessionId("s2")) shouldBe SessionError("m", SessionId("s2"), "load")
    e.withOperation("save") shouldBe SessionError("m", SessionId("s1"), "save")
    e.withCause(boom) shouldBe SessionError("m", SessionId("s1"), "load", Some(boom))
    e.withCause(boom).withCause(None) shouldBe e
    e.withCause(Some(other)).cause shouldBe Some(other)
    e.withLlmCause(llm) shouldBe SessionError("m", SessionId("s1"), "load", llmCause = Some(llm))
    e.withLlmCause(llm).withLlmCause(None) shouldBe e
    e.withLlmCause(Some(llm)).context("llmErrorType") shouldBe "SimpleError"
  }

  "FileError.apply" should "default cause to None" in {
    FileError("m", FilePath("/a"), "read").cause shouldBe None
  }

  "FileError setters" should "change exactly their field" in {
    val e = FileError("m", FilePath("/a"), "read")
    e.withMessage("n") shouldBe FileError("n", FilePath("/a"), "read")
    e.withPath(FilePath("/b")) shouldBe FileError("m", FilePath("/b"), "read")
    e.withOperation("write") shouldBe FileError("m", FilePath("/a"), "write")
    e.withCause(boom) shouldBe FileError("m", FilePath("/a"), "read", Some(boom))
    e.withCause(boom).withCause(None) shouldBe e
    e.withCause(Some(other)).cause shouldBe Some(other)
  }

  "SerializationError.apply" should "default cause to None" in {
    SerializationError("m", "JSON", "serialize").cause shouldBe None
  }

  "SerializationError setters" should "change exactly their field" in {
    val e = SerializationError("m", "JSON", "serialize")
    e.withMessage("n") shouldBe SerializationError("n", "JSON", "serialize")
    e.withDataType("SessionState") shouldBe SerializationError("m", "SessionState", "serialize")
    e.withOperation("deserialize") shouldBe SerializationError("m", "JSON", "deserialize")
    e.withCause(boom) shouldBe SerializationError("m", "JSON", "serialize", Some(boom))
    e.withCause(boom).withCause(None) shouldBe e
    e.withCause(Some(other)).cause shouldBe Some(other)
  }

  "CommandParseError.apply" should "default cause to None" in {
    CommandParseError("m", "/save", "validate-title").cause shouldBe None
  }

  "CommandParseError setters" should "change exactly their field" in {
    val e = CommandParseError("m", "/save", "validate-title")
    e.withMessage("n") shouldBe CommandParseError("n", "/save", "validate-title")
    e.withCommand("/load") shouldBe CommandParseError("m", "/load", "validate-title")
    e.withParseOperation("parse-command") shouldBe CommandParseError("m", "/save", "parse-command")
    e.withCause(boom) shouldBe CommandParseError("m", "/save", "validate-title", Some(boom))
    e.withCause(boom).withCause(None) shouldBe e
    e.withCause(Some(other)).cause shouldBe Some(other)
  }

  "AssistantError cases" should "still pattern match positionally" in {
    val errors: Seq[AssistantError] = Seq(
      IOError("io", "read"),
      EOFError("eof"),
      DisplayError("d", "welcome"),
      SessionError("s", SessionId("s1"), "load"),
      FileError("f", FilePath("/a"), "read"),
      SerializationError("z", "JSON", "serialize"),
      CommandParseError("c", "/x", "parse-command")
    )
    val described = errors.map {
      case IOError(m, op, _)                => s"$m:$op"
      case EOFError(m, op)                  => s"$m:$op"
      case DisplayError(m, t, _)            => s"$m:$t"
      case SessionError(m, id, op, _, _)    => s"$m:${id.value}:$op"
      case FileError(m, p, op, _)           => s"$m:${p.value}:$op"
      case SerializationError(m, dt, op, _) => s"$m:$dt:$op"
      case CommandParseError(m, c, op, _)   => s"$m:$c:$op"
    }
    described shouldBe Seq(
      "io:read",
      "eof:read",
      "d:welcome",
      "s:s1:load",
      "f:/a:read",
      "z:JSON:serialize",
      "c:/x:parse-command"
    )
  }
}
