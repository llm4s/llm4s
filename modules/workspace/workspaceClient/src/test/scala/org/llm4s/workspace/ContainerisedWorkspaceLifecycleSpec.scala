package org.llm4s.workspace

import org.java_websocket.WebSocket
import org.java_websocket.drafts.Draft
import org.java_websocket.exceptions.InvalidDataException
import org.java_websocket.framing.CloseFrame
import org.java_websocket.handshake.{ ClientHandshake, ServerHandshakeBuilder }
import org.java_websocket.server.WebSocketServer
import org.llm4s.shared._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default._

import java.net.InetSocketAddress
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger, AtomicReference }
import java.util.concurrent.{ ConcurrentLinkedQueue, CountDownLatch, TimeUnit }
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.util.{ Success, Try }

/**
 * How `ContainerisedWorkspace` starts, loses and removes its container (#3046, #3053), without Docker: a fake `docker`
 * CLI that records what it is asked, and a fake runner that checks the handshake's token as the real one does.
 */
class ContainerisedWorkspaceLifecycleSpec extends AnyFlatSpec with Matchers {

  /** Records every docker call; `run` answers `runResult`, `rm -f` answers `rmResult`, and `stop` succeeds. */
  final private class FakeDocker(runResult: Try[(Int, String, String)], rmResult: Try[(Int, String, String)])
      extends ContainerisedWorkspace.DockerCli {
    val calls = new ConcurrentLinkedQueue[(Seq[String], Map[String, String])]()
    def run(args: Seq[String], environment: Map[String, String]): Try[(Int, String, String)] = {
      calls.add(args -> environment)
      args.drop(1).headOption match {
        case Some("run") => runResult
        case Some("rm")  => rmResult
        case _           => Success((0, "", ""))
      }
    }
    def commands: Seq[Seq[String]] = calls.asScala.toSeq.map(_._1)
    def token: Option[String] = calls.asScala
      .collectFirst { case (a, env) if a(1) == "run" => env }
      .flatMap(_.get(RunnerHandshake.TokenEnvVar))
  }

  /**
   * A runner on `port` that opens a handshake only with `token()` and no Origin, answers `GetWorkspaceInfo` and,
   * unless `silent`, heartbeats.
   */
  final private class FakeRunner(port: Int, token: () => Option[String])
      extends WebSocketServer(new InetSocketAddress("127.0.0.1", port)) {
    val opened  = new AtomicInteger(0)
    val refused = new AtomicInteger(0)
    val origins = new AtomicInteger(0)
    val silent  = new AtomicBoolean(false)
    val started = new CountDownLatch(1)
    val current = new AtomicReference[WebSocket]()
    val writes  = new AtomicInteger(0)
    setReuseAddr(true)

    override def onWebsocketHandshakeReceivedAsServer(
      conn: WebSocket,
      draft: Draft,
      request: ClientHandshake
    ): ServerHandshakeBuilder = {
      if (request.hasFieldValue("Origin")) origins.incrementAndGet(): Unit
      val headers = Map(RunnerHandshake.AuthorizationHeader -> Seq(request.getFieldValue("Authorization")))
        .filter(_._2.exists(_.nonEmpty)) ++
        (if (request.hasFieldValue("Origin")) Map("Origin" -> Seq(request.getFieldValue("Origin"))) else Map.empty)
      val ok = token().exists(t => RunnerHandshake.check(t, headers).isRight)
      if (!ok) {
        refused.incrementAndGet()
        throw new InvalidDataException(CloseFrame.POLICY_VALIDATION, "refused")
      }
      super.onWebsocketHandshakeReceivedAsServer(conn, draft, request)
    }

    def onOpen(conn: WebSocket, handshake: ClientHandshake): Unit = {
      opened.incrementAndGet()
      current.set(conn)
    }
    def onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean): Unit = ()
    def onError(conn: WebSocket, ex: Exception): Unit                              = ()
    def onStart(): Unit                                                            = started.countDown()

    def onMessage(conn: WebSocket, message: String): Unit =
      read[WebSocketMessage](message) match {
        case HeartbeatMessage(_) if !silent.get() =>
          conn.send(write[WebSocketMessage](HeartbeatResponseMessage(System.currentTimeMillis())))
        case CommandMessage(_: WriteFileCommand) =>
          // the connection drops with the write in flight: whether it committed is unknown to the client
          writes.incrementAndGet()
          conn.close(CloseFrame.GOING_AWAY, "dropped")
        case CommandMessage(cmd: GetWorkspaceInfoCommand) =>
          val info = GetWorkspaceInfoResponse(cmd.commandId, "/workspace", Nil, WorkspaceLimits(1L, 1, 1, 1L))
          conn.send(write[WebSocketMessage](ResponseMessage(info)))
        case _ => ()
      }

    def startAndWait(): Unit = { start(); started.await(10, TimeUnit.SECONDS): Unit }
  }

  private def freePort(): Int = {
    val s = new java.net.ServerSocket(0)
    val p = s.getLocalPort
    s.close()
    p
  }

  private class TestWorkspace(port: Int, fake: FakeDocker, dir: String)
      extends ContainerisedWorkspace(dir, "img", port) {
    override private[workspace] def docker: ContainerisedWorkspace.DockerCli = fake
    override private[workspace] def heartbeatInterval: FiniteDuration        = 100.millis
    override private[workspace] def heartbeatResponseTimeout: FiniteDuration = 1.second
    override private[workspace] def reconnectDelays: Seq[FiniteDuration]     = Seq(0.millis, 100.millis, 200.millis)
  }

  private def tempDir(): String = java.nio.file.Files.createTempDirectory("ws-lifecycle").toString

  private def eventually(what: String)(cond: => Boolean): Unit = {
    val deadline = System.nanoTime() + 10.seconds.toNanos
    while (!cond && System.nanoTime() < deadline) Thread.sleep(20)
    withClue(what)(cond shouldBe true)
  }

  "A failed docker run" should "remove the container it may have left, and start nothing" in {
    val fake = new FakeDocker(Success((125, "", "port is already allocated")), Success((0, "", "")))
    val ws   = new TestWorkspace(freePort(), fake, tempDir())
    ws.startContainer() shouldBe false
    fake.commands.map(_.take(3)) should contain(Seq("docker", "rm", "-f"))
    fake.commands.last shouldBe Seq("docker", "rm", "-f", ws.containerName)
    // nothing runs on: a later call is a typed refusal, not a RuntimeException
    val e = intercept[WorkspaceAgentException](ws.getWorkspaceInfo())
    e.code shouldBe "WORKSPACE_NOT_RUNNING"
  }

  "stopContainer" should "remove the container even when docker stop fails, and count one already gone as removed" in {
    val fake = new FakeDocker(Success((125, "", "")), Success((1, "", "Error: No such container: x")))
    val ws   = new TestWorkspace(freePort(), fake, tempDir())
    ws.stopContainer() shouldBe true
    fake.commands.map(_.take(3)) should contain(Seq("docker", "rm", "-f"))
    val failing = new FakeDocker(Success((0, "", "")), Success((1, "", "Error: permission denied")))
    new TestWorkspace(freePort(), failing, tempDir()).stopContainer() shouldBe false
  }

  "A started workspace" should "pass its token by environment, not on the command line, and send it on the handshake" in {
    val port   = freePort()
    val fake   = new FakeDocker(Success((0, "id", "")), Success((0, "", "")))
    val runner = new FakeRunner(port, () => fake.token)
    runner.startAndWait()
    val ws = new TestWorkspace(port, fake, tempDir())
    try {
      ws.startContainer() shouldBe true
      val token = fake.token.getOrElse(fail("no token in docker's environment"))
      token.length should be >= RunnerHandshake.MinTokenLength
      fake.commands.flatten.exists(_.contains(token)) shouldBe false
      runner.opened.get() shouldBe 1
      runner.origins.get() shouldBe 0
      ws.getWorkspaceInfo().root shouldBe "/workspace"
    } finally {
      ws.close()
      runner.stop(1000)
    }
  }

  it should "reconnect after the runner drops the connection (a long pause of this JVM)" in {
    val port   = freePort()
    val fake   = new FakeDocker(Success((0, "id", "")), Success((0, "", "")))
    val runner = new FakeRunner(port, () => fake.token)
    runner.startAndWait()
    val ws = new TestWorkspace(port, fake, tempDir())
    try {
      ws.startContainer() shouldBe true
      // what the runner's heartbeat timeout does
      runner.current.get().close(CloseFrame.NORMAL, "Heartbeat timeout")
      ws.getWorkspaceInfo().root shouldBe "/workspace"
      runner.opened.get() should be >= 2
      runner.refused.get() shouldBe 0
    } finally {
      ws.close()
      runner.stop(1000)
    }
  }

  it should "drop and reconnect a connection whose heartbeats the runner stops answering" in {
    val port   = freePort()
    val fake   = new FakeDocker(Success((0, "id", "")), Success((0, "", "")))
    val runner = new FakeRunner(port, () => fake.token)
    runner.startAndWait()
    val ws = new TestWorkspace(port, fake, tempDir())
    try {
      ws.startContainer() shouldBe true
      runner.silent.set(true)
      eventually("a second connection after the heartbeats went unanswered")(runner.opened.get() >= 2)
    } finally {
      ws.close()
      runner.stop(1000)
    }
  }

  it should "remove the container and fail typed when the runner is gone for good" in {
    val port   = freePort()
    val fake   = new FakeDocker(Success((0, "id", "")), Success((0, "", "")))
    val runner = new FakeRunner(port, () => fake.token)
    runner.startAndWait()
    val ws = new TestWorkspace(port, fake, tempDir())
    ws.startContainer() shouldBe true
    runner.stop(1000)
    intercept[WorkspaceAgentException](ws.getWorkspaceInfo()).code shouldBe "WORKSPACE_UNAVAILABLE"
    eventually("the container removed")(fake.commands.contains(Seq("docker", "rm", "-f", ws.containerName)))
    intercept[WorkspaceAgentException](ws.getWorkspaceInfo()).code shouldBe "WORKSPACE_UNAVAILABLE"
    // a stop by the caller afterwards is still a plain stop
    ws.stopContainer() shouldBe true
  }

  it should "give up on a runner that accepts connections but never answers a heartbeat" in {
    val port   = freePort()
    val fake   = new FakeDocker(Success((0, "id", "")), Success((0, "", "")))
    val runner = new FakeRunner(port, () => fake.token)
    runner.startAndWait()
    val ws = new TestWorkspace(port, fake, tempDir())
    try {
      ws.startContainer() shouldBe true
      runner.silent.set(true)
      eventually("the container removed")(fake.commands.contains(Seq("docker", "rm", "-f", ws.containerName)))
      runner.opened.get() should be <= 4 // the first connection and at most three reconnections
      intercept[WorkspaceAgentException](ws.getWorkspaceInfo()).code shouldBe "WORKSPACE_UNAVAILABLE"
    } finally runner.stop(1000)
  }

  it should "fail a file change in flight when the connection drops, never sending it twice, and carry on" in {
    val port   = freePort()
    val fake   = new FakeDocker(Success((0, "id", "")), Success((0, "", "")))
    val runner = new FakeRunner(port, () => fake.token)
    runner.startAndWait()
    val ws = new TestWorkspace(port, fake, tempDir())
    try {
      ws.startContainer() shouldBe true
      intercept[WorkspaceAgentException](ws.writeFile("f.txt", "x")).code shouldBe "CONNECTION_CLOSED"
      runner.writes.get() shouldBe 1
      ws.getWorkspaceInfo().root shouldBe "/workspace"
    } finally {
      ws.close()
      runner.stop(1000)
    }
  }

  "The fake runner" should "refuse a handshake without the token, as the real one does" in {
    val port   = freePort()
    val runner = new FakeRunner(port, () => Some(RunnerHandshake.newToken()))
    runner.startAndWait()
    val fake = new FakeDocker(Success((0, "id", "")), Success((0, "", "")))
    // the workspace's own token is not the runner's: no connection opens, and the container is removed
    val ws = new TestWorkspace(port, fake, tempDir()) {
      override private[workspace] def reconnectDelays = Seq(0.millis)
    }
    try {
      // startup retries ten times a second apart; enough to see the first refusals
      val result = new java.util.concurrent.atomic.AtomicReference[Option[Boolean]](None)
      val t      = new Thread(() => result.set(Some(ws.startContainer())))
      t.setDaemon(true)
      t.start()
      eventually("a refused handshake")(runner.refused.get() >= 1)
      runner.opened.get() shouldBe 0
      ws.stopContainer(): Unit
      t.join(15000)
      result.get() shouldBe Some(false)
    } finally runner.stop(1000)
  }
}
