package org.llm4s.mcp

import com.sun.net.httpserver.HttpExchange
import org.llm4s.testkit.LocalProviderTestServer
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger }
import scala.concurrent.duration.*

/**
 * A lost MCP session, or a restarted server, is recovered (#3072): the client runs the handshake again and retries
 * the call once, but only a call the server provably did not run (refused with 404 for its session, or never sent
 * to a stdio process that had died); and a tool an agent took from a registry follows the registry's reconnect.
 */
class MCPSessionRecoverySpec extends AnyFlatSpec with Matchers {

  /** A Streamable HTTP server that issues a new session on each initialize and answers 404 for any other. */
  final private class SessionServer {
    val sessions     = java.util.concurrent.ConcurrentHashMap.newKeySet[String]()
    val initializes  = new AtomicInteger(0)
    val calls        = new AtomicInteger(0)
    val alwaysExpire = new AtomicBoolean(false)

    def handle(exchange: HttpExchange): Unit = {
      val body    = ujson.read(new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8))
      val session = Option(exchange.getRequestHeaders.getFirst("mcp-session-id"))
      def reply(result: ujson.Value): Unit =
        LocalProviderTestServer.sendJsonResponse(
          exchange,
          200,
          ujson.write(ujson.Obj("jsonrpc" -> "2.0", "id" -> body("id"), "result" -> result))
        )
      body("method").str match {
        case "initialize" =>
          val id = s"s${initializes.incrementAndGet()}"
          if (!alwaysExpire.get) sessions.add(id): Unit
          exchange.getResponseHeaders.set("mcp-session-id", id)
          reply(
            ujson.Obj(
              "protocolVersion" -> "2025-06-18",
              "capabilities"    -> ujson.Obj("tools" -> ujson.Obj()),
              "serverInfo"      -> ujson.Obj("name" -> "sessions", "version" -> "1")
            )
          )
        case _ if !session.exists(sessions.contains) =>
          exchange.sendResponseHeaders(404, -1)
          exchange.close()
        case _ if !body.obj.contains("id") =>
          exchange.sendResponseHeaders(202, -1)
          exchange.close()
        case "tools/list" =>
          reply(
            ujson.Obj(
              "tools" -> ujson.Arr(
                ujson.Obj(
                  "name"        -> "echo",
                  "description" -> "echoes",
                  "inputSchema" -> ujson.Obj("type" -> "object", "properties" -> ujson.Obj())
                )
              )
            )
          )
        case "tools/call" =>
          calls.incrementAndGet(): Unit
          reply(ujson.Obj("content" -> ujson.Arr(ujson.Obj("type" -> "text", "text" -> "pong"))))
        case _ => reply(ujson.Obj())
      }
    }
  }

  private def withSessionServer(body: (SessionServer, String) => Unit): Unit = {
    val server = new SessionServer
    LocalProviderTestServer.withServer("/")(server.handle)(url => body(server, url))
  }

  "MCPClientImpl" should "start a new session and retry a call its expired session refused, once" in
    withSessionServer { (server, url) =>
      val client = new MCPClientImpl(MCPServerConfig.streamableHTTP("sessions", url, 10.seconds))
      try {
        val echo = client.getTools().toOption.flatMap(_.find(_.name == "echo")).get
        echo.execute(ujson.Obj()) shouldBe Right(ujson.Str("pong"))
        val before = server.initializes.get

        server.sessions.clear() // the server expires every session, as a restart does
        echo.execute(ujson.Obj()) shouldBe Right(ujson.Str("pong"))

        server.initializes.get shouldBe before + 1
        server.calls.get shouldBe 2 // the refused call never ran; its retry is the only second run
        client.getTools().map(_.map(_.name)) shouldBe Right(Seq("echo"))
      } finally client.close()
    }

  it should "give up after one new session when the server refuses that one too" in
    withSessionServer { (server, url) =>
      val client = new MCPClientImpl(MCPServerConfig.streamableHTTP("sessions", url, 10.seconds))
      try {
        val echo   = client.getTools().toOption.flatMap(_.find(_.name == "echo")).get
        val before = server.initializes.get
        server.alwaysExpire.set(true)
        server.sessions.clear()

        echo.execute(ujson.Obj()).isLeft shouldBe true
        server.initializes.get shouldBe before + 1
        server.calls.get shouldBe 0
      } finally client.close()
    }

  it should "run the handshake with a stdio server that was restarted, before sending it the call" in {
    val pidFile = Files.createTempFile("llm4s-mcp-pid", ".txt")
    val command = StdioTestServers.command(
      "--ready",
      "--require-init",
      s"--pidfile=$pidFile",
      StdioTestServers.reply(
        "initialize",
        """{"jsonrpc":"2.0","id":"$id","result":{"protocolVersion":"2024-11-05","capabilities":{"tools":{}},"serverInfo":{"name":"s","version":"1"}}}"""
      ),
      StdioTestServers.reply(
        "tools/list",
        """{"jsonrpc":"2.0","id":"$id","result":{"tools":[{"name":"echo","description":"echoes","inputSchema":{"type":"object","properties":{}}}]}}"""
      ),
      StdioTestServers.reply(
        "tools/call",
        """{"jsonrpc":"2.0","id":"$id","result":{"content":[{"type":"text","text":"pong"}]}}"""
      )
    )
    val client = new MCPClientImpl(MCPServerConfig.stdio("restarting", command, 10.seconds))
    try {
      val echo = client.getTools().toOption.flatMap(_.find(_.name == "echo")).get
      echo.execute(ujson.Obj()) shouldBe Right(ujson.Str("pong"))

      val first = Files.readString(pidFile).trim.toLong
      ProcessHandle.of(first).ifPresent { process =>
        process.destroyForcibly(): Unit
        process.onExit().get(10, TimeUnit.SECONDS): Unit
      }

      echo.execute(ujson.Obj()) shouldBe Right(ujson.Str("pong"))
      Files.readString(pidFile).trim.toLong should not be first
    } finally {
      client.close()
      Files.deleteIfExists(pidFile): Unit
    }
  }

  "A tool taken from MCPToolRegistry" should "follow the registry's reconnect after its client was closed" in
    withSessionServer { (server, url) =>
      val registry = new MCPToolRegistry(
        Seq(MCPServerConfig.streamableHTTP("sessions", url, 10.seconds)),
        initializeOnStartup = false
      )
      try {
        // what AgentBuilder.withTools(registry) does: take the tools once, when the agent is built
        val held = registry.tools.find(_.name == "echo").get
        held.execute(ujson.Obj()) shouldBe Right(ujson.Str("pong"))

        registry.closeMCPClients() // as the registry does to a client whose refresh failed
        held.execute(ujson.Obj()) shouldBe Right(ujson.Str("pong"))
        server.calls.get shouldBe 2
      } finally registry.close()
    }
}
