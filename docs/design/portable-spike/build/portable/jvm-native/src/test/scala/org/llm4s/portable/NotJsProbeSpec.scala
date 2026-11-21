package org.llm4s.portable

import org.llm4s.error.{ CancelledError, ErrorRecovery, SimpleError }
import org.llm4s.toolapi.{ ToolCallRequest, ToolExecutionConfig, ToolRegistry }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt

/**
 * Links on the JVM and Scala Native, not on Scala.js 1.22, whose javalib has no `java.nio.channels.ClosedByInterruptException`
 * (`CancelledError`) or `Thread.sleep` (`ErrorRecovery`), and which cannot block on `Await` (`ToolRegistry`).
 */
class NotJsProbeSpec extends AnyFlatSpec with Matchers {
  import Fixtures._

  "CancelledError.isCancellation" should "recognise an interruption" in {
    CancelledError.isCancellation(new InterruptedException()) shouldBe true
  }

  "ErrorRecovery.recoverWithBackoff" should "retry with a sleeping thread" in {
    ErrorRecovery.recoverWithBackoff(() => Right(1), maxAttempts = 3, baseDelay = 1.milli) shouldBe Right(1)
    ErrorRecovery.recoverWithBackoff(() => Left(SimpleError("no")), 1, 1.milli).isLeft shouldBe true
  }

  "ToolRegistry.execute" should "run a tool synchronously (it checks for interruption and blocks on Await with a timeout)" in {
    given ExecutionContext = ExecutionContext.parasitic
    val registry           = new ToolRegistry(Seq(weatherTool))
    val request            = ToolCallRequest("get_weather", weatherCall.arguments)
    registry.execute(request) shouldBe Right(ujson.Str("Sunny in Paris"))
    registry.execute(request, ToolExecutionConfig.default) shouldBe Right(ujson.Str("Sunny in Paris"))
  }
}
