package org.llm4s.mcp

import org.llm4s.error.{ CancelledError, SimpleError }
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

/**
 * `MCPClientImpl.initialize` and `getTools` when the transport fails or is cancelled at each step: a cancellation
 * is returned as one, the other failures keep their messages, and a notification the server does not accept does not
 * stop the handshake. The transport is a script; no network is involved.
 */
class MCPClientHandshakePathsSpec extends AnyFlatSpec with Matchers {

  private class Scripted(
    onRequest: JsonRpcRequest => Result[JsonRpcResponse],
    onNotification: => Result[Unit] = Right(())
  ) extends MCPTransportImpl {
    override val name: String                                                  = "scripted"
    val notifications                                                          = new AtomicInteger(0)
    override def sendRequest(request: JsonRpcRequest): Result[JsonRpcResponse] = onRequest(request)
    override def close(): Unit                                                 = ()
    override def sendNotification(notification: JsonRpcNotification): Result[Unit] = {
      notifications.incrementAndGet(): Unit
      onNotification
    }
  }

  private def answer(request: JsonRpcRequest, result: ujson.Value): Result[JsonRpcResponse] =
    Right(JsonRpcResponse(id = request.id, result = Some(result)))

  private val handshake = ujson.Obj("protocolVersion" -> "2025-06-18")

  private def clientOver(transport: MCPTransportImpl): MCPClientImpl = {
    val client = new MCPClientImpl(MCPServerConfig.streamableHTTP("scripted", "http://127.0.0.1:1/mcp", 5.seconds))
    client.transport = Some(transport)
    client
  }

  "initialize" should "return CancelledError when the handshake request is cancelled" in {
    val client = clientOver(new Scripted(_ => Left(CancelledError("initialize"))))

    client.initialize() should matchPattern { case Left(_: CancelledError) => }
  }

  it should "return CancelledError when the initialized notification is cancelled" in {
    val transport = new Scripted(answer(_, handshake), Left(CancelledError("notifications/initialized")))
    val client    = clientOver(transport)

    client.initialize() should matchPattern { case Left(_: CancelledError) => }
    transport.notifications.get shouldBe 1
  }

  it should "carry on when the server does not accept the initialized notification" in {
    val transport = new Scripted(answer(_, handshake), Left(SimpleError("notification refused")))
    val client    = clientOver(transport)

    client.initialize() shouldBe Right(())
    client.initialize() shouldBe Right(()) // already initialised: no second handshake
    transport.notifications.get shouldBe 1
  }

  it should "report a failed handshake request with its cause" in {
    val client = clientOver(new Scripted(_ => Left(SimpleError("connection refused"))))

    client.initialize().left.map(_.message) shouldBe Left("Initialize request failed: connection refused")
  }

  it should "reject a server that speaks a protocol version it does not know" in {
    val client = clientOver(new Scripted(answer(_, ujson.Obj("protocolVersion" -> "1999-01-01"))))

    client.initialize().left.map(_.message) shouldBe Left("Unsupported protocol version: 1999-01-01")
  }

  "getTools" should "return CancelledError when listing the tools is cancelled" in {
    val client = clientOver(
      new Scripted(request =>
        if (request.method == "initialize") answer(request, handshake) else Left(CancelledError("tools/list"))
      )
    )

    client.getTools() should matchPattern { case Left(_: CancelledError) => }
  }

  it should "return CancelledError when the handshake it triggers is cancelled" in {
    val client = clientOver(new Scripted(_ => Left(CancelledError("initialize"))))

    client.getTools() should matchPattern { case Left(_: CancelledError) => }
  }

  it should "swallow any other failure into an empty list, as documented" in {
    val client = clientOver(
      new Scripted(request =>
        if (request.method == "initialize") answer(request, handshake) else Left(SimpleError("server error"))
      )
    )

    client.getTools().isLeft shouldBe true
  }
}
