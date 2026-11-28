package org.llm4s.mcp

import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.CopyOnWriteArrayList
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

/** `tools/list` (#3075): every page is read, a paging loop is bounded, and a tool needs no `description`. */
class MCPToolListingSpec extends AnyFlatSpec with Matchers {

  private def tool(name: String, extra: (String, ujson.Value)*): ujson.Obj =
    ujson.Obj.from(Seq("name" -> ujson.Str(name), "inputSchema" -> ujson.Obj("type" -> "object")) ++ extra)

  /** Answers `tools/list` with `pages(cursor)`, recording each cursor asked for. */
  private class Paging(pages: Option[String] => ujson.Value) extends MCPTransportImpl {
    override val name: String = "paging"
    val cursors               = new CopyOnWriteArrayList[Option[String]]()
    override def sendRequest(request: JsonRpcRequest): Result[JsonRpcResponse] = {
      val result = request.method match {
        case "initialize" => ujson.Obj("protocolVersion" -> "2025-06-18")
        case _ =>
          val cursor = request.params.flatMap(_.objOpt).flatMap(_.get("cursor")).flatMap(_.strOpt)
          cursors.add(cursor): Unit
          pages(cursor)
      }
      Right(JsonRpcResponse(id = request.id, result = Some(result)))
    }
    override def sendNotification(notification: JsonRpcNotification): Result[Unit] = Right(())
    override def close(): Unit                                                     = ()
  }

  private def toolsOver(transport: MCPTransportImpl) = {
    val client = new MCPClientImpl(MCPServerConfig.streamableHTTP("paging", "http://127.0.0.1:1/mcp", 5.seconds))
    client.transport = Some(transport)
    client.getTools()
  }

  "MCPClientImpl.getTools" should "follow nextCursor until a page has none" in {
    val transport = new Paging({
      case None       => ujson.Obj("tools" -> ujson.Arr(tool("a", "description" -> "A")), "nextCursor" -> "p2")
      case Some("p2") => ujson.Obj("tools" -> ujson.Arr(tool("b", "description" -> "B")), "nextCursor" -> "p3")
      case _          => ujson.Obj("tools" -> ujson.Arr(tool("c", "description" -> "C")))
    })
    toolsOver(transport).map(_.map(_.name)) shouldBe Right(Seq("a", "b", "c"))
    transport.cursors.asScala.toList shouldBe List(None, Some("p2"), Some("p3"))
  }

  it should "stop at a cursor it has seen, keeping the tools listed" in {
    val transport = new Paging({
      case None => ujson.Obj("tools" -> ujson.Arr(tool("a", "description" -> "A")), "nextCursor" -> "again")
      case _    => ujson.Obj("tools" -> ujson.Arr(tool("b", "description" -> "B")), "nextCursor" -> "again")
    })
    toolsOver(transport).map(_.map(_.name)) shouldBe Right(Seq("a", "b"))
    transport.cursors.size shouldBe 2
  }

  it should "stop after MaxToolPages pages from a server that never stops paging" in {
    val transport = new Paging(cursor => {
      val n = cursor.fold(0)(_.toInt)
      ujson.Obj("tools" -> ujson.Arr(tool(s"t$n", "description" -> "x")), "nextCursor" -> (n + 1).toString)
    })
    toolsOver(transport).map(_.size) shouldBe Right(MCPClientImpl.MaxToolPages)
    transport.cursors.size shouldBe MCPClientImpl.MaxToolPages
  }

  it should "keep a tool with no description, describing it by its title when it has one" in {
    val transport = new Paging(_ =>
      ujson.Obj(
        "tools" -> ujson.Arr(
          tool("bare"),
          tool("titled", "title"          -> "A titled tool"),
          tool("annotated", "annotations" -> ujson.Obj("title" -> "From annotations"))
        )
      )
    )
    toolsOver(transport).map(_.map(t => t.name -> t.description)) shouldBe Right(
      Seq("bare" -> "", "titled" -> "A titled tool", "annotated" -> "From annotations")
    )
  }
}
