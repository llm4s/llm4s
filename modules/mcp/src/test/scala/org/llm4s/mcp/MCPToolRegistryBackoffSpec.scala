package org.llm4s.mcp

import org.llm4s.error.SimpleError
import org.llm4s.toolapi.{ ObjectSchema, ToolFunction, ToolHintSource }
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.{ CountDownLatch, TimeUnit }
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

/**
 * `MCPToolRegistry` and a server that fails or is shared (#3080): a failure is remembered for a growing interval, so a
 * dead server is not dialled on every lookup; one refresh per server runs at a time; startup contacts the servers in
 * parallel; `healthCheck` reaches the server; and the registry is a `ToolHintSource`. The clients are stubs and the
 * clock is the spec's.
 */
class MCPToolRegistryBackoffSpec extends AnyFlatSpec with Matchers {

  private def tool(name: String) =
    ToolFunction[ujson.Value](name, name, ObjectSchema[ujson.Value]("p", Seq.empty), _ => Right(ujson.Null))

  private class Stub(var listing: Result[Seq[ToolFunction[?]]], pingResult: => Result[Unit] = Right(()))
      extends MCPClient {
    val lists                                             = new AtomicInteger(0)
    override def initialize(): Result[Unit]               = Right(())
    override def getTools(): Result[Seq[ToolFunction[?]]] = { lists.incrementAndGet(); listing }
    override def ping(): Result[Unit]                     = pingResult
    override def close(): Unit                            = ()
  }

  private def config(name: String) = MCPServerConfig.streamableHTTP(name, "http://127.0.0.1:1/mcp", 5.seconds)

  // The clock and the creation count are constructor parameters, not fields: startup runs in the superclass's
  // constructor, before a subclass's own fields are set.
  private class Registry(
    servers: Seq[MCPServerConfig],
    make: MCPServerConfig => MCPClient,
    startup: Boolean = false,
    val time: java.util.concurrent.atomic.AtomicLong = new java.util.concurrent.atomic.AtomicLong(0),
    val created: AtomicInteger = new AtomicInteger(0)
  ) extends MCPToolRegistry(servers, initializeOnStartup = startup) {
    override private[mcp] def createMCPClient(server: MCPServerConfig): MCPClient = {
      created.incrementAndGet()
      make(server)
    }
    override private[mcp] def nowMillis(): Long = time.get
    def advance(by: FiniteDuration): Unit       = time.addAndGet(by.toMillis): Unit
  }

  "MCPToolRegistry" should "not dial a failed server again until its backoff has passed, which grows" in {
    val stub     = new Stub(Left(SimpleError("down")))
    val registry = new Registry(Seq(config("dead")), _ => stub)

    registry.getAllTools shouldBe empty
    registry.getAllTools shouldBe empty
    registry.execute(org.llm4s.toolapi.ToolCallRequest("x", ujson.Obj())).isLeft shouldBe true
    stub.lists.get shouldBe 1 // one dial for three lookups

    registry.advance(MCPToolRegistry.retryDelay(1))
    registry.getAllTools shouldBe empty
    stub.lists.get shouldBe 2
    registry.advance(MCPToolRegistry.retryDelay(1)) // less than the second failure's backoff
    registry.getAllTools shouldBe empty
    stub.lists.get shouldBe 2

    registry.advance(MCPToolRegistry.retryDelay(2))
    stub.listing = Right(Seq(tool("back")))
    registry.getAllTools.map(_.name) shouldBe Seq("back")
  }

  it should "dial at once after clearCache" in {
    val stub     = new Stub(Left(SimpleError("down")))
    val registry = new Registry(Seq(config("dead")), _ => stub)
    registry.getAllTools shouldBe empty
    stub.listing = Right(Seq(tool("t")))
    registry.clearCache()
    registry.getAllTools.map(_.name) shouldBe Seq("t")
  }

  "MCPToolRegistry.retryDelay" should "start at a second and double up to a minute" in {
    (1 to 8).map(MCPToolRegistry.retryDelay) shouldBe
      Seq(1.second, 2.seconds, 4.seconds, 8.seconds, 16.seconds, 32.seconds, 60.seconds, 60.seconds)
  }

  "MCPToolRegistry" should "run one refresh per server when several calls find it uncached at once" in {
    val inList = new CountDownLatch(1)
    val go     = new CountDownLatch(1)
    val stub = new Stub(Right(Seq(tool("t")))) {
      override def getTools(): Result[Seq[ToolFunction[?]]] = {
        inList.countDown()
        go.await(10, TimeUnit.SECONDS)
        super.getTools()
      }
    }
    val registry = new Registry(Seq(config("shared")), _ => stub)
    val callers  = (1 to 4).map(_ => Thread.ofVirtual().start(() => registry.getAllTools: Unit))
    inList.await(10, TimeUnit.SECONDS) shouldBe true
    go.countDown()
    callers.foreach(_.join(10_000))
    stub.lists.get shouldBe 1
    registry.created.get shouldBe 1
  }

  it should "contact its servers in parallel at startup" in {
    // Each server's listing waits until both have started: serial startup would never finish it.
    val both = new CountDownLatch(2)
    val registry = new Registry(
      Seq(config("a"), config("b")),
      server =>
        new Stub(Right(Seq(tool(server.name)))) {
          override def getTools(): Result[Seq[ToolFunction[?]]] = {
            both.countDown()
            if (!both.await(10, TimeUnit.SECONDS)) Left(SimpleError("started alone")) else super.getTools()
          }
        },
      startup = true
    )
    registry.getAllTools.map(_.name) shouldBe Seq("a", "b")
  }

  "healthCheck" should "report a server that has died since its handshake as unhealthy" in {
    @volatile var alive = true
    val registry =
      new Registry(
        Seq(config("flaky")),
        _ => new Stub(Right(Seq.empty), if (alive) Right(()) else Left(SimpleError("gone")))
      )
    registry.healthCheck() shouldBe Map("flaky" -> true)
    alive = false
    registry.healthCheck() shouldBe Map("flaky" -> false)
  }

  "MCPToolRegistry" should "be a ToolHintSource, for AgentBuilder.withTools" in {
    (new Registry(Seq.empty, _ => fail("no server")): Any) shouldBe a[ToolHintSource]
  }
}
