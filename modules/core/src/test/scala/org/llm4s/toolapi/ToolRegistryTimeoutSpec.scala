package org.llm4s.toolapi

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.{ Await, ExecutionContext }
import scala.concurrent.duration._

class ToolRegistryTimeoutSpec extends AnyFlatSpec with Matchers {

  implicit val ec: ExecutionContext = ExecutionContext.global

  // Minimal fake tool implementation for tests
  class TestTool(nameStr: String, delay: Long = 0)
      extends ToolFunction[Any, String](
        name = nameStr,
        description = "test tool",
        schema = null,
        handler = _ => {
          if (delay > 0) Thread.sleep(delay)
          Right("ok")
        }
      )

  behavior.of("ToolRegistry executionTimeout")

  it should "allow tool execution when it completes before timeout" in {

    val fastTool = new TestTool("fastTool", 10)

    val registry = new ToolRegistry(Seq(fastTool), Some(1.second))

    val result = Await.result(
      registry.executeAsync(ToolCallRequest("fastTool", ujson.Obj())),
      2.seconds
    )

    result.isRight shouldBe true
  }

  it should "return timeout error when tool execution exceeds timeout" in {

    val slowTool = new TestTool("slowTool", 2000)

    val registry = new ToolRegistry(Seq(slowTool), Some(100.millis))

    val result = Await.result(
      registry.executeAsync(ToolCallRequest("slowTool", ujson.Obj())),
      3.seconds
    )

    result.isLeft shouldBe true
  }

  it should "preserve original behavior when executionTimeout is None" in {

    val tool = new TestTool("normalTool", 0)

    val registry = new ToolRegistry(Seq(tool), None)

    val result = Await.result(
      registry.executeAsync(ToolCallRequest("normalTool", ujson.Obj())),
      2.seconds
    )

    result.isRight shouldBe true
  }
}
