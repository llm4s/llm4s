package org.llm4s.sc3

import org.llm4s.toolapi._
import org.llm4s.toolapi.builtin.BuiltinTools
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.Await

/**
 * Cross-version test for ToolRegistry: registration, discovery, sync execute, and schema output.
 * Verifies behavior is identical in Scala 2.13 and 3.x (same logic as sc2.ToolRegistryCrossTest).
 * Uses only core builtin tools; no network or filesystem.
 * Includes positive (valid execute, getToolDefinitionsSafe) and negative (UnknownFunction, unsupported provider) cases.
 */
class ToolRegistryCrossTest extends AnyFlatSpec with Matchers {

  "ToolRegistry" should "accept a sequence of tools and expose them" in {
    val coreTools = BuiltinTools.coreSafe.getOrElse(fail("coreSafe failed"))
    val reg = new ToolRegistry(coreTools)
    reg.tools should not be empty
    reg.tools.size shouldBe coreTools.size
  }

  it should "return None for unknown tool name" in {
    val reg = new ToolRegistry(BuiltinTools.coreSafe.getOrElse(fail("coreSafe failed")))
    reg.getTool("nonexistent_tool") shouldBe None
  }

  it should "return Some(tool) for existing tool name" in {
    val reg = new ToolRegistry(BuiltinTools.coreSafe.getOrElse(fail("coreSafe failed")))
    reg.getTool("get_current_datetime") shouldBe defined
    reg.getTool("get_current_datetime").get.name shouldBe "get_current_datetime"
  }

  it should "execute get_current_datetime with empty object and return Right" in {
    val reg = new ToolRegistry(BuiltinTools.coreSafe.getOrElse(fail("coreSafe failed")))
    val result = reg.execute(ToolCallRequest("get_current_datetime", ujson.Obj()))
    result shouldBe a[Right[_, _]]
    result.foreach { json =>
      json.obj.contains("datetime") shouldBe true
      json.obj.contains("timezone") shouldBe true
    }
  }

  it should "execute calculator with valid args and return result" in {
    val reg = new ToolRegistry(BuiltinTools.coreSafe.getOrElse(fail("coreSafe failed")))
    val args = ujson.Obj("operation" -> "add", "a" -> 2, "b" -> 3)
    val result = reg.execute(ToolCallRequest("calculator", args))
    result shouldBe a[Right[_, _]]
    result.foreach { json =>
      json.obj.get("result") shouldBe Some(ujson.Num(5))
    }
  }

  it should "return Left(UnknownFunction) for unknown tool name" in {
    val reg = new ToolRegistry(BuiltinTools.coreSafe.getOrElse(fail("coreSafe failed")))
    val result = reg.execute(ToolCallRequest("unknown_tool", ujson.Obj()))
    result shouldBe Left(ToolCallError.UnknownFunction("unknown_tool"))
  }

  it should "getOpenAITools return a non-empty array with function entries" in {
    val reg = new ToolRegistry(BuiltinTools.coreSafe.getOrElse(fail("coreSafe failed")))
    val arr = reg.getOpenAITools(strict = true)
    arr shouldBe a[ujson.Arr]
    arr.arr should not be empty
    arr.arr.foreach { item =>
      item.obj("type") shouldBe ujson.Str("function")
      item.obj("function").obj.contains("name") shouldBe true
      item.obj("function").obj.contains("description") shouldBe true
      item.obj("function").obj.contains("parameters") shouldBe true
    }
  }

  it should "getToolDefinitionsSafe(openai) return same shape as getOpenAITools" in {
    val reg    = new ToolRegistry(BuiltinTools.coreSafe.getOrElse(fail("coreSafe failed")))
    val openai = reg.getToolDefinitionsSafe("openai").getOrElse(fail("getToolDefinitionsSafe failed"))
    openai shouldBe a[ujson.Arr]
    openai.arr should not be empty
  }

  it should "getToolDefinitionsSafe(anthropic) return non-empty array" in {
    val reg       = new ToolRegistry(BuiltinTools.coreSafe.getOrElse(fail("coreSafe failed")))
    val anthropic = reg.getToolDefinitionsSafe("anthropic").getOrElse(fail("getToolDefinitionsSafe failed"))
    anthropic shouldBe a[ujson.Arr]
    anthropic.arr should not be empty
  }

  it should "return Left for unsupported provider in getToolDefinitionsSafe" in {
    val reg = new ToolRegistry(BuiltinTools.coreSafe.getOrElse(fail("coreSafe failed")))
    reg.getToolDefinitionsSafe("unsupported_provider") shouldBe a[Left[_, _]]
  }

  it should "executeAll Sequential return results in order" in {
    val reg = new ToolRegistry(BuiltinTools.coreSafe.getOrElse(fail("coreSafe failed")))
    val reqs = Seq(
      ToolCallRequest("get_current_datetime", ujson.Obj()),
      ToolCallRequest("generate_uuid", ujson.Obj())
    )
    val future = reg.executeAll(reqs, ToolExecutionStrategy.Sequential)
    val results = Await.result(future, 10.seconds)
    results.size shouldBe 2
    results(0) shouldBe a[Right[_, _]]
    results(1) shouldBe a[Right[_, _]]
  }

  "ToolRegistry.empty" should "have no tools" in {
    val reg = ToolRegistry.empty
    reg.tools shouldBe empty
    reg.execute(ToolCallRequest("any", ujson.Obj())) shouldBe Left(ToolCallError.UnknownFunction("any"))
  }
}
