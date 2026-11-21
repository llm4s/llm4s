package org.llm4s.llmconnect.auth.vault

import com.sun.net.httpserver.{ HttpExchange, HttpServer }
import org.llm4s.error.{ AuthenticationError, ConfigurationError, LLMError, NotFoundError, ServiceError }
import org.llm4s.http.Llm4sHttpClient
import org.llm4s.llmconnect.auth.MutableClock
import org.llm4s.security.Secret
import org.llm4s.types.ProviderModelTypes.asKey
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ ConcurrentHashMap, CountDownLatch, Executors, TimeUnit }
import scala.concurrent.duration.*

/** [[VaultApiKeyProvider]] against a fake Vault: a local HTTP server answering the KV API (#2479). */
class VaultApiKeyProviderSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private val VaultToken = "hvs.test-vault-token-0123456789"

  /** Replies by request path; every request's path and headers are recorded. */
  private val replies                                    = new ConcurrentHashMap[String, (Int, String)]()
  private val requests                                   = new ConcurrentHashMap[String, AtomicInteger]()
  @volatile private var lastHeaders: Map[String, String] = Map.empty
  @volatile private var delayMillis: Long                = 0L

  private val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
  server.setExecutor(Executors.newFixedThreadPool(8))
  server.createContext(
    "/",
    (exchange: HttpExchange) => {
      val path = exchange.getRequestURI.getRawPath
      requests.computeIfAbsent(path, _ => new AtomicInteger()).incrementAndGet()
      lastHeaders = Seq("X-Vault-Token", "X-Vault-Namespace")
        .flatMap(h => Option(exchange.getRequestHeaders.getFirst(h)).map(h -> _))
        .toMap
      if (delayMillis > 0) Thread.sleep(delayMillis)
      val (status, body) = Option(replies.get(path)).getOrElse(404 -> """{"errors":[]}""")
      val bytes          = body.getBytes(StandardCharsets.UTF_8)
      exchange.getResponseHeaders.add("Content-Type", "application/json")
      exchange.sendResponseHeaders(status, bytes.length.toLong)
      exchange.getResponseBody.write(bytes)
      exchange.close()
    }
  )
  server.start()

  private val address = s"http://127.0.0.1:${server.getAddress.getPort}"
  private val http    = Llm4sHttpClient.create()

  override def afterAll(): Unit = {
    server.stop(0)
    http.close()
  }

  private def kv2(data: (String, ujson.Value)*): String =
    ujson.write(ujson.Obj("data" -> ujson.Obj("data" -> ujson.Obj.from(data), "metadata" -> ujson.Obj("version" -> 1))))

  private def kv1(data: (String, ujson.Value)*): String = ujson.write(ujson.Obj("data" -> ujson.Obj.from(data)))

  private def hits(path: String): Int = Option(requests.get(path)).map(_.get).getOrElse(0)

  private def settings(path: String, field: String = "apiKey"): VaultSettings =
    VaultSettings(address, Secret(VaultToken), path, field)

  private def provider(
    s: VaultSettings,
    clock: MutableClock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
  ): VaultApiKeyProvider =
    VaultApiKeyProvider(s, http, clock).fold(e => fail(e.formatted), identity)

  private def key(p: VaultApiKeyProvider): String = p.current().fold(e => fail(e.formatted), _.asKey)

  /** No error text - message, formatted or toString - holds the Vault token or a key Vault returned. */
  private def assertNoSecrets(error: LLMError, secrets: String*): Unit =
    (secrets :+ VaultToken).foreach { secret =>
      (error.message should not).include(secret)
      (error.formatted should not).include(secret)
      (error.toString should not).include(secret)
    }

  "VaultApiKeyProvider" should "read the field of a KV v2 secret, sending the token" in {
    replies.put("/v1/secret/data/llm4s/openai", 200 -> kv2("apiKey" -> "sk-from-vault-v2", "other" -> "x"))

    key(provider(settings("llm4s/openai"))) shouldBe "sk-from-vault-v2"
    lastHeaders.get("X-Vault-Token") shouldBe Some(VaultToken)
    lastHeaders.get("X-Vault-Namespace") shouldBe None
  }

  it should "read a KV v1 secret under its own mount" in {
    replies.put("/v1/kv/llm4s/anthropic", 200 -> kv1("key" -> "sk-ant-from-v1"))

    val s = settings("llm4s/anthropic", "key").withMount("/kv/").withKvVersion(VaultSettings.KvVersion.V1)
    key(provider(s)) shouldBe "sk-ant-from-v1"
  }

  it should "send the namespace and encode each path segment" in {
    replies.put("/v1/secret/data/team%20a/key%2B1", 200 -> kv2("apiKey" -> "sk-namespaced-key"))

    key(provider(settings("team a/key+1").withNamespace("admin/llm"))) shouldBe "sk-namespaced-key"
    lastHeaders.get("X-Vault-Namespace") shouldBe Some("admin/llm")
  }

  it should "cache the key for the refresh interval and read a rotated key after it" in {
    val path  = "/v1/secret/data/rotating"
    val clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
    replies.put(path, 200 -> kv2("apiKey" -> "sk-rotating-key-one"))
    val p = provider(settings("rotating").withRefreshInterval(1.minute), clock)

    key(p) shouldBe "sk-rotating-key-one"
    replies.put(path, 200 -> kv2("apiKey" -> "sk-rotating-key-two"))
    clock.advance(59.seconds)
    key(p) shouldBe "sk-rotating-key-one"
    hits(path) shouldBe 1

    clock.advance(1.second)
    key(p) shouldBe "sk-rotating-key-two"
    hits(path) shouldBe 2
    key(p) shouldBe "sk-rotating-key-two"
    hits(path) shouldBe 2
  }

  it should "share one read between concurrent callers" in {
    val path = "/v1/secret/data/concurrent"
    replies.put(path, 200 -> kv2("apiKey" -> "sk-concurrent-key"))
    val p       = provider(settings("concurrent"))
    val pool    = Executors.newFixedThreadPool(8)
    val start   = new CountDownLatch(1)
    val results = new ConcurrentHashMap[Int, String]()
    delayMillis = 100
    (1 to 8).foreach(i =>
      pool.execute { () =>
        start.await()
        results.put(i, key(p))
      }
    )
    start.countDown()
    pool.shutdown()
    pool.awaitTermination(10, TimeUnit.SECONDS) shouldBe true
    delayMillis = 0

    results.size shouldBe 8
    results.values.toArray.distinct shouldBe Array("sk-concurrent-key")
    hits(path) shouldBe 1
  }

  it should "fail, not serve the expired key, when a read after the interval fails, and recover on the next call" in {
    val path  = "/v1/secret/data/outage"
    val clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
    replies.put(path, 200 -> kv2("apiKey" -> "sk-before-outage"))
    val p = provider(settings("outage").withRefreshInterval(10.seconds), clock)
    key(p) shouldBe "sk-before-outage"

    replies.put(path, 503 -> """{"errors":["Vault is sealed"]}""")
    clock.advance(10.seconds)
    val error = p.current().left.toOption.get
    error shouldBe a[ServiceError]
    error.asInstanceOf[ServiceError].httpStatus shouldBe 503
    error.message should include("Vault is sealed")
    assertNoSecrets(error, "sk-before-outage")

    replies.put(path, 200 -> kv2("apiKey" -> "sk-after-outage"))
    key(p) shouldBe "sk-after-outage"
  }

  it should "report a refused token as an AuthenticationError without the token" in {
    replies.put("/v1/secret/data/forbidden", 403 -> s"""{"errors":["permission denied", "bad token $VaultToken"]}""")

    val error = provider(settings("forbidden")).current().left.toOption.get
    error shouldBe a[AuthenticationError]
    error.message should include("permission denied")
    error.message should include("secret/forbidden#apiKey")
    assertNoSecrets(error)
  }

  it should "report a missing secret as a NotFoundError" in {
    val error = provider(settings("does/not/exist")).current().left.toOption.get
    error shouldBe a[NotFoundError]
    error.message should include("secret/does/not/exist")
    assertNoSecrets(error)
  }

  it should "refuse a secret without the field, with a blank or non-string value, or not JSON, without its content" in {
    val cases = Seq(
      "nofield"   -> kv2("other" -> "sk-other-secret-value"),
      "blank"     -> kv2("apiKey" -> "   "),
      "number"    -> kv2("apiKey" -> 12345678),
      "multiline" -> kv2("apiKey" -> "sk-line-one\nsk-line-two"),
      "notjson"   -> "sk-raw-secret-body",
      "v1shape"   -> kv1("apiKey" -> "sk-v1-shaped-secret")
    )
    cases.foreach { case (name, body) =>
      replies.put(s"/v1/secret/data/$name", 200 -> body)
      val error = provider(settings(name)).current().left.toOption.get
      withClue(name) {
        error shouldBe a[ConfigurationError]
        error.message should include(s"secret/$name")
        assertNoSecrets(error, "sk-other-secret-value", "sk-line-one", "sk-raw-secret-body", "sk-v1-shaped-secret")
      }
    }
  }

  it should "report an unreachable Vault as the HTTP client's error, without the token" in {
    val closed = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    val port   = closed.getAddress.getPort
    closed.stop(0)
    val p = VaultApiKeyProvider(
      VaultSettings(s"http://127.0.0.1:$port", Secret(VaultToken), "x", "apiKey").withRequestTimeout(2.seconds)
    ).fold(e => fail(e.formatted), identity)

    val error = p.current().left.toOption.get
    assertNoSecrets(error)
    p.close()
  }

  it should "refuse invalid settings with a ConfigurationError naming the setting, not the token" in {
    val good = settings("llm4s/openai")
    val cases = Seq(
      VaultSettings("vault:8200", Secret(VaultToken), "p", "f")      -> "address",
      VaultSettings("ftp://vault", Secret(VaultToken), "p", "f")     -> "address",
      VaultSettings(address, Secret("  "), "p", "f")                 -> "token is blank",
      VaultSettings(address, Secret("hvs.a\nb-c-d-e-f-g"), "p", "f") -> "more than one line",
      VaultSettings(address, Secret(VaultToken), " / ", "f")         -> "path is blank",
      VaultSettings(address, Secret(VaultToken), "p", " ")           -> "field is blank",
      good.withMount("/")                                            -> "mount is blank",
      good.withNamespace(" ")                                        -> "namespace is blank",
      good.withRefreshInterval(Duration.Zero)                        -> "refreshInterval",
      good.withRequestTimeout(-1.second)                             -> "requestTimeout"
    )
    cases.foreach { case (s, expected) =>
      val error = VaultApiKeyProvider(s).left.toOption.get
      withClue(expected) {
        error shouldBe a[ConfigurationError]
        error.message should include(expected)
        assertNoSecrets(error)
      }
    }
  }

  it should "never print the token" in {
    val s = settings("llm4s/openai").withNamespace(None)
    (s.toString should not).include(VaultToken)
    s.toString should include("secret/llm4s/openai#apiKey")
    (provider(s).toString should not).include(VaultToken)
  }

  it should "close an HTTP client it created and leave a supplied one open" in {
    replies.put("/v1/secret/data/closing", 200 -> kv2("apiKey" -> "sk-closing-key"))
    val closedFlag = new java.util.concurrent.atomic.AtomicBoolean(false)
    val supplied = new Llm4sHttpClient {
      private val inner = Llm4sHttpClient.create()
      export inner.{ close as _, * }
      override def close(): Unit = closedFlag.set(true)
    }
    val p = VaultApiKeyProvider(settings("closing"), supplied).fold(e => fail(e.formatted), identity)
    key(p) shouldBe "sk-closing-key"
    p.close()
    closedFlag.get shouldBe false

    val owned = VaultApiKeyProvider(settings("closing")).fold(e => fail(e.formatted), identity)
    key(owned) shouldBe "sk-closing-key"
    owned.close()
  }
}
