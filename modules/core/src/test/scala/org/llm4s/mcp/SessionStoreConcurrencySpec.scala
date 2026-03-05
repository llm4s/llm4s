package org.llm4s.mcp

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterAll
import scala.concurrent.{ Future, Await, ExecutionContext }
import scala.concurrent.duration._
import java.util.concurrent.Executors
import org.llm4s.toolapi.{ ToolBuilder, Schema }

class SessionStoreConcurrencySpec extends AnyFunSpec with Matchers with BeforeAndAfterAll {

  var server: MCPServer                                     = _
  var port: Int                                             = _
  var executorService: java.util.concurrent.ExecutorService = _
  implicit var ec: ExecutionContext                         = _

  override def beforeAll(): Unit = {
    executorService = Executors.newFixedThreadPool(20)
    ec = ExecutionContext.fromExecutor(executorService)

    // Define a simple tool
    val pingTool = ToolBuilder[Map[String, Any], String]("ping", "Echo", Schema.`object`[Map[String, Any]]("p"))
      .withHandler(_ => Right("pong"))
      .buildSafe()

    pingTool.fold(
      e => fail(s"Tool creation failed: ${e.formatted}"),
      tool => {
        server = new MCPServer(MCPServerOptions(0, "/mcp", "TestServer", "1.0"), Seq(tool))
        server.start().fold(e => throw e, _ => ())
        port = server.boundPort
      }
    )
  }

  override def afterAll(): Unit = {
    if (server != null) server.stop()
    if (executorService != null) executorService.shutdown()
  }

  describe("SessionStore Concurrency") {
    it("should handle concurrent session creation without errors") {
      val concurrency = 50

      val futures = (1 to concurrency).map { i =>
        Future {
          val transport = StreamableHTTPTransport(s"http://127.0.0.1:$port/mcp", s"client-$i")
          val client    = new MCPClientImpl(MCPServerConfig(s"server-$i", transport, 10.seconds))

          try {
            // Force protocol version that creates sessions
            // Note: The client impl usually negotiates.
            // We rely on standard initialize which should pick a version.
            // If client defaults to old version, sessions might not be created.
            // But let's try standard flow.
            val res = client.initialize()
            res should be(Right(()))

            // Client keeps session internally if supported
            // Let's do a tool call
            val call = client.getTools()
            call.isRight should be(true)

          } finally
            client.close()
        }
      }

      val combined = Future.sequence(futures)
      Await.result(combined, 30.seconds)
    }
  }
}
