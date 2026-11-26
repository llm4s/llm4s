package org.llm4s.runner

import org.llm4s.shared.RunnerHandshake
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI
import java.net.http.{ HttpClient, WebSocket, WebSocketHandshakeException }
import java.util.concurrent.{ CompletionStage, ExecutionException, LinkedBlockingQueue, TimeUnit }
import scala.concurrent.duration._
import scala.util.Try

/**
 * The runner's WebSocket handshake (#3046), on a real server: the runner's own `RunnerAccess.guard` in front of an
 * echo socket, so a connection without the token is refused before any socket opens.
 */
class RunnerAccessSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private val token = RunnerHandshake.newToken()

  private object Routes extends cask.MainRoutes {
    @cask.websocket("/ws")
    def ws(request: cask.Request): cask.WebsocketResult =
      RunnerAccess.guard(token, request)(cask.WsHandler { channel =>
        cask.WsActor { case cask.Ws.Text(m) => channel.send(cask.Ws.Text(s"echo:$m")) }
      })
    initialize()
  }

  private val port = {
    val s = new java.net.ServerSocket(0)
    val p = s.getLocalPort
    s.close()
    p
  }
  private val server =
    io.undertow.Undertow.builder.addHttpListener(port, "127.0.0.1").setHandler(Routes.defaultHandler).build

  override def beforeAll(): Unit = server.start()
  override def afterAll(): Unit  = server.stop()

  private val http = HttpClient.newHttpClient()

  private def connect(headers: (String, String)*): Either[Int, WebSocket] = {
    val received = new LinkedBlockingQueue[String]()
    val listener = new WebSocket.Listener {
      override def onText(ws: WebSocket, data: CharSequence, last: Boolean): CompletionStage[?] = {
        received.put(data.toString)
        ws.request(1)
        null
      }
    }
    val builder = headers.foldLeft(http.newWebSocketBuilder()) { case (b, (k, v)) => b.header(k, v) }
    Try(builder.buildAsync(URI.create(s"ws://127.0.0.1:$port/ws"), listener).get(10, TimeUnit.SECONDS)).toEither.left
      .map {
        case e: ExecutionException =>
          e.getCause match {
            case h: WebSocketHandshakeException => h.getResponse.statusCode()
            case other                          => fail(other)
          }
        case other => fail(other)
      }
      .map { ws =>
        ws.sendText("hi", true).get(10, TimeUnit.SECONDS)
        Option(received.poll(10, TimeUnit.SECONDS)) shouldBe Some("echo:hi")
        ws
      }
  }

  "The runner's WebSocket" should "refuse a connection without the token" in {
    connect() shouldBe Left(401)
  }

  it should "refuse a connection with a wrong token" in {
    connect("Authorization" -> s"Bearer ${RunnerHandshake.newToken()}") shouldBe Left(401)
  }

  it should "refuse a browser's connection, which carries an Origin header, even with the token" in {
    connect("Authorization" -> RunnerHandshake.authorization(token), "Origin" -> "http://localhost") shouldBe Left(403)
  }

  it should "open a connection with the token" in {
    val ws = connect("Authorization" -> RunnerHandshake.authorization(token))
    ws.isRight shouldBe true
    ws.foreach(_.abort())
  }

  "IdleExit" should "exit once no client has been connected for the timeout, counted from the start" in {
    val idle = new IdleExit(Some(30.seconds), startedAtMillis = 0L)
    idle.observe(0, 29999L) shouldBe false
    idle.observe(0, 30000L) shouldBe true
  }

  it should "count from the last client's disconnection, and never exit while one is connected" in {
    val idle = new IdleExit(Some(30.seconds), startedAtMillis = 0L)
    idle.observe(1, 10000L) shouldBe false
    idle.observe(1, 100000L) shouldBe false
    idle.observe(0, 110000L) shouldBe false
    idle.observe(0, 139999L) shouldBe false
    idle.observe(0, 140000L) shouldBe true
  }

  it should "never exit with no timeout" in {
    new IdleExit(None, 0L).observe(0, Long.MaxValue / 2) shouldBe false
  }

  "IdleExit.timeoutFrom" should "read whole seconds, 0 as never, and default when unset" in {
    IdleExit.timeoutFrom(None) shouldBe Right(Some(IdleExit.DefaultTimeout))
    IdleExit.timeoutFrom(Some("90")) shouldBe Right(Some(90.seconds))
    IdleExit.timeoutFrom(Some("0")) shouldBe Right(None)
    IdleExit.timeoutFrom(Some("-1")).isLeft shouldBe true
    IdleExit.timeoutFrom(Some("5m")).isLeft shouldBe true
  }
}
