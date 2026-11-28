package org.llm4s.mcp

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }
import scala.concurrent.duration.*

/**
 * The stdio transport's process handling and message routing (#3077): the configured timeout is the reply timeout, a
 * server that says nothing until `initialize` costs no start-up wait, a request the server sends is answered and never
 * taken for a reply, a reply written just before an exit is delivered, and closing stops every process the server
 * started. Platform-sensitive (processes, pipes, process trees), so it runs on Windows too (`PlatformTests`).
 */
class StdioProcessLifecycleSpec extends AnyFlatSpec with Matchers {

  private val toolsList  = JsonRpcRequest("2.0", "1", "tools/list", None)
  private val initialize = JsonRpcRequest("2.0", "0", "initialize", None)
  private val Ok         = """{"jsonrpc":"2.0","id":"$id","result":{"ok":true}}"""

  private def tempFile(suffix: String): Path = {
    val file = Files.createTempFile("llm4s-mcp-", suffix)
    file.toFile.deleteOnExit()
    file
  }

  /** The content of `file` once `ready` holds for it, waiting up to 10 seconds. */
  private def awaitFile(file: Path, what: String)(ready: String => Boolean): String = {
    val deadline = Deadline.now + 10.seconds
    def text     = new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
    while (!ready(text) && deadline.hasTimeLeft()) Thread.sleep(20)
    withClue(s"$what: ")(ready(text) shouldBe true)
    text
  }

  private def pidIn(file: Path): Long = awaitFile(file, "a process id")(_.trim.nonEmpty).trim.toLong

  /** Whether the process `pid` has ended, waiting up to 10 seconds for it to. */
  private def ended(pid: Long): Boolean = {
    val deadline = Deadline.now + 10.seconds
    def alive    = ProcessHandle.of(pid).map(_.isAlive).orElse(false)
    while (alive && deadline.hasTimeLeft()) Thread.sleep(20)
    !alive
  }

  "StdioTransportImpl" should "wait for a reply as long as the configured timeout, not a fixed 30 seconds" in {
    // Answers initialize only: tools/list is never answered.
    val transport = new StdioTransportImpl(
      StdioTestServers.command(StdioTestServers.reply("initialize", Ok)),
      "mute",
      1.second
    )
    try {
      transport.sendRequest(initialize).isRight shouldBe true
      val started = System.nanoTime()
      val result  = transport.sendRequest(toolsList)
      val elapsed = (System.nanoTime() - started).nanos
      result.left.map(_.message).left.getOrElse(fail("expected a timeout")) should include("after 1 second")
      elapsed should be < 10.seconds
    } finally transport.close()
  }

  it should "send the first request at once to a server that says nothing until initialize" in {
    // No --ready, no --hello: nothing on stdout or stderr until a request arrives. The start-up wait this replaced
    // sat out its full 10 seconds for such a server on every start.
    val transport = new StdioTransportImpl(StdioTestServers.echo(Ok), "quiet", 30.seconds)
    try {
      val started = System.nanoTime()
      transport.sendRequest(initialize).isRight shouldBe true
      (System.nanoTime() - started).nanos should be < 8.seconds
    } finally transport.close()
  }

  it should "answer a server's ping and refuse its other requests, never taking one for the reply with its id" in {
    val record = tempFile(".record")
    // Before its reply the server sends two requests of its own, with the same id as the client's request.
    val reply = Seq(
      """{"jsonrpc":"2.0","id":"$id","method":"ping"}""",
      """{"jsonrpc":"2.0","id":7,"method":"roots/list"}""",
      Ok
    ).mkString("\n")
    val transport = new StdioTransportImpl(
      StdioTestServers.command("--record=" + record, StdioTestServers.reply("tools/list", reply)),
      "asking",
      30.seconds
    )
    try {
      val response = transport.sendRequest(toolsList)
      response.map(_.result.map(_("ok").bool)) shouldBe Right(Some(true))
      val seen = awaitFile(record, "the server read both answers") { text =>
        text.contains(""""result":{}""") && text.contains("-32601")
      }
      val answers =
        seen.linesIterator.filter(_.startsWith("{")).map(ujson.read(_)).filterNot(_.obj.contains("method")).toList
      answers.map(a => a("id")) should contain theSameElementsAs Seq(ujson.Str("1"), ujson.Num(7))
      answers.find(_("id") == ujson.Str("1")).map(_("result")) shouldBe Some(ujson.Obj())
      answers.find(_("id") == ujson.Num(7)).map(_("error")("code").num.toInt) shouldBe Some(
        JsonRpcReplies.MethodNotFound
      )
    } finally transport.close()
  }

  it should "deliver a reply the server wrote just before it exited" in {
    val transport = new StdioTransportImpl(StdioTestServers.echo(Ok, "--exit-after-reply"), "brief", 30.seconds)
    try transport.sendRequest(toolsList).map(_.id) shouldBe Right("1")
    finally transport.close()
  }

  it should "stop the processes the server started as well as the server when closed" in {
    val serverPid = tempFile(".pid")
    val childPid  = tempFile(".child.pid")
    val transport = new StdioTransportImpl(
      StdioTestServers.echo(Ok, "--pidfile=" + serverPid, "--spawn-child=" + childPid),
      "launcher",
      30.seconds
    )
    val server = pidIn {
      transport.sendRequest(toolsList).isRight shouldBe true
      serverPid
    }
    val child = pidIn(childPid)
    ProcessHandle.of(child).map(_.isAlive).orElse(false) shouldBe true

    transport.close()

    withClue("the server: ")(ended(server) shouldBe true)
    withClue("the process the server started: ")(ended(child) shouldBe true)
  }

  it should "start one process between calls that find the server gone at the same time" in {
    val record = tempFile(".record")
    // Every process the transport starts appends the requests it reads to the same file; one process reads both.
    val transport = new StdioTransportImpl(
      StdioTestServers.echo(Ok, "--record=" + record, "--delay=50"),
      "shared",
      30.seconds
    )
    try {
      val gate    = new java.util.concurrent.CountDownLatch(1)
      val results = new java.util.concurrent.ConcurrentLinkedQueue[Boolean]()
      val threads = (1 to 4).map { i =>
        Thread.ofVirtual().start { () =>
          gate.await()
          results.add(transport.sendRequest(toolsList.copy(id = s"c$i")).isRight): Unit
        }
      }
      gate.countDown()
      threads.foreach(_.join(30_000))
      results.toArray.toSeq shouldBe Seq(true, true, true, true)
      new String(Files.readAllBytes(record), StandardCharsets.UTF_8).linesIterator.count(_ == "started") shouldBe 1
    } finally transport.close()
  }

  "MCPClientImpl" should "use MCPServerConfig.timeout as the stdio reply timeout" in {
    val command = StdioTestServers.command(
      StdioTestServers.reply("initialize", """{"jsonrpc":"2.0","id":"$id","result":{"protocolVersion":"2025-06-18"}}""")
    )
    val client = new MCPClientImpl(MCPServerConfig.stdio("mute-tools", command, timeout = 1.second))
    try
      client.getTools().left.map(_.message).left.getOrElse(fail("expected a timeout")) should include("after 1 second")
    finally client.close()
  }
}
