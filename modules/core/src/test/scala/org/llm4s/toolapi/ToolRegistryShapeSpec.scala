package org.llm4s.toolapi

import org.llm4s.error.ValidationError
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContext }

/**
 * The shape of the registry (#2816): duplicate names are rejected, the tool definitions are rendered from a
 * plain list with the strictness stated, and [[ToolExecutor]] runs every call through the registry's own
 * `execute`, so a registry that finds its tools elsewhere is honoured on every path.
 */
class ToolRegistryShapeSpec extends AnyFlatSpec with Matchers {

  import ToolRegistryShapeSpec.*

  "ToolRegistry.of" should "reject two tools with the same name, naming it" in {
    val error = ToolRegistry.of(Seq(tool("ping", "first"), tool("pong", "x"), tool("ping", "second"))).left.toOption.get

    error shouldBe a[ValidationError]
    error.message should include("ping")
    (error.message should not).include("pong")
  }

  it should "name every duplicated name once" in {
    val tools = Seq(tool("a", "1"), tool("b", "1"), tool("a", "2"), tool("b", "2"), tool("a", "3"))
    ToolRegistry.of(tools).left.toOption.get.message should endWith("more than once: a, b")
  }

  it should "keep the tools in the order given and find each by name" in {
    val ping     = tool("ping", "pong")
    val echo     = tool("echo", "echo")
    val registry = ToolRegistry.of(Seq(ping, echo)).toOption.get

    registry.tools shouldBe Seq(ping, echo)
    registry.tool("echo") shouldBe Some(echo)
    registry.tool("missing") shouldBe None
    registry.execute(request) shouldBe Right(ujson.Str("pong"))
  }

  it should "accept no tools, as ToolRegistry.empty" in {
    ToolRegistry.of(Seq.empty).map(_.tools) shouldBe Right(Seq.empty)
    ToolRegistry.empty.tools shouldBe empty
    ToolRegistry.empty.execute(request).left.toOption.get shouldBe a[ToolCallError.UnknownFunction]
  }

  "ToolRegistry" should "have no public constructor" in {
    assertTypeError("new ToolRegistry(Seq.empty)")
  }

  "ToolFunction.toolDefinitions" should "render each tool once, in order, with the strictness the caller states" in {
    val tools = Seq(tool("ping", "pong"), tool("echo", "echo"))

    val strict = ToolFunction.toolDefinitions(tools, strict = true)
    strict.arr.map(_("function")("name").str).toSeq shouldBe Seq("ping", "echo")
    strict.arr.map(_("function")("strict").bool).toSeq shouldBe Seq(true, true)
    strict.arr.head shouldBe tools.head.toolDefinition(strict = true)

    ToolFunction.toolDefinitions(tools, strict = false).arr.map(_("function")("strict").bool).toSeq shouldBe
      Seq(false, false)
    ToolFunction.toolDefinitions(Seq.empty, strict = true).arr shouldBe empty
  }

  it should "leave the strictness for the caller to state" in {
    assertTypeError("""ToolFunction.toolDefinitions(Seq.empty)""")
    assertTypeError("""tool("ping", "pong").toolDefinition()""")
  }

  "ToolExecutor.execute" should "run every attempt through the registry's own execute" in {
    val remote = new Remote
    ToolExecutor.execute(remote, request, retried) shouldBe Right(ujson.Str("remote-2"))
    remote.calls.get() shouldBe 2
  }

  it should "be registry.execute for the default config" in {
    val remote = new Remote
    ToolExecutor.execute(remote, request, ToolExecutionConfig.default) shouldBe
      Left(ToolCallError.Timeout("ping", 1.milli))
    remote.calls.get() shouldBe 1
  }

  "ToolExecutor.executeAsync and executeAll" should "run through the registry's own execute" in {
    implicit val ec: ExecutionContext = ExecutionContext.global
    val remote                        = new Remote
    Await.result(ToolExecutor.executeAsync(remote, request, retried), 10.seconds) shouldBe Right(ujson.Str("remote-2"))
    Await.result(ToolExecutor.executeAll(remote, Seq(request, request)), 10.seconds) shouldBe
      Seq(Right(ujson.Str("remote-3")), Right(ujson.Str("remote-4")))
  }
}

object ToolRegistryShapeSpec {

  def tool(name: String, reply: String): ToolFunction[Map[String, Any], String] =
    ToolBuilder[Map[String, Any], String](name, s"Replies $reply", Schema.`object`[Map[String, Any]]("No arguments"))
      .withHandler(_ => Right(reply))
      .buildSafe()
      .toOption
      .get

  val request = ToolCallRequest("ping", ujson.Obj())

  /** A registry whose tools are not in `tools`: every call must go through its `execute`. */
  final class Remote extends ToolRegistry {
    val calls                          = new AtomicInteger(0)
    def tools: Seq[ToolFunction[_, _]] = Seq.empty
    def execute(request: ToolCallRequest): Either[ToolCallError, ujson.Value] = {
      val n = calls.incrementAndGet()
      if (n == 1) Left(ToolCallError.Timeout(request.functionName, 1.milli)) else Right(ujson.Str(s"remote-$n"))
    }
  }

  val retried = ToolExecutionConfig.default
    .withTimeout(5.seconds)
    .withRetryPolicy(ToolRetryPolicy(maxAttempts = 2, baseDelay = Duration.Zero).toOption.get)

}
