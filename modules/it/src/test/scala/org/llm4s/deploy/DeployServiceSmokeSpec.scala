package org.llm4s.deploy

import org.llm4s.agent.graph.postgres.PgCheckpointerDb
import org.llm4s.it.tags.Docker
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.{ ServerSocket, URI }
import java.net.http.{ HttpClient, HttpRequest, HttpResponse }
import java.nio.file.{ Files, Path }
import java.time.Duration
import java.util.concurrent.TimeUnit
import scala.util.{ Try, Using }

/**
 * The example agent service (design 4.60) started as P2's exit test will start it: a plain JVM process running
 * `org.llm4s.deploy.DeployServiceMain`, configured only by environment variables (and one system property for the
 * schema), on the Postgres store. It becomes ready, runs a submitted run to its end while a client follows it over
 * SSE, shows the thread, and on SIGTERM drains and exits.
 *
 * Tier 2 (`@Docker`): the database is [[PgCheckpointerDb]]'s.
 */
@Docker
class DeployServiceSmokeSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private val schema = PgCheckpointerDb.freshSchema()
  private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

  override def afterAll(): Unit = if (PgCheckpointerDb.enabled) PgCheckpointerDb.dropSchema(schema)

  private def freePort(): Int = Using.resource(new ServerSocket(0))(_.getLocalPort)

  private def start(port: Int, log: Path): Process = {
    val java = Path.of(System.getProperty("java.home"), "bin", "java").toString
    val builder = new ProcessBuilder(
      java,
      s"-Dllm4s.agent.checkpointer.postgres.schema=$schema",
      "-cp",
      System.getProperty("java.class.path"),
      "org.llm4s.deploy.DeployServiceMain"
    ).redirectErrorStream(true).redirectOutput(log.toFile)
    val env = builder.environment()
    env.put("PORT", port.toString)
    env.put("LLM4S_CHECKPOINTER", "postgres")
    env.put("LLM4S_CHECKPOINTER_POSTGRES_URL", PgCheckpointerDb.jdbcUrl)
    env.put("LLM4S_CHECKPOINTER_POSTGRES_USER", PgCheckpointerDb.user)
    env.put("LLM4S_CHECKPOINTER_POSTGRES_PASSWORD", PgCheckpointerDb.password)
    env.put("LLM4S_RUNTIME_LABEL", "smoke-1")
    env.put("LLM4S_WORKER_LABEL", "smoke-1")
    env.put("LLM4S_SERVICE_TOOL_DELAY", "300 milliseconds")
    builder.start()
  }

  private def get(port: Int, path: String): Option[HttpResponse[String]] =
    Try(
      client.send(
        HttpRequest
          .newBuilder(URI.create(s"http://127.0.0.1:$port$path"))
          .timeout(Duration.ofSeconds(30))
          .GET()
          .build(),
        HttpResponse.BodyHandlers.ofString()
      )
    ).toOption

  private def post(port: Int, path: String, body: String): HttpResponse[String] =
    client.send(
      HttpRequest
        .newBuilder(URI.create(s"http://127.0.0.1:$port$path"))
        .timeout(Duration.ofSeconds(30))
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build(),
      HttpResponse.BodyHandlers.ofString()
    )

  "The example service on Postgres" should "become ready, run and stream a run, and drain on SIGTERM" in {
    PgCheckpointerDb.require()
    val port    = freePort()
    val log     = Files.createTempFile("llm4s-deploy-service", ".log")
    val process = start(port, log)
    def output  = Files.readString(log)
    Using.resource(new AutoCloseable { def close(): Unit = process.destroyForcibly(): Unit }) { _ =>
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90)
      while (!get(port, "/ready").exists(_.statusCode() == 200) && System.nanoTime() < deadline) {
        if (!process.isAlive) fail(s"the service exited before it was ready:\n$output")
        Thread.sleep(200)
      }
      withClue(output)(get(port, "/ready").map(_.statusCode()) shouldBe Some(200))

      val ticket = post(port, "/threads/smoke/runs", """{"query": "hello from the smoke test"}""")
      withClue(output)(ticket.statusCode() shouldBe 202)
      val entry = ujson.read(ticket.body())("entryId").str

      val stream = get(port, s"/runs/$entry/events").getOrElse(fail(s"no event stream:\n$output"))
      stream.statusCode() shouldBe 200
      stream.body() should include("event: run-event")
      stream.body() should include("event: end")

      ujson.read(get(port, s"/runs/$entry").get.body())("state").str shouldBe "done"
      val thread = get(port, "/threads/smoke").get
      thread.statusCode() shouldBe 200
      thread.body() should include("Echo: hello from the smoke test")
      get(port, "/threads").get.body() should include("smoke")
      get(port, "/metrics").get.body() should include("llm4s_agent_runs_started_total")

      process.destroy() // SIGTERM: the shutdown hook drains the worker, stops the server and closes the store
      withClue(output)(process.waitFor(60, TimeUnit.SECONDS) shouldBe true)
    }
  }
}
