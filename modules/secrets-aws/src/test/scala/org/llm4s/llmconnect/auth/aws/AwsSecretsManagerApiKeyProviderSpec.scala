package org.llm4s.llmconnect.auth.aws

import com.sun.net.httpserver.{ HttpExchange, HttpServer }
import org.llm4s.error.{ AuthenticationError, ConfigurationError, LLMError, NotFoundError, ServiceError }
import org.llm4s.http.Llm4sHttpClient
import org.llm4s.llmconnect.auth.MutableClock
import org.llm4s.security.Secret
import org.llm4s.types.ProviderModelTypes.asKey
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.{ InetSocketAddress, URI }
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger }
import java.util.concurrent.{ ConcurrentHashMap, CountDownLatch, Executors, TimeUnit }
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * [[AwsSecretsManagerApiKeyProvider]] against a fake Secrets Manager: a local HTTP server answering `GetSecretValue`
 * that checks every request's Signature Version 4 signature (#2479).
 */
class AwsSecretsManagerApiKeyProviderSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private val AccessKeyId     = "AKIATESTACCESSKEY01"
  private val SecretAccessKey = "test-secret-access-key/0123456789abcdefABCDEF"
  private val SessionToken    = "test-session-token-0123456789"
  private val Region          = "eu-west-1"
  private val Start           = Instant.parse("2026-01-01T00:00:00Z")

  private val credentials = AwsCredentials(AccessKeyId, Secret(SecretAccessKey))

  /** Replies by secret id; every request's secret id, headers and signature check are recorded. */
  private val replies                                    = new ConcurrentHashMap[String, (Int, String)]()
  private val requests                                   = new ConcurrentHashMap[String, AtomicInteger]()
  @volatile private var lastHeaders: Map[String, String] = Map.empty
  @volatile private var lastBody: ujson.Value            = ujson.Null
  @volatile private var lastPath: String                 = ""
  @volatile private var signatureValid: Boolean          = false
  @volatile private var delayMillis: Long                = 0L

  private val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
  server.setExecutor(Executors.newFixedThreadPool(8))
  server.createContext(
    "/",
    (exchange: HttpExchange) => {
      val payload = exchange.getRequestBody.readAllBytes()
      val headers = exchange.getRequestHeaders.asScala.map((k, v) => k.toLowerCase -> v.asScala.head).toMap
      val body    = ujson.read(new String(payload, StandardCharsets.UTF_8))
      val id      = body("SecretId").str
      requests.computeIfAbsent(id, _ => new AtomicInteger()).incrementAndGet()
      lastHeaders = headers
      lastBody = body
      lastPath = exchange.getRequestURI.getRawPath
      signatureValid = validSignature(exchange, headers, payload)
      if (delayMillis > 0) Thread.sleep(delayMillis)
      val (status, reply) = Option(replies.get(id)).getOrElse(
        400 -> """{"__type":"ResourceNotFoundException","message":"Secrets Manager can't find the specified secret."}"""
      )
      val bytes = reply.getBytes(StandardCharsets.UTF_8)
      exchange.getResponseHeaders.add("Content-Type", "application/x-amz-json-1.1")
      exchange.sendResponseHeaders(status, bytes.length.toLong)
      exchange.getResponseBody.write(bytes)
      exchange.close()
    }
  )
  server.start()

  private val endpoint = s"http://127.0.0.1:${server.getAddress.getPort}"
  private val http     = Llm4sHttpClient.create()

  override def afterAll(): Unit = {
    server.stop(0)
    http.close()
  }

  /**
   * Recomputes the signature from what arrived - the `Host` the HTTP client sent, the signed headers' values and the
   * body - as AWS would, so a header signed but sent differently fails here.
   */
  private def validSignature(exchange: HttpExchange, headers: Map[String, String], payload: Array[Byte]): Boolean = {
    val auth         = headers.getOrElse("authorization", "")
    val signed       = auth.split("SignedHeaders=").lift(1).map(_.takeWhile(_ != ',')).getOrElse("").split(';').toSeq
    val values       = signed.filterNot(_ == "host").map(h => h -> headers.getOrElse(h, "")).toMap
    val uri          = URI.create(s"http://${headers.getOrElse("host", "")}${exchange.getRequestURI}")
    val sessionToken = headers.get("x-amz-security-token").map(Secret(_))
    val at = java.time.LocalDateTime
      .parse(headers.getOrElse("x-amz-date", ""), java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"))
      .toInstant(java.time.ZoneOffset.UTC)
    val expected = SigV4.signatureOf(
      exchange.getRequestMethod,
      uri,
      values,
      payload,
      credentials.withSessionToken(sessionToken),
      Region,
      "secretsmanager",
      at
    )
    auth.endsWith(s"Signature=$expected") &&
    auth.contains(s"Credential=$AccessKeyId/${headers("x-amz-date").take(8)}/$Region/secretsmanager/aws4_request")
  }

  private def secretString(value: String): String =
    ujson.write(ujson.Obj("ARN" -> "arn:aws:secretsmanager:eu-west-1:1:secret:x", "SecretString" -> value))

  private def hits(id: String): Int = Option(requests.get(id)).map(_.get).getOrElse(0)

  private def settings(id: String): AwsSecretsManagerSettings =
    AwsSecretsManagerSettings(Region, credentials, id).withEndpoint(endpoint)

  private def provider(
    s: AwsSecretsManagerSettings,
    clock: MutableClock = new MutableClock(Start)
  ): AwsSecretsManagerApiKeyProvider =
    AwsSecretsManagerApiKeyProvider(s, http, clock).fold(e => fail(e.formatted), identity)

  private def key(p: AwsSecretsManagerApiKeyProvider): String = p.current().fold(e => fail(e.formatted), _.asKey)

  private def error(p: AwsSecretsManagerApiKeyProvider): LLMError =
    p.current().left.toOption.getOrElse(fail("read a key"))

  /** No error text - message, formatted or toString - holds a credential or a secret Secrets Manager returned. */
  private def assertNoSecrets(error: LLMError, secrets: String*): Unit =
    (secrets ++ Seq(SecretAccessKey, SessionToken)).foreach { secret =>
      (error.message should not).include(secret)
      (error.formatted should not).include(secret)
      (error.toString should not).include(secret)
    }

  "AwsSecretsManagerApiKeyProvider" should "read a plain SecretString with a signed GetSecretValue" in {
    replies.put("llm4s/plain", 200 -> secretString("  sk-from-aws-plain  "))

    key(provider(settings("llm4s/plain"))) shouldBe "sk-from-aws-plain"
    signatureValid shouldBe true
    lastPath shouldBe "/"
    lastHeaders.get("x-amz-target") shouldBe Some("secretsmanager.GetSecretValue")
    lastHeaders.get("content-type") shouldBe Some("application/x-amz-json-1.1")
    lastHeaders.get("x-amz-date") shouldBe Some("20260101T000000Z")
    lastHeaders.get("x-amz-security-token") shouldBe None
    lastBody shouldBe ujson.Obj("SecretId" -> "llm4s/plain", "VersionStage" -> "AWSCURRENT")
  }

  it should "read a field of a key/value secret, a version stage, and sign the session token" in {
    replies.put(
      "arn:aws:secretsmanager:eu-west-1:1:secret:llm4s/kv",
      200 -> secretString("""{"apiKey":"sk-from-aws-field","other":"sk-other"}""")
    )
    val temporary = credentials.withSessionToken(Secret(SessionToken))
    val s = settings(" arn:aws:secretsmanager:eu-west-1:1:secret:llm4s/kv ")
      .withField(" apiKey ")
      .withVersionStage("AWSPENDING")
      .withCredentials(temporary)

    key(provider(s)) shouldBe "sk-from-aws-field"
    signatureValid shouldBe true
    lastHeaders.get("x-amz-security-token") shouldBe Some(SessionToken)
    lastHeaders("authorization") should include("x-amz-security-token")
    lastBody("VersionStage").str shouldBe "AWSPENDING"
  }

  it should "ask for the credentials on every read, so expiring ones are renewed" in {
    replies.put("llm4s/renewed", 200 -> secretString("sk-renewed-key"))
    val clock = new MutableClock(Start)
    val asked = new AtomicInteger()
    val s = settings("llm4s/renewed")
      .withRefreshInterval(1.minute)
      .withCredentials { () =>
        asked.incrementAndGet(); Right(credentials)
      }
    val p = provider(s, clock)

    key(p) shouldBe "sk-renewed-key"
    key(p) shouldBe "sk-renewed-key"
    asked.get shouldBe 1
    clock.advance(1.minute)
    key(p) shouldBe "sk-renewed-key"
    asked.get shouldBe 2
    lastHeaders.get("x-amz-date") shouldBe Some("20260101T000100Z")
    signatureValid shouldBe true
  }

  it should "cache the key for the refresh interval and read a rotated key after it" in {
    val id    = "llm4s/rotating"
    val clock = new MutableClock(Start)
    replies.put(id, 200 -> secretString("sk-rotating-key-one"))
    val p = provider(settings(id).withRefreshInterval(1.minute), clock)

    key(p) shouldBe "sk-rotating-key-one"
    replies.put(id, 200 -> secretString("sk-rotating-key-two"))
    clock.advance(59.seconds)
    key(p) shouldBe "sk-rotating-key-one"
    hits(id) shouldBe 1

    clock.advance(1.second)
    key(p) shouldBe "sk-rotating-key-two"
    hits(id) shouldBe 2
    key(p) shouldBe "sk-rotating-key-two"
    hits(id) shouldBe 2
  }

  it should "share one read between concurrent callers" in {
    val id = "llm4s/concurrent"
    replies.put(id, 200 -> secretString("sk-concurrent-key"))
    val p       = provider(settings(id))
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
    hits(id) shouldBe 1
  }

  it should "fail, not serve the expired key, when a read after the interval fails, and recover on the next call" in {
    val id    = "llm4s/outage"
    val clock = new MutableClock(Start)
    replies.put(id, 200 -> secretString("sk-before-outage"))
    val p = provider(settings(id).withRefreshInterval(10.seconds), clock)
    key(p) shouldBe "sk-before-outage"

    replies.put(id, 500 -> """{"__type":"InternalServiceError","message":"An error occurred on the server side."}""")
    clock.advance(10.seconds)
    val e = error(p)
    e shouldBe a[ServiceError]
    e.asInstanceOf[ServiceError].status shouldBe 500
    e.message should include("InternalServiceError")
    e.message should include("An error occurred on the server side.")
    assertNoSecrets(e, "sk-before-outage")

    replies.put(id, 200 -> secretString("sk-after-outage"))
    key(p) shouldBe "sk-after-outage"
  }

  it should "report refused credentials and denied access as an AuthenticationError without the credentials" in {
    val cases = Seq(
      "denied"  -> (400 -> s"""{"__type":"AccessDeniedException","message":"denied for $SecretAccessKey"}"""),
      "unknown" -> (400 -> """{"__type":"com.amazon.coral.service#UnrecognizedClientException","message":"bad"}"""),
      "expired" -> (400 -> s"""{"__type":"ExpiredTokenException","Message":"token $SessionToken expired"}"""),
      "status"  -> (403 -> """{"message":"Forbidden"}"""),
      "nobody"  -> (401 -> "")
    )
    cases.foreach { case (name, reply) => replies.put(s"llm4s/$name", reply) }
    cases.foreach { case (name, _) =>
      val e =
        error(provider(settings(s"llm4s/$name").withCredentials(credentials.withSessionToken(Secret(SessionToken)))))
      withClue(name) {
        e shouldBe a[AuthenticationError]
        e.message should include(s"llm4s/$name")
        assertNoSecrets(e)
      }
    }
  }

  it should "read the error type from the x-amzn-ErrorType header when the body has none" in {
    val headerServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    headerServer.createContext(
      "/",
      (exchange: HttpExchange) => {
        exchange.getRequestBody.readAllBytes()
        exchange.getResponseHeaders.add("x-amzn-ErrorType", "InvalidSignatureException:http://internal.amazon.com/")
        exchange.sendResponseHeaders(400, -1)
        exchange.close()
      }
    )
    headerServer.start()
    val s = AwsSecretsManagerSettings(Region, credentials, "llm4s/header")
      .withEndpoint(s"http://127.0.0.1:${headerServer.getAddress.getPort}/")
    val e = error(provider(s))
    headerServer.stop(0)
    e shouldBe a[AuthenticationError]
    e.message should include("InvalidSignatureException")
  }

  it should "report a missing secret as a NotFoundError" in {
    val e = error(provider(settings("does/not/exist")))
    e shouldBe a[NotFoundError]
    e.message should include("does/not/exist")
    e.message should include("can't find the specified secret")
    assertNoSecrets(e)
  }

  it should "refuse a secret with no usable string, without its content" in {
    val cases = Seq(
      "binary"    -> ujson.write(ujson.Obj("SecretBinary" -> "c2stYmluYXJ5LXNlY3JldA==")),
      "none"      -> """{"Name":"none"}""",
      "notjson"   -> "sk-raw-not-json-body",
      "blank"     -> secretString("   "),
      "multiline" -> secretString("sk-line-one\nsk-line-two")
    )
    cases.foreach { case (name, body) =>
      replies.put(s"llm4s/$name", 200 -> body)
      val e = error(provider(settings(s"llm4s/$name")))
      withClue(name) {
        e shouldBe a[ConfigurationError]
        e.message should include(s"llm4s/$name")
        assertNoSecrets(e, "c2stYmluYXJ5LXNlY3JldA==", "sk-raw-not-json-body", "sk-line-one")
      }
    }
  }

  it should "refuse a field that is missing, blank, not a string, or in a SecretString that is not JSON" in {
    val cases = Seq(
      "nofield"  -> """{"other":"sk-other-field-value"}""",
      "blankkey" -> """{"apiKey":"  "}""",
      "number"   -> """{"apiKey":12345678}""",
      "plain"    -> "sk-plain-not-an-object"
    )
    cases.foreach { case (name, secret) =>
      replies.put(s"llm4s/$name", 200 -> secretString(secret))
      val e = error(provider(settings(s"llm4s/$name").withField("apiKey")))
      withClue(name) {
        e shouldBe a[ConfigurationError]
        e.message should include(s"llm4s/$name#apiKey")
        assertNoSecrets(e, "sk-other-field-value", "sk-plain-not-an-object")
      }
    }
  }

  it should "drop a leading byte-order mark from the key (#3109)" in {
    replies.put("llm4s/bom", 200 -> secretString("\uFEFFsk-aws-bom-key"))
    key(provider(settings("llm4s/bom"))) shouldBe "sk-aws-bom-key"
  }

  it should "refuse a key holding a control character or one above U+00FF, naming the kind, without the key (#3109)" in {
    val cases = Seq(
      "esc"      -> secretString("sk-esc\u001bsecret-value") -> "a control character at position 7",
      "nul"      -> secretString("sk-nul\u0000secret-value") -> "a control character at position 7",
      "nonascii" -> secretString("sk-caf\u017esecret-value") -> "a character above U+00FF at position 7",
      "field" -> secretString("{\"apiKey\":\"sk-esc\\u001bsecret-value\"}") -> "a control character at position 7"
    )
    cases.foreach { case ((name, body), reason) =>
      replies.put(s"llm4s/chars-$name", 200 -> body)
      val s =
        if name == "field" then settings(s"llm4s/chars-$name").withField("apiKey") else settings(s"llm4s/chars-$name")
      val e = error(provider(s))
      withClue(name) {
        e shouldBe a[ConfigurationError]
        e.message should include(s.location)
        e.message should include(reason)
        assertNoSecrets(e, "secret-value")
      }
    }
  }

  it should "refuse unusable credentials before contacting AWS, naming the part, not its value" in {
    replies.put("llm4s/creds", 200 -> secretString("sk-never-read"))
    val cases = Seq[(() => org.llm4s.types.Result[AwsCredentials], String)](
      (() => Right(null))                                                        -> "none were given",
      (() => Right(AwsCredentials(" ", Secret(SecretAccessKey))))                -> "accessKeyId is blank",
      (() => Right(AwsCredentials(null, Secret(SecretAccessKey))))               -> "accessKeyId is blank",
      (() => Right(AwsCredentials("AKIA BAD", Secret(SecretAccessKey))))         -> "accessKeyId holds whitespace",
      (() => Right(AwsCredentials(AccessKeyId, Secret(" "))))                    -> "secretAccessKey is blank",
      (() => Right(AwsCredentials(AccessKeyId, null)))                           -> "secretAccessKey is blank",
      (() => Right(AwsCredentials(AccessKeyId, Secret(s"$SecretAccessKey\nx")))) -> "more than one line",
      (() => Right(credentials.withSessionToken(Secret(" "))))                   -> "sessionToken is blank",
      (() => Right(credentials.withSessionToken(Secret(s"$SessionToken\r\nx")))) -> "sessionToken holds",
      (() => Left(ConfigurationError("no instance profile")))                    -> "no instance profile",
      (() => throw new IllegalStateException(s"leaked $SecretAccessKey"))        -> "could not be obtained"
    )
    cases.foreach { case (source, expected) =>
      val e = error(provider(settings("llm4s/creds").withCredentials(source)))
      withClue(expected) {
        e shouldBe a[ConfigurationError]
        e.message should include(expected)
        assertNoSecrets(e)
      }
    }
    hits("llm4s/creds") shouldBe 0
  }

  it should "report an unreachable Secrets Manager as the HTTP client's error, without the credentials" in {
    val closed = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    val port   = closed.getAddress.getPort
    closed.stop(0)
    val p = AwsSecretsManagerApiKeyProvider(
      AwsSecretsManagerSettings(Region, credentials.withSessionToken(Secret(SessionToken)), "x")
        .withEndpoint(s"http://127.0.0.1:$port")
        .withRequestTimeout(2.seconds)
    ).fold(e => fail(e.formatted), identity)

    assertNoSecrets(error(p))
    p.close()
  }

  it should "refuse invalid settings with a ConfigurationError naming the setting" in {
    val good = settings("llm4s/openai")
    val cases = Seq(
      AwsSecretsManagerSettings("", credentials, "id")          -> "region",
      AwsSecretsManagerSettings("Eu West 1", credentials, "id") -> "region",
      AwsSecretsManagerSettings(null, credentials, "id")        -> "region",
      AwsSecretsManagerSettings(Region, credentials, " ")       -> "secretId is blank",
      good.withField(" ")                                       -> "field is blank",
      good.withVersionStage(" ")                                -> "versionStage is blank",
      good.withEndpoint("localhost:4566")                       -> "endpoint",
      good.withEndpoint("ftp://secrets")                        -> "endpoint",
      good.withEndpoint("https://secrets.internal/prefix")      -> "endpoint",
      good.withEndpoint("https://secrets.internal/?x=1")        -> "endpoint",
      good.withEndpoint("http://[bad")                          -> "endpoint",
      good.withRefreshInterval(Duration.Zero)                   -> "refreshInterval",
      good.withRequestTimeout(-1.second)                        -> "requestTimeout"
    )
    cases.foreach { case (s, expected) =>
      val e = AwsSecretsManagerApiKeyProvider(s).left.toOption.getOrElse(fail(s"accepted $s"))
      withClue(expected) {
        e shouldBe a[ConfigurationError]
        e.message should include(expected)
        assertNoSecrets(e)
      }
    }
  }

  it should "default to the region's public endpoint, the China partition's for a cn- region" in {
    AwsSecretsManagerSettings("us-east-1", credentials, "id").endpointUrl shouldBe
      "https://secretsmanager.us-east-1.amazonaws.com"
    AwsSecretsManagerSettings("cn-north-1", credentials, "id").endpointUrl shouldBe
      "https://secretsmanager.cn-north-1.amazonaws.com.cn"
    settings("id").withEndpoint(None).endpointUrl shouldBe "https://secretsmanager.eu-west-1.amazonaws.com"
    settings("id").withField(None).location shouldBe "id"
  }

  it should "never print a credential" in {
    val temporary = credentials.withSessionToken(Secret(SessionToken))
    val s         = settings("llm4s/openai").withField("apiKey").withCredentials(temporary)
    Seq(s.toString, temporary.toString, credentials.toString, provider(s).toString).foreach { text =>
      (text should not).include(SecretAccessKey)
      (text should not).include(SessionToken)
    }
    s.toString should include("llm4s/openai#apiKey")
    temporary.toString should include(AccessKeyId)
    credentials.toString should include("sessionToken=-")
    credentials.withSessionToken(None) shouldBe credentials
  }

  it should "close an HTTP client it created and leave a supplied one open" in {
    replies.put("llm4s/closing", 200 -> secretString("sk-closing-key"))
    val closedFlag = new AtomicBoolean(false)
    val supplied = new Llm4sHttpClient {
      private val inner                             = Llm4sHttpClient.create()
      def send(request: org.llm4s.http.HttpRequest) = inner.send(request)
      override def close(): Unit                    = closedFlag.set(true)
    }
    val p = AwsSecretsManagerApiKeyProvider(settings("llm4s/closing"), supplied).fold(e => fail(e.formatted), identity)
    key(p) shouldBe "sk-closing-key"
    p.close()
    closedFlag.get shouldBe false

    val owned = AwsSecretsManagerApiKeyProvider(settings("llm4s/closing")).fold(e => fail(e.formatted), identity)
    key(owned) shouldBe "sk-closing-key"
    owned.close()
  }
}
