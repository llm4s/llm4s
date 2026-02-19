package org.llm4s.llmconnect.middleware


import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.slf4j.Logger
import scala.collection.mutable.ArrayBuffer

class LoggingMiddlewareSpec extends AnyFlatSpec with Matchers {

  // A simple fake logger to capture calls
  class FakeLogger extends Logger {
    val debugs = ArrayBuffer[String]()
    val warns = ArrayBuffer[String]()
    val traces = ArrayBuffer[String]()

    override def getName: String = "FakeLogger"
    override def isDebugEnabled: Boolean = true
    override def debug(msg: String): Unit = debugs += msg
    override def isWarnEnabled: Boolean = true
    override def warn(msg: String): Unit = warns += msg
    override def isTraceEnabled: Boolean = true
    override def trace(msg: String): Unit = traces += msg
    
    // Implement other required methods with no-op or exception
    override def isInfoEnabled: Boolean = false
    override def info(msg: String): Unit = ()
    override def isErrorEnabled: Boolean = false
    override def error(msg: String): Unit = ()
    // ... extensive interface, implementing minimal set for test
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

  class NoOpClient extends LLMClient {
    override def complete(c: Conversation, o: CompletionOptions): Result[Completion] =
      Right(Completion("id", 0L, "content", "model", AssistantMessage("content")))
    override def streamComplete(c: Conversation, o: CompletionOptions, onChunk: StreamedChunk => Unit): Result[Completion] = 
      Right(Completion("id", 0L, "content", "model", AssistantMessage("content")))
    override def getContextWindow(): Int = 100
    override def getReserveCompletion(): Int = 10
  }

  class FailingClient extends LLMClient {
    override def complete(c: Conversation, o: CompletionOptions): Result[Completion] = 
      Left(org.llm4s.error.NetworkError("boom", None, "endpoint"))
    override def streamComplete(c: Conversation, o: CompletionOptions, onChunk: StreamedChunk => Unit): Result[Completion] = ???
    override def getContextWindow(): Int = 100
    override def getReserveCompletion(): Int = 10
  }

  "LoggingMiddleware" should "log requests and successful responses" in {
    val logger = new FakeLogger()
    val middleware = new LoggingMiddleware(logger = logger)
    val client = middleware.wrap(new NoOpClient)

    client.complete(Conversation(Seq.empty))

    logger.debugs.size should be >= 2 // Request + Response
    logger.debugs.exists(_.contains("Request:")) shouldBe true
    logger.debugs.exists(_.contains("Success")) shouldBe true
  }

  it should "log failures as warnings" in {
    val logger = new FakeLogger()
    val middleware = new LoggingMiddleware(logger = logger)
    val client = middleware.wrap(new FailingClient)

    client.complete(Conversation(Seq.empty))

    logger.debugs.exists(_.contains("Request:")) shouldBe true
    logger.warns.size shouldBe 1
    logger.warns.head should include ("Failed")
    logger.warns.head should include ("boom")
  }
}
