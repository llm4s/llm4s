package org.llm4s.config

import com.sun.net.httpserver.{ HttpExchange, HttpServer }
import org.llm4s.it.Tier
import org.llm4s.it.tags.Docker
import org.llm4s.llmconnect.{ LLMClient, LLMConnect }
import org.llm4s.llmconnect.config.OpenAICompatibleConfig
import org.llm4s.llmconnect.model.{ Conversation, UserMessage }
import org.llm4s.model.ModelRegistryService
import org.llm4s.testkit.LocalProviderTestServer.openAICompletion
import org.llm4s.testkit.ProviderModuleChecks
import org.scalatest.{ BeforeAndAfterAll, BeforeAndAfterEach, EitherValues }
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path, Paths }
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

/**
 * The gateway path of `docs/guide/reference-architecture.md` (#2315), through a real LiteLLM proxy.
 *
 * The app's section is `deploy/overlays/enterprise/application.conf` as the overlay ships it, with only the gateway's
 * address and the key file made local. LiteLLM (`modules/it/src/test/resources/litellm/gateway-it.yaml`) routes the
 * app's model to a fake upstream that this suite runs on the host and that records what reaches it. So the suite
 * shows, end to end: the app authenticates to the gateway with its own key, the gateway calls the vendor with the
 * vendor key (never the app's), maps the model name, and carries the tenant headers across; streaming works through
 * it; and a key the gateway does not know is refused before anything reaches the vendor.
 *
 * Needs a LiteLLM container (CI's `integration-tests` job starts it):
 * {{{
 *   docker run -d --name litellm --add-host=host.docker.internal:host-gateway -p 4000:4000 \
 *     -e LITELLM_MASTER_KEY=sk-it-master-0001 -e UPSTREAM_API_KEY=sk-it-upstream-0001 \
 *     -e LITELLM_LOCAL_MODEL_COST_MAP=True \
 *     -v "$PWD/modules/it/src/test/resources/litellm/gateway-it.yaml:/app/config.yaml:ro" \
 *     ghcr.io/berriai/litellm:main-v1.83.14-stable --config /app/config.yaml --port 4000
 *   LITELLM_TEST_URL=http://localhost:4000 LITELLM_TEST_MASTER_KEY=sk-it-master-0001 \
 *   LITELLM_TEST_UPSTREAM_KEY=sk-it-upstream-0001 sbt "it/testOnly org.llm4s.config.LiteLLMGatewaySpec"
 * }}}
 * It is in package `org.llm4s.config` for `ReferenceConfig.withEnv`, which loads a given HOCON text with an injected
 * environment.
 */
@Docker
class LiteLLMGatewaySpec
    extends AnyWordSpec
    with Matchers
    with EitherValues
    with BeforeAndAfterAll
    with BeforeAndAfterEach:

  private val gatewayUrl  = sys.env.get("LITELLM_TEST_URL").map(_.stripSuffix("/"))
  private val masterKey   = sys.env.getOrElse("LITELLM_TEST_MASTER_KEY", "sk-it-master-0001")
  private val upstreamKey = sys.env.getOrElse("LITELLM_TEST_UPSTREAM_KEY", "sk-it-upstream-0001")
  // The port gateway-it.yaml's api_base names.
  private val UpstreamPort = 18089

  private val Overlay        = "deploy/overlays/enterprise/application.conf"
  private val MountedKey     = "/var/run/secrets/llm4s/gateway-api-key"
  private val OverlayGateway = "http://litellm:4000/v1"
  private val hello          = Conversation(Seq(UserMessage("Hello")))

  /** One request as the fake upstream (the "vendor" behind the gateway) received it. */
  final case class Upstream(path: String, authorization: String, tenant: String, costCentre: String, model: String)

  private val received                     = new ConcurrentLinkedQueue[Upstream]()
  private var upstream: Option[HttpServer] = None

  override def beforeAll(): Unit =
    if gatewayUrl.isDefined then
      // On every interface: the container reaches the host through the Docker bridge, not the loopback.
      val server = HttpServer.create(new InetSocketAddress(UpstreamPort), 0)
      server.createContext("/", exchange => answer(exchange))
      server.start()
      upstream = Some(server)

  override def afterAll(): Unit = upstream.foreach(_.stop(0))

  override def beforeEach(): Unit = received.clear()

  private def answer(exchange: HttpExchange): Unit =
    val headers = exchange.getRequestHeaders
    val body    = ujson.read(new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8))
    received.add(
      Upstream(
        exchange.getRequestURI.getPath,
        Option(headers.getFirst("Authorization")).getOrElse(""),
        Option(headers.getFirst("X-Tenant-Id")).getOrElse(""),
        Option(headers.getFirst("X-Cost-Centre")).getOrElse(""),
        body("model").str
      )
    )
    val streamed = body.obj.get("stream").exists(_.boolOpt.contains(true))
    val (contentType, reply) =
      if streamed then ("text/event-stream", sse(List("Hello", " from", " upstream")))
      else ("application/json", openAICompletion("Hello from upstream", "upstream-model-v1"))
    val bytes = reply.getBytes(StandardCharsets.UTF_8)
    exchange.getResponseHeaders.add("Content-Type", contentType)
    exchange.sendResponseHeaders(200, bytes.length.toLong)
    exchange.getResponseBody.write(bytes)
    exchange.close()

  private def sse(pieces: List[String]): String =
    def chunk(delta: ujson.Obj, finish: ujson.Value) =
      ujson
        .Obj(
          "id"      -> "chatcmpl-upstream",
          "object"  -> "chat.completion.chunk",
          "created" -> 1700000000,
          "model"   -> "upstream-model-v1",
          "choices" -> ujson.Arr(ujson.Obj("index" -> 0, "delta" -> delta, "finish_reason" -> finish))
        )
        .render()
    val first = chunk(ujson.Obj("role" -> "assistant", "content" -> ""), ujson.Null)
    val parts = pieces.map(p => chunk(ujson.Obj("content" -> p), ujson.Null))
    val last  = chunk(ujson.Obj(), ujson.Str("stop"))
    (first :: parts ::: List(last)).map(c => s"data: $c\n\n").mkString + "data: [DONE]\n\n"

  private lazy val root: Path =
    Iterator
      .iterate(Paths.get(System.getProperty("user.dir")).toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .find(dir => Files.exists(dir.resolve(Overlay)))
      .getOrElse(fail(s"$Overlay was not found above ${System.getProperty("user.dir")}"))

  private def keyFile(content: String): Path =
    val file = Files.createTempFile("gateway-key", ".txt")
    file.toFile.deleteOnExit()
    Files.writeString(file, content)

  /** The overlay's application.conf, pointed at the local gateway and reading its key from `file`. */
  private def client(file: Path, url: String): LLMClient =
    val overlay = new String(Files.readAllBytes(root.resolve(Overlay)), StandardCharsets.UTF_8)
    overlay should include("\"" + OverlayGateway + "\"")
    overlay should include("\"" + MountedKey + "\"")
    val local = overlay
      .replace("\"" + OverlayGateway + "\"", "\"" + url + "/v1\"")
      .replace("\"" + MountedKey + "\"", "\"" + file.toString.replace("\\", "\\\\") + "\"")
    val cfg = Llm4sConfig.defaultProvider(ReferenceConfig.withEnv(local, Map.empty)).value match
      case c: OpenAICompatibleConfig => c
      case other                     => fail(s"expected the overlay's openai-compatible gateway section, got $other")
    given ModelRegistryService = ProviderModuleChecks.defaultModelRegistry
    LLMConnect.getClient(cfg).value

  private def withGateway(test: String => Unit): Unit =
    Tier.require(gatewayUrl.isDefined, "LITELLM_TEST_URL not set (no LiteLLM gateway to send through)")
    gatewayUrl.foreach(test)

  private val expectedUpstream =
    Upstream("/v1/chat/completions", s"Bearer $upstreamKey", "acme-retail", "cc-4711", "upstream-model-v1")

  "an llm4s client configured as the enterprise overlay" should {

    "reach the vendor through LiteLLM with the vendor key, the mapped model and the tenant headers" in withGateway {
      url =>
        val llm = client(keyFile(masterKey), url)
        llm.complete(hello).value.content shouldBe "Hello from upstream"
        llm.close()

        received.asScala.toList shouldBe List(expectedUpstream)
        // the app's gateway key stays at the gateway
        received.asScala.map(_.authorization) should not contain s"Bearer $masterKey"
    }

    "stream a reply through LiteLLM" in withGateway { url =>
      val llm    = client(keyFile(masterKey), url)
      val pieces = new ConcurrentLinkedQueue[String]()
      val done   = llm.streamComplete(hello, onChunk = chunk => chunk.content.foreach(pieces.add)).value
      llm.close()

      pieces.asScala.mkString shouldBe "Hello from upstream"
      done.content shouldBe "Hello from upstream"
      received.asScala.toList shouldBe List(expectedUpstream)
    }

    "be refused by the gateway with a key it does not know, before anything reaches the vendor" in withGateway { url =>
      val llm = client(keyFile("sk-not-a-gateway-key"), url)
      // Refused, but not as a 401: with a master key and no database, LiteLLM cannot look up virtual keys and answers
      // any other key with 400 "No connected db.", which llm4s reads as a ValidationError. What the suite shows is
      // that the gateway stops it, so the error's kind is not pinned.
      llm.complete(hello).left.value.message should not be empty
      llm.close()

      received.asScala shouldBe empty
    }
  }
