package org.llm4s.llmconnect.middleware

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.slf4j.Logger
import scala.collection.mutable.ArrayBuffer


class IntegrationSpec extends AnyFlatSpec with Matchers {

  class FakeLogger extends Logger {
    val logs = ArrayBuffer[String]()
    // Implement required methods minimally
    override def getName: String = "FakeLogger"
    override def isTraceEnabled: Boolean = true
    override def trace(msg: String): Unit = logs += s"[TRACE] $msg"
    override def isDebugEnabled: Boolean = true
    override def debug(msg: String): Unit = logs += s"[DEBUG] $msg"
    override def isInfoEnabled: Boolean = true
    override def info(msg: String): Unit = logs += s"[INFO] $msg"
    override def isWarnEnabled: Boolean = true
    override def warn(msg: String): Unit = logs += s"[WARN] $msg"
    override def isErrorEnabled: Boolean = true
    override def error(msg: String): Unit = logs += s"[ERROR] $msg"
    
    // Boilerplate for other methods
    override def debug(format: String, arg: Any): Unit = ()
    override def debug(format: String, arg1: Any, arg2: Any): Unit = ()
    override def debug(format: String, arguments: Any*): Unit = ()
    override def debug(msg: String, t: Throwable): Unit = ()
    override def info(format: String, arg: Any): Unit = ()
    override def info(format: String, arg1: Any, arg2: Any): Unit = ()
    override def info(format: String, arguments: Any*): Unit = ()
    override def info(msg: String, t: Throwable): Unit = ()
    override def warn(format: String, arg: Any): Unit = ()
    override def warn(format: String, arguments: Any*): Unit = ()
    override def warn(format: String, arg1: Any, arg2: Any): Unit = ()
    override def warn(msg: String, t: Throwable): Unit = ()
    override def error(format: String, arg: Any): Unit = ()
    override def error(format: String, arg1: Any, arg2: Any): Unit = ()
    override def error(format: String, arguments: Any*): Unit = ()
    override def error(msg: String, t: Throwable): Unit = ()
    override def trace(format: String, arg: Any): Unit = ()
    override def trace(format: String, arg1: Any, arg2: Any): Unit = ()
    override def trace(format: String, arguments: Any*): Unit = ()
    override def trace(msg: String, t: Throwable): Unit = ()
    override def isDebugEnabled(marker: org.slf4j.Marker): Boolean = false
    override def debug(marker: org.slf4j.Marker, msg: String): Unit = ()
    override def debug(marker: org.slf4j.Marker, format: String, arg: Any): Unit = ()
    override def debug(marker: org.slf4j.Marker, format: String, arg1: Any, arg2: Any): Unit = ()
    override def debug(marker: org.slf4j.Marker, format: String, arguments: Any*): Unit = ()
    override def debug(marker: org.slf4j.Marker, msg: String, t: Throwable): Unit = ()
    override def isInfoEnabled(marker: org.slf4j.Marker): Boolean = false
    override def info(marker: org.slf4j.Marker, msg: String): Unit = ()
    override def info(marker: org.slf4j.Marker, format: String, arg: Any): Unit = ()
    override def info(marker: org.slf4j.Marker, format: String, arg1: Any, arg2: Any): Unit = ()
    override def info(marker: org.slf4j.Marker, format: String, arguments: Any*): Unit = ()
    override def info(marker: org.slf4j.Marker, msg: String, t: Throwable): Unit = ()
    override def isWarnEnabled(marker: org.slf4j.Marker): Boolean = false
    override def warn(marker: org.slf4j.Marker, msg: String): Unit = ()
    override def warn(marker: org.slf4j.Marker, format: String, arg: Any): Unit = ()
    override def warn(marker: org.slf4j.Marker, format: String, arg1: Any, arg2: Any): Unit = ()
    override def warn(marker: org.slf4j.Marker, format: String, arguments: Any*): Unit = ()
    override def warn(marker: org.slf4j.Marker, msg: String, t: Throwable): Unit = ()
    override def isErrorEnabled(marker: org.slf4j.Marker): Boolean = false
    override def error(marker: org.slf4j.Marker, msg: String): Unit = ()
    override def error(marker: org.slf4j.Marker, format: String, arg: Any): Unit = ()
    override def error(marker: org.slf4j.Marker, format: String, arg1: Any, arg2: Any): Unit = ()
    override def error(marker: org.slf4j.Marker, format: String, arguments: Any*): Unit = ()
    override def error(marker: org.slf4j.Marker, msg: String, t: Throwable): Unit = ()
    override def isTraceEnabled(marker: org.slf4j.Marker): Boolean = false
    override def trace(marker: org.slf4j.Marker, msg: String): Unit = ()
    override def trace(marker: org.slf4j.Marker, format: String, arg: Any): Unit = ()
    override def trace(marker: org.slf4j.Marker, format: String, arg1: Any, arg2: Any): Unit = ()
    override def trace(marker: org.slf4j.Marker, format: String, arguments: Any*): Unit = ()
    override def trace(marker: org.slf4j.Marker, msg: String, t: Throwable): Unit = ()
  }

  class MockClient extends LLMClient {
    override def complete(c: Conversation, o: CompletionOptions): Result[Completion] = 
      Right(Completion("id", System.currentTimeMillis(), "Mock Response", "model", AssistantMessage("Mock Response")))
      
    override def streamComplete(c: Conversation, o: CompletionOptions, onChunk: StreamedChunk => Unit): Result[Completion] = {
      onChunk(StreamedChunk("id", Some("Mock"), None, None, None))
      onChunk(StreamedChunk("id", Some(" Response"), None, Some("stop"), None))
      Right(Completion("id", System.currentTimeMillis(), "Mock Response", "model", AssistantMessage("Mock Response")))
    }
      
    override def getContextWindow(): Int = 8192
    override def getReserveCompletion(): Int = 2048
  }

  "LLMClientPipeline Integration" should "apply multiple middleware correctly" in {
    val logger = new FakeLogger()
    val baseClient = new MockClient()
    
    val client = LLMClientPipeline(baseClient)
      .use(new RequestIdMiddleware()) // Innermost
      .use(new LoggingMiddleware(logger = logger))
      .use(new InputSanitizationMiddleware(maxTotalCharacters = 100)) // Outermost
      .build()
      
    // 1. Valid Request
    val conversation = Conversation(Seq(UserMessage("Hello")))
    val result = client.complete(conversation)
    
    result.isRight shouldBe true
    result.map(_.content) shouldBe Right("Mock Response")
    
    // Verify logging
    logger.logs.exists(_.contains("[DEBUG] Request:")) shouldBe true
    logger.logs.exists(_.contains("[DEBUG] Success")) shouldBe true
    
    // 2. Invalid Request (Sanitization)
    val invalidConversation = Conversation(Seq(UserMessage("A" * 101)))
    val invalidResult = client.complete(invalidConversation)
    
    invalidResult.isLeft shouldBe true
    // invalidResult.left.get shouldBe a [org.llm4s.error.InvalidInputError] // usage deprecated
    invalidResult.swap.getOrElse(fail("Expected failure")).shouldBe(a[org.llm4s.error.InvalidInputError])
    
    // Logging for failure
    // Sanitization is added AFTER Logging, so it wraps Logging.
    // Sanitization failure returns Left directly; Logging (inner) is not invoked.
    logger.logs.exists(_.contains("[WARN]")) shouldBe false
  }
}
