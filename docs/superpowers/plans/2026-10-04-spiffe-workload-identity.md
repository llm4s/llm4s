# SPIFFE Workload Identity Implementation Plan (#1354)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a named provider section authenticate with a SPIFFE JWT-SVID (exchanged for a short-lived access token, refreshed before expiry and once on 401) for the generic `openai-compatible` provider (Databricks), and through the vendor SDKs' native workload identity for `openai` and `anthropic`; prove it at four test layers including a real SPIRE stack.

**Architecture:** Core gains `org.llm4s.llmconnect.auth` (identity-token sources, a caching access-token provider, an RFC 8693 exchange) and a built-in `auth` block on `NamedProviderConfig`, of which core parses only the identity-token source and hands the remaining keys to the provider as auth extras it declares in `ProviderConfigSpec.authExtras`. `openai-compatible` uses the core exchange and refreshes/retries on 401 inside `OpenAICompatibleClient`; `openai` and `anthropic` translate the section into their SDKs' workload-identity configuration. A new `@Spiffe` integration tier runs SPIRE + `spiffe-helper` in Docker Compose and drives all three clients with real SVIDs against in-JVM fakes that verify the SVID signature.

**Tech Stack:** Scala 3.7.1, JDK 21, sbt, ScalaTest, upickle/ujson, pureconfig, openai-java 4.69.3, anthropic-java 2.65.0, SPIRE 1.15.3, spiffe-helper 0.12.1, nimbus-jose-jwt (test-only, `it`).

**Spec:** `docs/superpowers/specs/2026-10-04-spiffe-workload-identity-design.md`

**Issue:** [#1354](https://github.com/llm4s/llm4s/issues/1354)

## Global Constraints

- Scala 3 only; Scala 3 idioms (`enum`, `opaque type`, `using`) welcome.
- Every fallible operation returns `Result[A]` (`Either[LLMError, A]`); no exceptions escape public APIs.
- Scalafix bans `try {`, `catch {`, `finally`, `sys.env`, `System.getenv`, `ConfigFactory`, `ConfigSource.default` (main sources) and infix operator calls - use `Try(...)`, `Using`, `.toResult`.
- Durations are `FiniteDuration`, points in time `Instant`; never a raw `Long` with a unit in its name.
- Nothing in `llm4s-core` names a provider; core ships no provider and no provider-specific key.
- A frozen module's public signatures expose no third-party type: the openai-java / anthropic-java adapters are `private[provider]`.
- Nothing is frozen before 1.0: change APIs directly, no compatibility overloads or `@deprecated` shims.
- Secrets (identity tokens, access tokens, API keys) never appear in `toString`, log lines or error messages.
- The API-side refresh-and-retry triggers on `AuthenticationError` from the API call (HTTP 401 **or 403**, which `HttpErrorMapper` both map to it) and retries **exactly once**; a token-endpoint failure is never retried by this mechanism.
- Every commit uses `git commit -s` and ends with `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.
- Format with `sbt scalafmtAll` before each commit.
- Image pins: `ghcr.io/spiffe/spire-server:1.15.3`, `ghcr.io/spiffe/spire-agent:1.15.3`, `ghcr.io/spiffe/spiffe-helper:0.12.1`.

## Review Focus

1. **Token lifetime shorter than the refresh margin** (e.g. `expires_in = 30` with the default 60 s margin): expected to use the token for half its life rather than refetch on every call. Pinned in Task 1 (`refreshes at half-life when the token lives shorter than the margin`).
2. **A token-endpoint error body that echoes the subject token** (some IdPs quote the rejected assertion): expected the JWT never to appear in the resulting error message. Pinned in Task 2 (`never echoes the subject token in an error`).
3. **An optional auth key bound to an unset variable** (`clientId = ${?DATABRICKS_CLIENT_ID}` with the variable absent): expected the key to be simply absent, not an error. Pinned in Task 3 (`treats an unset optional auth key as absent`).
4. **`auth` on a section whose vendor's shared key is also set** (`OPENAI_API_KEY` exported, section uses `auth`): expected the section to load with `auth` and no API key, not a "both set" error. Pinned in Task 3 (`ignores the shared credential when the section uses auth`).
5. **A relative `identityTokenFile`**: expected it resolved against the working directory at load time, so the SDKs (which may resolve differently) get an absolute path. Pinned in Task 3 (`resolves a relative identityTokenFile to an absolute path`).

## Refinements of the spec

Decisions made while planning against the code; each is narrower or safer than the spec's wording.

- `Credential.None` is named `Credential.Anonymous` (a case called `None` shadows `scala.None` inside the enum).
- The client's one retry fires on `AuthenticationError`, which `HttpErrorMapper` produces for 401 and 403 alike; a 403 costs one extra call.
- `OpenAIConfig` and `AnthropicConfig` keep `apiKey: String`, which is `""` exactly when the new `workloadIdentity` field is set, rather than becoming a credential ADT - `OpenAIConfig` has a dozen users across `image`, `samples` and `openai-compatible`. Revisit if it reads badly in review.
- Core tests use the existing `MockHttpClient` for the exchange (core cannot depend on the testkit, which depends on core); the testkit fake serves provider modules and `it`.
- The trust bundle comes from `spiffe-helper`'s `jwt_bundle_file_name`, not a separate export step, so it follows key rotation; the TLS keystore for the OpenAI proxy is generated per run with `keytool`, not checked in.

---

## PR 1: core, provider wiring, layers 1 and 2

### Task 1: Identity-token sources and the caching access-token provider

**Files:**
- Create: `modules/core/src/main/scala/org/llm4s/llmconnect/auth/IdentityTokenSource.scala`
- Create: `modules/core/src/main/scala/org/llm4s/llmconnect/auth/AccessTokenProvider.scala`
- Test: `modules/core/src/test/scala/org/llm4s/llmconnect/auth/IdentityTokenSourceSpec.scala`
- Test: `modules/core/src/test/scala/org/llm4s/llmconnect/auth/CachingAccessTokenProviderSpec.scala`
- Test: `modules/core/src/test/scala/org/llm4s/llmconnect/auth/MutableClock.scala`

**Interfaces:**
- Produces:
  - `enum IdentitySource { case File(path: java.nio.file.Path); case Literal(token: String) }`
  - `trait IdentityTokenSource { def fetch(): Result[String] }`; `IdentityTokenSource.file(path: Path)`, `.static(token: String)`, `.from(source: IdentitySource)`
  - `final case class AccessToken(value: String, expiresAt: Instant)`
  - `trait AccessTokenProvider { def token(): Result[String]; def invalidate(rejected: String): Unit }`
  - `final class CachingAccessTokenProvider(fetch: () => Result[AccessToken], refreshMargin: FiniteDuration = CachingAccessTokenProvider.DefaultRefreshMargin, clock: Clock = Clock.systemUTC()) extends AccessTokenProvider`; `CachingAccessTokenProvider.DefaultRefreshMargin: FiniteDuration = 60.seconds`
  - test helper `org.llm4s.llmconnect.auth.MutableClock(start: Instant)` with `advance(d: FiniteDuration): Unit`

- [ ] **Step 1: Write the test clock**

```scala
// modules/core/src/test/scala/org/llm4s/llmconnect/auth/MutableClock.scala
package org.llm4s.llmconnect.auth

import java.time.{ Clock, Instant, ZoneId, ZoneOffset }
import scala.concurrent.duration.FiniteDuration

/** A clock a test moves by hand. */
final class MutableClock(start: Instant) extends Clock:
  @volatile private var now: Instant = start
  def advance(by: FiniteDuration): Unit           = now = now.plusMillis(by.toMillis)
  override def instant(): Instant                 = now
  override def getZone: ZoneId                    = ZoneOffset.UTC
  override def withZone(zone: ZoneId): Clock      = this
```

- [ ] **Step 2: Write the failing source spec**

```scala
// modules/core/src/test/scala/org/llm4s/llmconnect/auth/IdentityTokenSourceSpec.scala
package org.llm4s.llmconnect.auth

import org.llm4s.error.AuthenticationError
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }

class IdentityTokenSourceSpec extends AnyWordSpec with Matchers with EitherValues:

  private def tempFile(content: String): Path =
    val file = Files.createTempFile("svid", ".jwt")
    file.toFile.deleteOnExit()
    Files.writeString(file, content, StandardCharsets.UTF_8)

  "IdentityTokenSource.file" should {
    "read the token, trimmed" in {
      IdentityTokenSource.file(tempFile("  eyJ.a.b\n")).fetch() shouldBe Right("eyJ.a.b")
    }
    "pick up a rotated token on the next fetch" in {
      val file   = tempFile("first")
      val source = IdentityTokenSource.file(file)
      source.fetch() shouldBe Right("first")
      Files.writeString(file, "second")
      source.fetch() shouldBe Right("second")
    }
    "fail with AuthenticationError for a missing file" in {
      val error = IdentityTokenSource.file(Path.of("/no/such/svid")).fetch().left.value
      error shouldBe an[AuthenticationError]
      error.message should include("/no/such/svid")
    }
    "fail with AuthenticationError for an empty or whitespace-only file" in {
      IdentityTokenSource.file(tempFile("")).fetch().left.value shouldBe an[AuthenticationError]
      IdentityTokenSource.file(tempFile(" \n\t")).fetch().left.value shouldBe an[AuthenticationError]
    }
  }

  "IdentityTokenSource.from" should {
    "turn a literal into a static source" in {
      IdentityTokenSource.from(IdentitySource.Literal("tok")).fetch() shouldBe Right("tok")
    }
    "never show a literal token in toString" in {
      IdentitySource.Literal("secret-jwt").toString should not include "secret-jwt"
    }
  }
```

- [ ] **Step 3: Write the failing provider spec**

```scala
// modules/core/src/test/scala/org/llm4s/llmconnect/auth/CachingAccessTokenProviderSpec.scala
package org.llm4s.llmconnect.auth

import org.llm4s.error.AuthenticationError
import org.llm4s.types.Result
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant
import java.util.concurrent.{ Callable, Executors }
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

class CachingAccessTokenProviderSpec extends AnyWordSpec with Matchers:

  private val start = Instant.parse("2026-10-04T12:00:00Z")

  /** Issues t1, t2, ... each living `lifetime` from the clock's now. */
  private final class Issuer(clock: MutableClock, lifetime: FiniteDuration):
    val calls = new AtomicInteger(0)
    def fetch(): Result[AccessToken] =
      val n = calls.incrementAndGet()
      Right(AccessToken(s"t$n", clock.instant().plusMillis(lifetime.toMillis)))

  "CachingAccessTokenProvider" should {
    "reuse the token until the refresh margin, then refresh" in {
      val clock    = MutableClock(start)
      val issuer   = Issuer(clock, 10.minutes)
      val provider = CachingAccessTokenProvider(() => issuer.fetch(), 1.minute, clock)
      provider.token() shouldBe Right("t1")
      clock.advance(8.minutes)
      provider.token() shouldBe Right("t1")
      clock.advance(1.minute + 1.second)
      provider.token() shouldBe Right("t2")
      issuer.calls.get shouldBe 2
    }

    "refreshes at half-life when the token lives shorter than the margin" in {
      val clock    = MutableClock(start)
      val issuer   = Issuer(clock, 30.seconds)
      val provider = CachingAccessTokenProvider(() => issuer.fetch(), 60.seconds, clock)
      provider.token() shouldBe Right("t1")
      clock.advance(14.seconds)
      provider.token() shouldBe Right("t1")
      clock.advance(2.seconds)
      provider.token() shouldBe Right("t2")
    }

    "make one fetch for 20 concurrent callers" in {
      val clock  = MutableClock(start)
      val calls  = new AtomicInteger(0)
      val fetch = () => {
        calls.incrementAndGet()
        Thread.sleep(50)
        Right(AccessToken("t1", clock.instant().plusSeconds(600)))
      }
      val provider = CachingAccessTokenProvider(fetch, 1.minute, clock)
      val pool     = Executors.newVirtualThreadPerTaskExecutor()
      val tasks    = (1 to 20).map(_ => (() => provider.token()): Callable[Result[String]])
      val results  = pool.invokeAll(tasks.asJava).asScala.map(_.get())
      pool.shutdown()
      results.distinct shouldBe Seq(Right("t1"))
      calls.get shouldBe 1
    }

    "drop the cached token only when it is the rejected one" in {
      val clock    = MutableClock(start)
      val issuer   = Issuer(clock, 10.minutes)
      val provider = CachingAccessTokenProvider(() => issuer.fetch(), 1.minute, clock)
      provider.token() shouldBe Right("t1")
      provider.invalidate("t1")
      provider.token() shouldBe Right("t2")
      provider.invalidate("t1") // stale: a slower caller's 401 for the old token
      provider.token() shouldBe Right("t2")
      issuer.calls.get shouldBe 2
    }

    "not cache a failure" in {
      val clock = MutableClock(start)
      val calls = new AtomicInteger(0)
      val fetch = () =>
        if calls.incrementAndGet() == 1 then Left(AuthenticationError("token-exchange", "nope"))
        else Right(AccessToken("t2", clock.instant().plusSeconds(600)))
      val provider = CachingAccessTokenProvider(fetch, 1.minute, clock)
      provider.token().isLeft shouldBe true
      provider.token() shouldBe Right("t2")
    }

    "not cache a token that is already expired" in {
      val clock    = MutableClock(start)
      val issuer   = Issuer(clock, 0.seconds)
      val provider = CachingAccessTokenProvider(() => issuer.fetch(), 1.minute, clock)
      provider.token() shouldBe Right("t1")
      provider.token() shouldBe Right("t2")
    }

    "redact the token value in AccessToken.toString" in {
      AccessToken("secret", start).toString should not include "secret"
    }
  }
```

- [ ] **Step 4: Run the specs to see them fail**

Run: `sbt "core/testOnly org.llm4s.llmconnect.auth.*"`
Expected: compilation failure - `IdentityTokenSource`, `CachingAccessTokenProvider` not found.

- [ ] **Step 5: Implement the sources**

```scala
// modules/core/src/main/scala/org/llm4s/llmconnect/auth/IdentityTokenSource.scala
package org.llm4s.llmconnect.auth

import org.llm4s.error.AuthenticationError
import org.llm4s.types.Result

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }
import scala.util.Try

/**
 * Where a workload's identity token comes from - typically a SPIFFE JWT-SVID file that
 * `spiffe-helper` keeps fresh. The configured form, before it is read.
 */
enum IdentitySource:
  /** A file re-read on every fetch, so a rotated token is picked up. */
  case File(path: Path)

  /** A fixed token: for tests, or a token injected some other way. */
  case Literal(token: String)

  override def toString: String = this match
    case File(path)  => s"File($path)"
    case Literal(_)  => "Literal(***)"

/** Fetches the subject token a token exchange presents. */
trait IdentityTokenSource:
  def fetch(): Result[String]

object IdentityTokenSource:

  private val Provider = "workload-identity"

  /** Reads `path` on every fetch, trimmed; a missing, unreadable or blank file is an `AuthenticationError`. */
  def file(path: Path): IdentityTokenSource = () =>
    Try(Files.readString(path, StandardCharsets.UTF_8)).toEither.left
      .map(e => AuthenticationError(Provider, s"cannot read identity token file $path: ${e.getClass.getSimpleName}"))
      .flatMap(raw => nonBlank(raw, s"identity token file $path is empty"))

  /** Always returns `token`, trimmed; a blank token is an `AuthenticationError`. */
  def static(token: String): IdentityTokenSource = () => nonBlank(token, "identity token is empty")

  def from(source: IdentitySource): IdentityTokenSource = source match
    case IdentitySource.File(path)     => file(path)
    case IdentitySource.Literal(token) => static(token)

  private def nonBlank(raw: String, whenBlank: String): Result[String] =
    val token = raw.trim
    if token.isEmpty then Left(AuthenticationError(Provider, whenBlank)) else Right(token)
```

- [ ] **Step 6: Implement the provider**

```scala
// modules/core/src/main/scala/org/llm4s/llmconnect/auth/AccessTokenProvider.scala
package org.llm4s.llmconnect.auth

import org.llm4s.types.Result

import java.time.{ Clock, Duration as JDuration, Instant }
import scala.concurrent.duration.*

/** A bearer token and when it stops being valid. The value is redacted in `toString`. */
final case class AccessToken(value: String, expiresAt: Instant):
  override def toString: String = s"AccessToken(***, expiresAt=$expiresAt)"

/** Supplies the bearer token for each request, refreshing it as needed. */
trait AccessTokenProvider:
  /** A token believed valid now. */
  def token(): Result[String]

  /**
   * Reports that the server rejected `rejected`. The cached token is dropped only if it is that
   * token, so concurrent calls that all saw a 401 for the same token cause one refresh, and a late
   * report about an older token does not discard a newer one.
   */
  def invalidate(rejected: String): Unit

/**
 * Caches the token `fetch` returns until `refreshMargin` before it expires - or half-way through
 * its life, if it lives shorter than twice the margin. Concurrent callers share one in-flight
 * fetch. A failed fetch is not cached: the next call tries again.
 */
final class CachingAccessTokenProvider(
  fetch: () => Result[AccessToken],
  refreshMargin: FiniteDuration = CachingAccessTokenProvider.DefaultRefreshMargin,
  clock: Clock = Clock.systemUTC()
) extends AccessTokenProvider:

  private final case class Cached(token: AccessToken, refreshAt: Instant)

  @volatile private var cached: Option[Cached] = None
  private val lock                              = new Object

  def token(): Result[String] =
    fresh() match
      case Some(value) => Right(value)
      case None =>
        lock.synchronized {
          fresh() match
            case Some(value) => Right(value)
            case None =>
              fetch().map { token =>
                cached = Some(Cached(token, refreshAt(token)))
                token.value
              }
        }

  def invalidate(rejected: String): Unit =
    lock.synchronized { cached = cached.filterNot(_.token.value == rejected) }

  private def fresh(): Option[String] =
    cached.collect { case Cached(token, at) if clock.instant().isBefore(at) => token.value }

  private def refreshAt(token: AccessToken): Instant =
    val now      = clock.instant()
    val lifetime = JDuration.between(now, token.expiresAt)
    if lifetime.isNegative || lifetime.isZero then now
    else
      val margin = JDuration.ofMillis(refreshMargin.toMillis)
      val half   = lifetime.dividedBy(2)
      token.expiresAt.minus(if half.compareTo(margin) < 0 then half else margin)

object CachingAccessTokenProvider:
  val DefaultRefreshMargin: FiniteDuration = 60.seconds
```

- [ ] **Step 7: Run the specs to see them pass**

Run: `sbt "core/testOnly org.llm4s.llmconnect.auth.*"`
Expected: PASS (all tests in both specs).

- [ ] **Step 8: Commit**

```bash
sbt scalafmtAll
git add modules/core/src/main/scala/org/llm4s/llmconnect/auth modules/core/src/test/scala/org/llm4s/llmconnect/auth
git commit -s -m "feat(core): identity-token sources and a caching access-token provider

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: RFC 8693 token exchange

**Files:**
- Create: `modules/core/src/main/scala/org/llm4s/llmconnect/auth/TokenExchange.scala`
- Test: `modules/core/src/test/scala/org/llm4s/llmconnect/auth/TokenExchangeSpec.scala`

**Interfaces:**
- Consumes: `IdentitySource`, `IdentityTokenSource.from`, `AccessToken` (Task 1); `org.llm4s.http.{ Llm4sHttpClient, HttpResponse }`; `org.llm4s.llmconnect.provider.HttpErrorMapper.mapHttpError(status, body, provider, headers): Result[Nothing]`; core test `org.llm4s.http.MockHttpClient(responses: Seq[HttpResponse])` with `lastUrl`, `lastHeaders`, `lastBody`, `postCallCount`.
- Produces:
  - `final case class TokenExchangeConfig(identityToken: IdentitySource, tokenUrl: String, clientId: Option[String] = None, scope: Option[String] = None, audience: Option[String] = None)` (`toString` delegates to `IdentitySource.toString`, so literals stay redacted)
  - `TokenExchange.rfc8693(config: TokenExchangeConfig, httpClient: Llm4sHttpClient, clock: Clock = Clock.systemUTC(), timeout: FiniteDuration = TokenExchange.DefaultTimeout): () => Result[AccessToken]`
  - `TokenExchange.GrantType`, `TokenExchange.JwtTokenType`, `TokenExchange.DefaultTimeout = 30.seconds`
  - `TokenExchange.provider(config, httpClient, refreshMargin = CachingAccessTokenProvider.DefaultRefreshMargin): AccessTokenProvider` - the caching provider over `rfc8693`.

- [ ] **Step 1: Write the failing spec**

```scala
// modules/core/src/test/scala/org/llm4s/llmconnect/auth/TokenExchangeSpec.scala
package org.llm4s.llmconnect.auth

import org.llm4s.error.{ AuthenticationError, ServiceError }
import org.llm4s.http.{ HttpResponse, MockHttpClient }
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Instant

class TokenExchangeSpec extends AnyWordSpec with Matchers with EitherValues:

  private val now   = Instant.parse("2026-10-04T12:00:00Z")
  private val clock = MutableClock(now)
  private val jwt   = "eyJhbGciOiJFUzI1NiJ9.eyJzdWIiOiJzcGlmZmUifQ.sig"
  private val ok    = HttpResponse(200, """{"access_token":"dbx-1","token_type":"Bearer","expires_in":3600}""")

  private def config(
    clientId: Option[String] = None,
    scope: Option[String] = None,
    audience: Option[String] = None
  ) = TokenExchangeConfig(IdentitySource.Literal(jwt), "https://ws.example/oidc/v1/token", clientId, scope, audience)

  private def form(body: String): Map[String, String] =
    body.split('&').toSeq.map { pair =>
      val Array(k, v) = pair.split("=", 2)
      URLDecoder.decode(k, StandardCharsets.UTF_8) -> URLDecoder.decode(v, StandardCharsets.UTF_8)
    }.toMap

  "TokenExchange.rfc8693" should {
    "post the RFC 8693 form and return the access token with its expiry" in {
      val http  = MockHttpClient(Seq(ok))
      val token = TokenExchange.rfc8693(config(Some("sp-uuid"), Some("all-apis")), http, clock)().value
      token shouldBe AccessToken("dbx-1", now.plusSeconds(3600))
      http.lastUrl shouldBe Some("https://ws.example/oidc/v1/token")
      http.lastHeaders.value("Content-Type") shouldBe "application/x-www-form-urlencoded"
      form(http.lastBody.value) shouldBe Map(
        "grant_type"         -> TokenExchange.GrantType,
        "subject_token"      -> jwt,
        "subject_token_type" -> TokenExchange.JwtTokenType,
        "client_id"          -> "sp-uuid",
        "scope"              -> "all-apis"
      )
    }

    "omit optional fields that are not set" in {
      val http = MockHttpClient(Seq(ok))
      TokenExchange.rfc8693(config(), http, clock)().value
      form(http.lastBody.value).keySet shouldBe Set("grant_type", "subject_token", "subject_token_type")
    }

    "send audience when set" in {
      val http = MockHttpClient(Seq(ok))
      TokenExchange.rfc8693(config(audience = Some("aud-x")), http, clock)().value
      form(http.lastBody.value)("audience") shouldBe "aud-x"
    }

    "accept expires_in given as a string" in {
      val http = MockHttpClient(Seq(HttpResponse(200, """{"access_token":"a","expires_in":"60"}""")))
      TokenExchange.rfc8693(config(), http, clock)().value.expiresAt shouldBe now.plusSeconds(60)
    }

    "map 400, 401 and 403 from the token endpoint to AuthenticationError" in {
      for status <- Seq(400, 401, 403) do
        val http = MockHttpClient(Seq(HttpResponse(status, """{"error":"invalid_grant"}""")))
        TokenExchange.rfc8693(config(), http, clock)().left.value shouldBe an[AuthenticationError]
    }

    "map a 5xx to a retryable ServiceError" in {
      val http  = MockHttpClient(Seq(HttpResponse(503, "busy")))
      val error = TokenExchange.rfc8693(config(), http, clock)().left.value
      error shouldBe a[ServiceError]
      error.asInstanceOf[ServiceError].httpStatus shouldBe 503
    }

    "fail on a malformed body or missing fields" in {
      for body <- Seq("not json", """{"expires_in":60}""", """{"access_token":"a"}""", """{"access_token":"","expires_in":1}""") do
        val http = MockHttpClient(Seq(HttpResponse(200, body)))
        TokenExchange.rfc8693(config(), http, clock)().left.value shouldBe an[AuthenticationError]
    }

    "never echoes the subject token in an error" in {
      val http  = MockHttpClient(Seq(HttpResponse(400, s"""{"error":"invalid_grant","assertion":"$jwt"}""")))
      val error = TokenExchange.rfc8693(config(), http, clock)().left.value
      error.message should not include jwt
    }

    "fail without calling the endpoint when the identity token is missing" in {
      val http = MockHttpClient(Seq(ok))
      val cfg  = config().copy(identityToken = IdentitySource.File(java.nio.file.Path.of("/no/such/svid")))
      TokenExchange.rfc8693(cfg, http, clock)().left.value shouldBe an[AuthenticationError]
      http.postCallCount shouldBe 0
    }

    "keep a literal identity token out of TokenExchangeConfig.toString" in {
      config().toString should not include jwt
    }
  }
```

- [ ] **Step 2: Run it to see it fail**

Run: `sbt "core/testOnly org.llm4s.llmconnect.auth.TokenExchangeSpec"`
Expected: compilation failure - `TokenExchange`, `TokenExchangeConfig` not found.

- [ ] **Step 3: Implement**

```scala
// modules/core/src/main/scala/org/llm4s/llmconnect/auth/TokenExchange.scala
package org.llm4s.llmconnect.auth

import org.llm4s.error.AuthenticationError
import org.llm4s.http.{ HttpResponse, Llm4sHttpClient }
import org.llm4s.llmconnect.provider.HttpErrorMapper
import org.llm4s.types.Result
import org.llm4s.util.Redaction

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import scala.concurrent.duration.*
import scala.util.Try

/**
 * An RFC 8693 token exchange: present `identityToken` at `tokenUrl`, get back a short-lived
 * bearer token. Databricks workload identity federation is one such endpoint
 * (`https://<workspace>/oidc/v1/token`, `scope = all-apis`, `clientId` = the service principal).
 */
final case class TokenExchangeConfig(
  identityToken: IdentitySource,
  tokenUrl: String,
  clientId: Option[String] = None,
  scope: Option[String] = None,
  audience: Option[String] = None
)

object TokenExchange:

  val GrantType: String                 = "urn:ietf:params:oauth:grant-type:token-exchange"
  val JwtTokenType: String              = "urn:ietf:params:oauth:token-type:jwt"
  val DefaultTimeout: FiniteDuration    = 30.seconds
  private val Provider                  = "token-exchange"

  /** One exchange per call: read the identity token, post it, parse the reply. */
  def rfc8693(
    config: TokenExchangeConfig,
    httpClient: Llm4sHttpClient,
    clock: Clock = Clock.systemUTC(),
    timeout: FiniteDuration = DefaultTimeout
  ): () => Result[AccessToken] =
    val subject = IdentityTokenSource.from(config.identityToken)
    () =>
      for
        jwt <- subject.fetch()
        response <- httpClient.post(
          config.tokenUrl,
          Map("Content-Type" -> "application/x-www-form-urlencoded", "Accept" -> "application/json"),
          form(config, jwt),
          timeout
        )
        token <- parse(response, jwt, clock)
      yield token

  /** [[rfc8693]] behind a [[CachingAccessTokenProvider]]. */
  def provider(
    config: TokenExchangeConfig,
    httpClient: Llm4sHttpClient,
    refreshMargin: FiniteDuration = CachingAccessTokenProvider.DefaultRefreshMargin
  ): AccessTokenProvider =
    CachingAccessTokenProvider(rfc8693(config, httpClient), refreshMargin)

  private def form(config: TokenExchangeConfig, jwt: String): String =
    (Seq("grant_type" -> GrantType, "subject_token" -> jwt, "subject_token_type" -> JwtTokenType) ++
      config.clientId.map("client_id" -> _) ++
      config.scope.map("scope" -> _) ++
      config.audience.map("audience" -> _))
      .map((k, v) => s"${encode(k)}=${encode(v)}")
      .mkString("&")

  private def encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

  private def parse(response: HttpResponse, jwt: String, clock: Clock): Result[AccessToken] =
    // Some identity providers quote the rejected assertion back; it must not reach a log line.
    val safeBody = Redaction.truncateForLog(response.body.replace(jwt, "***"), 512)
    response.statusCode match
      case status if status >= 200 && status < 300 =>
        Try(ujson.read(response.body)).toOption
          .flatMap(_.objOpt)
          .flatMap { obj =>
            for
              access <- obj.get("access_token").flatMap(_.strOpt).map(_.trim).filter(_.nonEmpty)
              expiry <- obj.get("expires_in").flatMap(v => v.numOpt.orElse(v.strOpt.flatMap(_.trim.toDoubleOption)))
            yield AccessToken(access, clock.instant().plusSeconds(expiry.toLong))
          }
          .toRight(AuthenticationError(Provider, "token endpoint reply has no access_token or expires_in"))
      case status @ (400 | 401 | 403) =>
        Left(AuthenticationError(Provider, s"token endpoint rejected the identity token (HTTP $status): $safeBody"))
      case status =>
        HttpErrorMapper.mapHttpError(status, safeBody, Provider, response.headers)
```

Add a redacting `toString` to `TokenExchangeConfig` is unnecessary: the case-class `toString` prints `identityToken` via `IdentitySource.toString`, which already redacts a literal.

- [ ] **Step 4: Run it to see it pass**

Run: `sbt "core/testOnly org.llm4s.llmconnect.auth.*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
sbt scalafmtAll
git add modules/core/src/main/scala/org/llm4s/llmconnect/auth/TokenExchange.scala modules/core/src/test/scala/org/llm4s/llmconnect/auth/TokenExchangeSpec.scala
git commit -s -m "feat(core): RFC 8693 token exchange for workload identity

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: The `auth` block in named provider sections

**Files:**
- Create: `modules/core/src/main/scala/org/llm4s/llmconnect/auth/AuthConfig.scala`
- Modify: `modules/core/src/main/scala/org/llm4s/llmconnect/spi/ProviderConfigSpec.scala` (add `authExtras`, `"auth"` in `BuiltinKeys`)
- Modify: `modules/core/src/main/scala/org/llm4s/config/ProvidersConfigModel.scala` (`RawNamedProviderSection.auth`, `NamedProviderConfig.auth`/`withAuth`)
- Modify: `modules/core/src/main/scala/org/llm4s/config/RawProvidersConfigLoader.scala` (read `auth` as a map of scalars)
- Modify: `modules/core/src/main/scala/org/llm4s/config/NamedProviderConfigNormalizer.scala` (raw map -> `AuthConfig`)
- Modify: `modules/core/src/main/scala/org/llm4s/config/NamedProviderValidator.scala` (validation + skip shared credential)
- Modify: `modules/core/src/main/scala/org/llm4s/config/ApiKeySource.scala` (`case WorkloadIdentity(path)`)
- Modify: `modules/core/src/main/scala/org/llm4s/config/ProvidersConfigLoader.scala` (`apiKeySources`)
- Modify: `modules/core/src/main/scala/org/llm4s/llmconnect/spi/ProviderDescriptor.scala` (`ProviderDescriptor.requireApiKey` unchanged; add `ProviderDescriptor.authExtra`)
- Test: `modules/core/src/test/scala/org/llm4s/config/ProviderAuthConfigSpec.scala`
- Test: `modules/config-policy/src/test/scala/org/llm4s/configpolicy/WorkloadIdentityPolicySpec.scala`

**Interfaces:**
- Consumes: `IdentitySource` (Task 1).
- Produces:
  - `final case class AuthConfig(identityToken: IdentitySource, extras: Map[String, String])` in `org.llm4s.llmconnect.auth`, with `def extra(key: String): Option[String]`, `toString` redacting extras' values; `AuthConfig.IdentityTokenFileKey = "identityTokenFile"`, `AuthConfig.IdentityTokenKey = "identityToken"`.
  - `ProviderConfigSpec.authExtras: Seq[ProviderConfigKey]`, `withAuthExtras(...)`, `supportsAuth: Boolean`; `apply(..., authExtras: Seq[ProviderConfigKey] = Seq.empty)`.
  - `ProviderConfigSpec.BuiltinKeys` = `Set("provider", "model", "baseUrl", "apiKey", "headers", "auth")`.
  - `NamedProviderConfig.auth: Option[AuthConfig]`, `withAuth(auth: AuthConfig)`, `withAuth(auth: Option[AuthConfig])`; `NamedProviderConfig.apply(..., auth: Option[AuthConfig] = None)`.
  - `ProviderDescriptor.requireAuthExtra(providerName: String, auth: AuthConfig, key: String): Result[String]`.
  - `ApiKeySource.WorkloadIdentity(path: String)`.

- [ ] **Step 1: Write the failing config spec**

```scala
// modules/core/src/test/scala/org/llm4s/config/ProviderAuthConfigSpec.scala
package org.llm4s.config

import org.llm4s.config.ProvidersConfigModel.{ NamedProviderConfig, ProviderName }
import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.auth.{ AuthConfig, IdentitySource }
import org.llm4s.llmconnect.config.{ ContextWindowResolver, ProviderConfig }
import org.llm4s.llmconnect.spi.{ ProviderConfigKey, ProviderConfigSpec, ProviderDescriptor, ProviderRegistry }
import org.llm4s.llmconnect.{ LLMClient, LlmClientOptions }
import org.llm4s.model.ModelRegistryService
import org.llm4s.testutil.{ FixtureChatConfig, FixtureChatProvider }
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import pureconfig.ConfigSource

import java.nio.file.Path

class ProviderAuthConfigSpec extends AnyWordSpec with Matchers with EitherValues:

  /** A fixture provider that needs an API key unless the section uses auth, and declares auth extras. */
  private object AuthProvider extends ProviderDescriptor:
    val id: ProviderId = ProviderId("authfixture")
    val configSpec: ProviderConfigSpec =
      ProviderConfigSpec
        .apiKeyAndDefaultBaseUrl(FixtureChatProvider.DefaultBaseUrl, Seq("AUTHFIXTURE_API_KEY"))
        .withAuthExtras(
          Seq(
            ProviderConfigKey.required("tokenUrl", "the token endpoint"),
            ProviderConfigKey.optional("clientId", "the client id"),
            ProviderConfigKey.optional("scope", "the scope", default = Some("all-apis"))
          )
        )
    def buildConfig(providerName: String, section: NamedProviderConfig)(using
      ContextWindowResolver
    ): Result[ProviderConfig] =
      FixtureChatConfig.fromValues(section.model.asString, "unused", FixtureChatProvider.DefaultBaseUrl)
    def buildClient(config: ProviderConfig, options: LlmClientOptions)(using ModelRegistryService): Result[LLMClient] =
      FixtureChatProvider.buildClient(config, options)

  private given ProviderRegistry = ProviderRegistry.of(AuthProvider, FixtureChatProvider)

  private val sharedKey = "llm4s.credentials.authfixture.apiKey = \"sk-shared\"\n"

  private def load(hocon: String, name: String = "main"): Result[NamedProviderConfig] =
    ProvidersConfigLoader.loadSections(ConfigSource.string(hocon)).flatMap(_.validated(ProviderName(name)))

  private def section(body: String) = s"llm4s.providers.main { provider = authfixture, model = m, $body }\n"

  "an auth block" should {
    "parse the identity-token file and pass the other keys as auth extras, defaults applied" in {
      val config = load(section("""auth { identityTokenFile = "/var/run/svid", tokenUrl = "https://t" }""")).value
      config.apiKey shouldBe None
      config.auth.value.identityToken shouldBe IdentitySource.File(Path.of("/var/run/svid"))
      config.auth.value.extras shouldBe Map("tokenUrl" -> "https://t", "scope" -> "all-apis")
    }

    "accept a literal identity token" in {
      load(section("""auth { identityToken = "eyJ.x.y", tokenUrl = "https://t" }""")).value.auth.value.identityToken shouldBe
        IdentitySource.Literal("eyJ.x.y")
    }

    "resolves a relative identityTokenFile to an absolute path" in {
      val file = load(section("""auth { identityTokenFile = "secrets/svid", tokenUrl = "https://t" }""")).value
        .auth.value.identityToken
      file shouldBe IdentitySource.File(Path.of("secrets/svid").toAbsolutePath.normalize)
    }

    "treats an unset optional auth key as absent" in {
      val hocon = section("""auth { identityTokenFile = "/s", tokenUrl = "https://t", clientId = ${?LLM4S_TEST_UNSET_VAR} }""")
      load(hocon).value.auth.value.extra("clientId") shouldBe None
    }

    "ignores the shared credential when the section uses auth" in {
      val config = load(sharedKey + section("""auth { identityTokenFile = "/s", tokenUrl = "https://t" }""")).value
      config.apiKey shouldBe None
      config.auth shouldBe defined
    }

    "reject both apiKey and auth in one section" in {
      val error = load(section("""apiKey = "sk", auth { identityTokenFile = "/s", tokenUrl = "https://t" }""")).left.value
      error shouldBe a[ConfigurationError]
      error.message should (include("apiKey") and include("auth"))
    }

    "reject neither or both identity-token keys" in {
      load(section("""auth { tokenUrl = "https://t" }""")).left.value.message should include("identityTokenFile")
      load(section("""auth { identityTokenFile = "/s", identityToken = "x", tokenUrl = "https://t" }""")).left.value
        .message should include("only one")
    }

    "reject a missing required auth extra" in {
      load(section("""auth { identityTokenFile = "/s" }""")).left.value.message should include("tokenUrl")
    }

    "reject auth on a provider that does not support it" in {
      val hocon = """llm4s.providers.main { provider = fixturechat, model = m, apiKey = k,
                    |  auth { identityTokenFile = "/s" } }""".stripMargin
      load(hocon).left.value.message should (include("fixturechat") and include("auth"))
    }

    "reject a nested object inside auth" in {
      load(section("""auth { identityTokenFile = "/s", tokenUrl = { a = b } }""")).isLeft shouldBe true
    }

    "fail only the section that is loaded" in {
      val hocon = section("""auth { identityTokenFile = "/s", tokenUrl = "https://t" }""") +
        """llm4s.providers.broken { provider = authfixture, model = m, auth { tokenUrl = "x" } }"""
      load(hocon).isRight shouldBe true
      load(hocon, "broken").isLeft shouldBe true
    }

    "redact auth extras and a literal token in toString" in {
      val config = load(section("""auth { identityToken = "eyJ.secret", tokenUrl = "https://secret-host" }""")).value
      config.toString should not include "eyJ.secret"
      config.toString should not include "secret-host"
    }
  }

  "apiKeySources" should {
    "report a section with auth as WorkloadIdentity" in {
      val hocon = section("""auth { identityTokenFile = "/s", tokenUrl = "https://t" }""")
      ProvidersConfigLoader.loadSections(ConfigSource.string(hocon)).value.apiKeySources shouldBe
        Map(ProviderName("main") -> ApiKeySource.WorkloadIdentity("llm4s.providers.main.auth"))
    }
  }
```

- [ ] **Step 2: Write the failing config-policy spec**

```scala
// modules/config-policy/src/test/scala/org/llm4s/configpolicy/WorkloadIdentityPolicySpec.scala
package org.llm4s.configpolicy

import org.llm4s.config.ApiKeySource
import org.llm4s.config.ProvidersConfigModel.ProviderName
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class WorkloadIdentityPolicySpec extends AnyWordSpec with Matchers:
  "ConfigPolicyEngine.checkApiKeySources" should {
    "accept a section that authenticates with workload identity" in {
      val sources = Map(ProviderName("dbx") -> ApiKeySource.WorkloadIdentity("llm4s.providers.dbx.auth"))
      val policy  = ConfigPolicy().withOwnApiKeyRequired(CatalogEnvironment.Prod)
      ConfigPolicyEngine.checkApiKeySources(sources, policy, CatalogEnvironment.Prod) shouldBe Nil
    }
  }
```

(`ConfigPolicyEngine.checkApiKeySources` is at `ConfigPolicy.scala:177`; `OwnApiKeyPolicySpec` is the existing spec for the same check. If `ConfigPolicy()` has no no-argument `apply`, start from `ConfigPolicy.prodSafeDefaults` as `OwnApiKeyPolicySpec` does.)

- [ ] **Step 3: Run both to see them fail**

Run: `sbt "core/testOnly org.llm4s.config.ProviderAuthConfigSpec" "configPolicy/testOnly org.llm4s.configpolicy.WorkloadIdentityPolicySpec"`
Expected: compilation failure - `withAuthExtras`, `AuthConfig`, `ApiKeySource.WorkloadIdentity` not found. (If the sbt project id for config-policy is not `configPolicy`, find it with `grep -n "config-policy" build.sbt`.)

- [ ] **Step 4: Add `AuthConfig`**

```scala
// modules/core/src/main/scala/org/llm4s/llmconnect/auth/AuthConfig.scala
package org.llm4s.llmconnect.auth

/**
 * A named provider section's `auth` block: the workload's identity token, plus the keys the
 * provider declares in `ProviderConfigSpec.authExtras` (a token URL, a federation rule id, ...),
 * resolved by validation. Values are redacted in `toString`.
 */
final case class AuthConfig(identityToken: IdentitySource, extras: Map[String, String]):
  def extra(key: String): Option[String] = extras.get(key)
  override def toString: String =
    s"AuthConfig($identityToken, ${extras.keys.toSeq.sorted.map(k => s"$k -> ***").mkString("Map(", ", ", ")")})"

object AuthConfig:
  val IdentityTokenFileKey: String = "identityTokenFile"
  val IdentityTokenKey: String     = "identityToken"
  val ReservedKeys: Set[String]    = Set(IdentityTokenFileKey, IdentityTokenKey)
```

- [ ] **Step 5: Extend `ProviderConfigSpec`**

In `ProviderConfigSpec.scala`: add the constructor field `authExtras: Seq[ProviderConfigKey]` after `apiKeyEnv`, the setter and query, and the `apply` parameter; add `"auth"` to `BuiltinKeys`. Add a Scaladoc `@param authExtras` line: "the keys this provider reads from a section's `auth` block besides `identityTokenFile`/`identityToken`; non-empty means the provider supports workload-identity auth, and a section with `auth` then needs no `apiKey`."

```scala
final case class ProviderConfigSpec private (
  requiresApiKey: Boolean,
  requiresBaseUrl: Boolean,
  defaultBaseUrl: Option[String],
  baseUrlExample: String,
  baseUrlEnv: Option[String],
  extras: Seq[ProviderConfigKey],
  apiKeyEnv: Seq[String],
  authExtras: Seq[ProviderConfigKey]
):
  // ... existing setters unchanged ...
  def withAuthExtras(authExtras: Seq[ProviderConfigKey]): ProviderConfigSpec = copy(authExtras = authExtras)

  /** Whether a section for this provider may carry an `auth` block. */
  def supportsAuth: Boolean = authExtras.nonEmpty
```

```scala
  def apply(
    requiresApiKey: Boolean = false,
    requiresBaseUrl: Boolean = false,
    defaultBaseUrl: Option[String] = None,
    baseUrlExample: String = "e.g. https://api.example.com/",
    baseUrlEnv: Option[String] = None,
    extras: Seq[ProviderConfigKey] = Seq.empty,
    apiKeyEnv: Seq[String] = Seq.empty,
    authExtras: Seq[ProviderConfigKey] = Seq.empty
  ): ProviderConfigSpec =
    new ProviderConfigSpec(requiresApiKey, requiresBaseUrl, defaultBaseUrl, baseUrlExample, baseUrlEnv, extras, apiKeyEnv, authExtras)

  val BuiltinKeys: Set[String] = Set("provider", "model", "baseUrl", "apiKey", "headers", "auth")
```

- [ ] **Step 6: Carry `auth` through the model**

In `ProvidersConfigModel.scala`:

```scala
  final private[llm4s] case class RawNamedProviderSection(
    provider: Option[String],
    model: Option[String],
    baseUrl: Option[String],
    apiKey: Option[String],
    headers: Option[Map[String, String]] = None,
    extras: Map[String, String] = Map.empty,
    auth: Option[Map[String, String]] = None
  )
```

`NamedProviderConfig`: add constructor field `auth: Option[AuthConfig]` (last), the two `withAuth` setters, include `auth` in `toString` (`AuthConfig.toString` redacts), document `@param auth` ("the section's workload-identity block, if any; validation guarantees it is never set together with `apiKey`"), and add `auth: Option[AuthConfig] = None` to `NamedProviderConfig.apply`, passed to `new`. Import `org.llm4s.llmconnect.auth.AuthConfig`.

- [ ] **Step 7: Read `auth` in the raw loader**

In `RawProvidersConfigLoader.namedProviderSectionReader`, after `builtins <- builtinFieldsReader.from(cursor)`, read the optional `auth` object as scalars (the extras loop already skips it, since `auth` is now in `BuiltinKeys`):

```scala
        authCursor = objCursor.atKeyOrUndefined("auth")
        auth <-
          if authCursor.isUndefined || authCursor.isNull then Right(None)
          else
            authCursor.asObjectCursor.flatMap { authObj =>
              authObj.objValue.keySet().asScala.toList.sorted
                .foldLeft[Either[ConfigReaderFailures, Map[String, String]]](Right(Map.empty)) { case (accEither, key) =>
                  for
                    acc       <- accEither
                    keyCursor <- authObj.atKey(key)
                    value <-
                      if keyCursor.isNull then Right(None)
                      else
                        PureConfigReader[String].from(keyCursor).map(Some(_)).left.flatMap(_ =>
                          keyCursor.failed(UserValidationFailed(s"auth key '${keyCursor.path}' must be a string, number or boolean"))
                        )
                  yield value.fold(acc)(acc.updated(key, _))
                }
                .map(Some(_))
            }
```

and yield `builtins.copy(extras = extras, auth = auth)`. (An unset `${?VAR}` is absent from the object, so it never reaches this loop - that is Review Focus 3.)

- [ ] **Step 8: Normalise the auth block**

In `NamedProviderConfigNormalizer.normalize`, build `AuthConfig` from `section.auth` and include it in the for-comprehension:

```scala
    val authConfig: Result[Option[AuthConfig]] =
      section.auth match
        case None => Right(None)
        case Some(raw) =>
          val values = raw.collect { case (k, v) if v.trim.nonEmpty => k -> v.trim }
          val path   = s"llm4s.providers.${providerName.asName}.auth"
          (values.get(AuthConfig.IdentityTokenFileKey), values.get(AuthConfig.IdentityTokenKey)) match
            case (Some(file), None) =>
              Right(Some(AuthConfig(IdentitySource.File(Path.of(file).toAbsolutePath.normalize), values -- AuthConfig.ReservedKeys)))
            case (None, Some(token)) =>
              Right(Some(AuthConfig(IdentitySource.Literal(token), values -- AuthConfig.ReservedKeys)))
            case (None, None) =>
              Left(ConfigurationError(s"$path needs ${AuthConfig.IdentityTokenFileKey} (or ${AuthConfig.IdentityTokenKey})"))
            case (Some(_), Some(_)) =>
              Left(ConfigurationError(s"$path sets both ${AuthConfig.IdentityTokenFileKey} and ${AuthConfig.IdentityTokenKey}; set only one"))
```

`for id <- providerType; model <- modelName; auth <- authConfig yield NamedProviderConfig(..., auth = auth)`. Imports: `java.nio.file.Path`, `org.llm4s.llmconnect.auth.{ AuthConfig, IdentitySource }`.

- [ ] **Step 9: Validate**

In `NamedProviderConfigValidator.validate`, skip the shared-credential fallback when the section uses auth, and reject `apiKey` + `auth`:

```scala
    for
      normalized <- NamedProviderConfigNormalizer.normalize(providerName, section)
      _ <- Either.cond(
        normalized.auth.isEmpty || normalized.apiKey.isEmpty,
        (),
        ConfigurationError(s"$sectionPath sets both apiKey and auth; a section authenticates one way - remove one")
      )
      descriptor <- registry.resolve(normalized.provider, Some(s"$sectionPath.provider"))
      resolved <-
        if normalized.auth.isDefined then Right(None)
        else credentials.resolve(normalized.apiKey.map(_.asKey), s"$sectionPath.apiKey", normalized.provider)
      validated <- NamedProviderSectionValidator.validate(
        providerName,
        descriptor,
        if normalized.auth.isDefined then normalized else normalized.withApiKey(resolved.map(key => ApiKey(key.value)))
      )
    yield
      resolved.foreach(SharedCredentials.logSource(sectionPath, _))
      validated
```

In `NamedProviderSectionValidator`:

1. `missingBuiltins`: change the apiKey condition to `spec.requiresApiKey && normalized.apiKey.isEmpty && normalized.auth.isEmpty`.
2. `specError`: also flag `spec.authExtras` names in `AuthConfig.ReservedKeys` ("declares auth key(s) ... which are reserved for the identity token").
3. Add auth resolution, run in `validateWithWarnings` after `resolveExtras`:

```scala
  /** The auth block's provider keys after defaults, and what is wrong with them. */
  private def resolveAuth(
    name: String,
    id: String,
    spec: ProviderConfigSpec,
    auth: AuthConfig
  ): ResolvedExtras =
    val path = s"llm4s.providers.$name.auth"
    if !spec.supportsAuth then
      ResolvedExtras(Map.empty, Seq(s"  - auth: provider = $id does not support an auth block; remove $path"), Nil)
    else
      val outcomes = spec.authExtras.map { key =>
        auth.extras.get(key.name).orElse(key.default) match
          case some @ Some(_)       => key.name -> KeyOutcome(some)
          case None if key.required => key.name -> KeyOutcome(None, problems = Seq(s"  - auth.${key.name}: ${key.description} (set $path.${key.name})"))
          case None                 => key.name -> KeyOutcome(None)
      }
      val unknown = auth.extras.keySet.diff(spec.authExtras.map(_.name).toSet).toSeq.sorted
      ResolvedExtras(
        outcomes.flatMap((k, o) => o.value.map(k -> _)).toMap,
        outcomes.flatMap(_._2.problems),
        Option.when(unknown.nonEmpty)(
          s"$path has unknown key(s) ${unknown.mkString(", ")}, which are ignored; provider = $id accepts " +
            (AuthConfig.ReservedKeys.toSeq.sorted ++ spec.authExtras.map(_.name)).mkString(", ")
        ).toSeq
      )
```

and in `validateWithWarnings`:

```scala
          val extras   = resolveExtras(name, id, spec, normalized)
          val auth     = normalized.auth.map(a => a -> resolveAuth(name, id, spec, a))
          val problems = missingBuiltins(name, descriptor, normalized) ++ extras.problems ++ auth.toSeq.flatMap(_._2.problems)
          if problems.nonEmpty then Left(/* unchanged message */)
          else
            val withAuth = auth.fold(normalized)((a, resolved) => normalized.withAuth(a.copy(extras = resolved.values)))
            Right((withAuth.withExtras(extras.values), extras.warnings ++ auth.toSeq.flatMap(_._2.warnings)))
```

(The "does not support" problem lands under the existing "is missing required fields" heading; change that heading to `s"Provider '$name' (provider = $id) is misconfigured:\n"` and update any existing spec asserting the old wording - `grep -rn "is missing required fields" modules/*/src/test` lists them.)

- [ ] **Step 10: `ApiKeySource` and `apiKeySources`**

```scala
  /**
   * The section authenticates with workload identity (an `auth` block), so it uses no API key.
   *
   * @param path the block's path, e.g. `llm4s.providers.dbx.auth`
   */
  case WorkloadIdentity(path: String)
```

In `ProviderSections.apiKeySources`:

```scala
        val source =
          if raw.auth.isDefined then ApiKeySource.WorkloadIdentity(s"llm4s.providers.${name.asName}.auth")
          else if raw.apiKey.exists(_.trim.nonEmpty) then ApiKeySource.Section(s"llm4s.providers.${name.asName}.apiKey")
          else ApiKeySource.Credentials(SharedCredentials.apiKeyPath(descriptor.id))
```

`checkApiKeySources` only flags `Credentials`, so it needs no change; confirm any exhaustive `match` on `ApiKeySource` elsewhere compiles (`grep -rn "ApiKeySource\." modules --include='*.scala'`).

- [ ] **Step 11: `ProviderDescriptor.requireAuthExtra`**

```scala
  /**
   * Reads a key the provider declares in `ProviderConfigSpec.authExtras` as required. Validation
   * has already enforced it; this turns a section built in code without it into an error rather
   * than an exception.
   */
  def requireAuthExtra(providerName: String, auth: AuthConfig, key: String): Result[String] =
    auth.extra(key).toRight(ConfigurationError(s"Configured provider '$providerName' is missing auth.$key (llm4s.providers.$providerName.auth.$key)"))
```

- [ ] **Step 12: Run the specs and core's suite**

Run: `sbt "core/testOnly org.llm4s.config.* org.llm4s.llmconnect.*" "configPolicy/testOnly org.llm4s.configpolicy.*"`
Expected: PASS, including every pre-existing config spec.

- [ ] **Step 13: Commit**

```bash
sbt scalafmtAll
git add modules/core modules/config-policy
git commit -s -m "feat(config): auth block for workload identity in named provider sections

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: Test fakes in `llm4s-provider-testkit`

**Files:**
- Create: `modules/provider-testkit/src/main/scala/org/llm4s/testkit/TestJwt.scala`
- Create: `modules/provider-testkit/src/main/scala/org/llm4s/testkit/FakeTokenExchangeServer.scala`
- Test: `modules/provider-testkit/src/test/scala/org/llm4s/testkit/FakeTokenExchangeServerSpec.scala`

**Interfaces:**
- Consumes: `LocalProviderTestServer.openAICompletion(content, model)`, `LocalProviderTestServer.openAISseBody(chunks, model)` (existing).
- Produces:
  - `object TestJwt { def es256(subject: String, audience: String, lifetime: FiniteDuration = 5.minutes, issuer: String = "https://spire.llm4s.test"): String }` - a signed compact JWT (JDK `KeyPairGenerator("EC")` P-256, `SHA256withECDSA` in P1363 format).
  - `final class FakeTokenExchangeServer` with: `baseUrl: String`; `exchanges: Seq[Map[String, String]]` (form fields, or JSON fields for the Anthropic grant); `apiAuthorizations: Seq[String]`; `issuedTokens: Seq[String]`; `setExpiresIn(seconds: Long): Unit` (default 3600); `rejectNextApiCalls(n: Int): Unit`; `setSubjectValidator(f: String => Either[String, Unit]): Unit` (default accepts anything non-blank); `close(): Unit`.
  - `object FakeTokenExchangeServer { val TokenPath = "/oidc/v1/token"; val ChatPath = "/serving-endpoints/chat/completions"; val ModelsPath = "/serving-endpoints/models"; val AnthropicTokenPath = "/v1/oauth/token"; val AnthropicMessagesPath = "/v1/messages"; val OpenAIChatPath = "/v1/chat/completions"; def withServer(test: FakeTokenExchangeServer => Any): Unit; def start(): FakeTokenExchangeServer }`.
  - Token naming: issued tokens are `t1`, `t2`, ... in issue order. API endpoints accept only the **latest** issued token (an earlier one is treated as expired) and return 401 `{"error":{"message":"invalid token"}}` otherwise.

- [ ] **Step 1: Write the failing spec**

```scala
// modules/provider-testkit/src/test/scala/org/llm4s/testkit/FakeTokenExchangeServerSpec.scala
package org.llm4s.testkit

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.net.URI
import java.net.http.{ HttpClient, HttpRequest, HttpResponse }

class FakeTokenExchangeServerSpec extends AnyWordSpec with Matchers:

  private val http = HttpClient.newHttpClient()

  private def post(url: String, body: String, headers: (String, String)*): HttpResponse[String] =
    val builder = HttpRequest.newBuilder(URI.create(url)).POST(HttpRequest.BodyPublishers.ofString(body))
    headers.foreach((k, v) => builder.header(k, v))
    http.send(builder.build(), HttpResponse.BodyHandlers.ofString())

  "FakeTokenExchangeServer" should {
    "issue t1, t2 for RFC 8693 exchanges and record the form" in FakeTokenExchangeServer.withServer { fake =>
      val form = "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Atoken-exchange&subject_token=abc&subject_token_type=x"
      val r1   = post(fake.baseUrl + FakeTokenExchangeServer.TokenPath, form, "Content-Type" -> "application/x-www-form-urlencoded")
      r1.statusCode shouldBe 200
      r1.body should include("\"access_token\":\"t1\"")
      fake.exchanges.head("subject_token") shouldBe "abc"
      post(fake.baseUrl + FakeTokenExchangeServer.TokenPath, form).body should include("t2")
    }

    "accept only the latest token on the chat endpoint" in FakeTokenExchangeServer.withServer { fake =>
      val form = "grant_type=g&subject_token=abc&subject_token_type=x"
      post(fake.baseUrl + FakeTokenExchangeServer.TokenPath, form)
      post(fake.baseUrl + FakeTokenExchangeServer.ChatPath, "{}", "Authorization" -> "Bearer t1").statusCode shouldBe 200
      post(fake.baseUrl + FakeTokenExchangeServer.TokenPath, form)
      post(fake.baseUrl + FakeTokenExchangeServer.ChatPath, "{}", "Authorization" -> "Bearer t1").statusCode shouldBe 401
      post(fake.baseUrl + FakeTokenExchangeServer.ChatPath, "{}", "Authorization" -> "Bearer t2").statusCode shouldBe 200
    }

    "reject the next N API calls when told to" in FakeTokenExchangeServer.withServer { fake =>
      post(fake.baseUrl + FakeTokenExchangeServer.TokenPath, "subject_token=abc")
      fake.rejectNextApiCalls(1)
      post(fake.baseUrl + FakeTokenExchangeServer.ChatPath, "{}", "Authorization" -> "Bearer t1").statusCode shouldBe 401
      post(fake.baseUrl + FakeTokenExchangeServer.ChatPath, "{}", "Authorization" -> "Bearer t1").statusCode shouldBe 200
    }

    "refuse a subject token the validator rejects" in FakeTokenExchangeServer.withServer { fake =>
      fake.setSubjectValidator(token => Either.cond(token == "good", (), "bad audience"))
      post(fake.baseUrl + FakeTokenExchangeServer.TokenPath, "subject_token=bad").statusCode shouldBe 400
      fake.issuedTokens shouldBe empty
    }

    "serve the Anthropic jwt-bearer grant and messages" in FakeTokenExchangeServer.withServer { fake =>
      val grant = """{"grant_type":"urn:ietf:params:oauth:grant-type:jwt-bearer","assertion":"abc","federation_rule_id":"fdrl_1","organization_id":"org"}"""
      post(fake.baseUrl + FakeTokenExchangeServer.AnthropicTokenPath, grant, "Content-Type" -> "application/json").body should include("t1")
      fake.exchanges.head("assertion") shouldBe "abc"
      post(fake.baseUrl + FakeTokenExchangeServer.AnthropicMessagesPath, "{}", "Authorization" -> "Bearer t1").body should include("\"type\":\"message\"")
    }
  }

  "TestJwt.es256" should {
    "produce a three-part compact JWT carrying the claims" in {
      val jwt     = TestJwt.es256("spiffe://llm4s.test/app", "databricks")
      val parts   = jwt.split('.')
      parts.length shouldBe 3
      val payload = new String(java.util.Base64.getUrlDecoder.decode(parts(1)))
      payload should (include("spiffe://llm4s.test/app") and include("databricks"))
    }
  }
```

- [ ] **Step 2: Run it to see it fail**

Run: `sbt "providerTestkit/testOnly org.llm4s.testkit.FakeTokenExchangeServerSpec"`
Expected: compilation failure. (If the testkit has no `src/test` yet, also add `Deps.scalatest % Test` to its `libraryDependencies` if it is not already there - check `build.sbt:709-725`.)

- [ ] **Step 3: Implement `TestJwt`**

```scala
// modules/provider-testkit/src/main/scala/org/llm4s/testkit/TestJwt.scala
package org.llm4s.testkit

import java.nio.charset.StandardCharsets
import java.security.{ KeyPairGenerator, Signature }
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.{ Base64, UUID }
import scala.concurrent.duration.*

/** Signed JWTs for tests that need a realistic subject token, using the JDK only. */
object TestJwt:

  private val keys =
    val generator = KeyPairGenerator.getInstance("EC")
    generator.initialize(new ECGenParameterSpec("secp256r1"))
    generator.generateKeyPair()

  private def b64(bytes: Array[Byte]): String = Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)

  /** An ES256 JWT with `sub`, `aud`, `iss`, `iat`, `exp` and a unique `jti`. */
  def es256(
    subject: String,
    audience: String,
    lifetime: FiniteDuration = 5.minutes,
    issuer: String = "https://spire.llm4s.test"
  ): String =
    val now     = Instant.now().getEpochSecond
    val header  = b64("""{"alg":"ES256","typ":"JWT"}""".getBytes(StandardCharsets.UTF_8))
    val payload = b64(
      ujson.Obj(
        "sub" -> subject,
        "aud" -> ujson.Arr(audience),
        "iss" -> issuer,
        "iat" -> now,
        "exp" -> (now + lifetime.toSeconds),
        "jti" -> UUID.randomUUID().toString
      ).render().getBytes(StandardCharsets.UTF_8)
    )
    val signer = Signature.getInstance("SHA256withECDSAinP1363Format")
    signer.initSign(keys.getPrivate)
    signer.update(s"$header.$payload".getBytes(StandardCharsets.US_ASCII))
    s"$header.$payload.${b64(signer.sign())}"
```

- [ ] **Step 4: Implement `FakeTokenExchangeServer`**

```scala
// modules/provider-testkit/src/main/scala/org/llm4s/testkit/FakeTokenExchangeServer.scala
package org.llm4s.testkit

import com.sun.net.httpserver.{ HttpExchange, HttpServer }

import java.net.{ InetSocketAddress, URLDecoder }
import java.nio.charset.StandardCharsets
import java.util.concurrent.{ ExecutorService, Executors }
import java.util.concurrent.atomic.{ AtomicInteger, AtomicLong }
import scala.collection.mutable
import scala.util.Try

/**
 * A local identity provider plus a protected API, for testing workload-identity auth without a
 * network: an RFC 8693 token endpoint (Databricks' `/oidc/v1/token`), Anthropic's `jwt-bearer`
 * grant, and OpenAI-format and Anthropic-format endpoints that accept only the most recently
 * issued token. Tokens are `t1`, `t2`, ... in issue order.
 */
final class FakeTokenExchangeServer private (server: HttpServer, executor: ExecutorService):
  import FakeTokenExchangeServer.*

  val baseUrl: String = s"http://localhost:${server.getAddress.getPort}"

  private val lock          = new Object
  private val exchangeLog   = mutable.Buffer.empty[Map[String, String]]
  private val authorizations = mutable.Buffer.empty[String]
  private val issued        = mutable.Buffer.empty[String]
  private val expiresIn     = new AtomicLong(3600)
  private val toReject      = new AtomicInteger(0)
  @volatile private var validator: String => Either[String, Unit] =
    token => Either.cond(token.trim.nonEmpty, (), "empty subject token")

  def exchanges: Seq[Map[String, String]]               = lock.synchronized(exchangeLog.toSeq)
  def apiAuthorizations: Seq[String]                     = lock.synchronized(authorizations.toSeq)
  def issuedTokens: Seq[String]                          = lock.synchronized(issued.toSeq)
  def setExpiresIn(seconds: Long): Unit                  = expiresIn.set(seconds)
  def rejectNextApiCalls(n: Int): Unit                   = toReject.set(n)
  def setSubjectValidator(f: String => Either[String, Unit]): Unit = validator = f
  def close(): Unit =
    server.stop(0)
    executor.shutdownNow(): Unit

  server.createContext(TokenPath, ex => exchange(ex, formFields(body(ex)), "subject_token"))
  server.createContext(AnthropicTokenPath, ex => exchange(ex, jsonFields(body(ex)), "assertion"))
  server.createContext(ChatPath, ex => api(ex, openAIReply))
  server.createContext(OpenAIChatPath, ex => api(ex, openAIReply))
  server.createContext(ModelsPath, ex => api(ex, _ => (200, "application/json", """{"data":[{"id":"fake-model"}]}""")))
  server.createContext(AnthropicMessagesPath, ex => api(ex, _ => (200, "application/json", AnthropicMessage)))

  private def exchange(ex: HttpExchange, fields: Map[String, String], subjectField: String): Unit =
    lock.synchronized(exchangeLog += fields)
    validator(fields.getOrElse(subjectField, "")) match
      case Left(reason) => send(ex, 400, "application/json", ujson.Obj("error" -> "invalid_grant", "error_description" -> reason).render())
      case Right(()) =>
        val token = lock.synchronized { issued += s"t${issued.size + 1}"; issued.last }
        send(ex, 200, "application/json",
          ujson.Obj("access_token" -> token, "token_type" -> "Bearer", "expires_in" -> expiresIn.get).render())

  private def api(ex: HttpExchange, reply: String => (Int, String, String)): Unit =
    val auth = Option(ex.getRequestHeaders.getFirst("Authorization")).getOrElse("")
    val text = body(ex)
    lock.synchronized(authorizations += auth)
    val latest   = lock.synchronized(issued.lastOption)
    val rejected = toReject.getAndUpdate(n => math.max(0, n - 1)) > 0
    if rejected || latest.forall(t => auth != s"Bearer $t") then
      send(ex, 401, "application/json", """{"error":{"type":"authentication_error","message":"invalid token"}}""")
    else
      val (status, contentType, out) = reply(text)
      send(ex, status, contentType, out)

  private def openAIReply(request: String): (Int, String, String) =
    if request.contains("\"stream\":true") then (200, "text/event-stream", LocalProviderTestServer.openAISseBody(Seq("hello")))
    else (200, "application/json", LocalProviderTestServer.openAICompletion("hello"))

object FakeTokenExchangeServer:
  val TokenPath: String             = "/oidc/v1/token"
  val ChatPath: String              = "/serving-endpoints/chat/completions"
  val ModelsPath: String            = "/serving-endpoints/models"
  val OpenAIChatPath: String        = "/v1/chat/completions"
  val AnthropicTokenPath: String    = "/v1/oauth/token"
  val AnthropicMessagesPath: String = "/v1/messages"

  private val AnthropicMessage =
    """{"id":"msg_1","type":"message","role":"assistant","model":"claude-test","content":[{"type":"text","text":"hello"}],""" +
      """"stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":1,"output_tokens":1}}"""

  def start(): FakeTokenExchangeServer =
    val server   = HttpServer.create(new InetSocketAddress("localhost", 0), 0)
    val executor = Executors.newVirtualThreadPerTaskExecutor()
    server.setExecutor(executor)
    val fake = new FakeTokenExchangeServer(server, executor)
    server.start()
    fake

  /** Starts a server, runs `test` with it, and stops it - also when `test` throws. */
  def withServer(test: FakeTokenExchangeServer => Any): Unit =
    val fake    = start()
    val outcome = Try(test(fake))
    fake.close()
    outcome.fold(error => throw error, _ => ())

  private def body(ex: HttpExchange): String =
    new String(ex.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)

  private def formFields(text: String): Map[String, String] =
    text.split('&').toSeq.filter(_.contains('=')).map { pair =>
      val Array(k, v) = pair.split("=", 2)
      URLDecoder.decode(k, StandardCharsets.UTF_8) -> URLDecoder.decode(v, StandardCharsets.UTF_8)
    }.toMap

  private def jsonFields(text: String): Map[String, String] =
    Try(ujson.read(text).obj.collect { case (k, ujson.Str(v)) => k -> v }.toMap).getOrElse(Map.empty)

  private def send(ex: HttpExchange, status: Int, contentType: String, out: String): Unit =
    val bytes = out.getBytes(StandardCharsets.UTF_8)
    ex.getResponseHeaders.set("Content-Type", contentType)
    ex.sendResponseHeaders(status, bytes.length.toLong)
    ex.getResponseBody.write(bytes)
    ex.close()
```

Check `LocalProviderTestServer.openAISseBody`/`openAICompletion` signatures (`LocalProviderTestServer.scala:95,120`) and adapt the calls if the parameter lists differ. Note: the `throw error` in `withServer` mirrors `LocalProviderTestServer.withServer`, which rethrows a failed test's exception by design.

- [ ] **Step 5: Run it to see it pass**

Run: `sbt "providerTestkit/testOnly org.llm4s.testkit.FakeTokenExchangeServerSpec"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
sbt scalafmtAll
git add modules/provider-testkit build.sbt
git commit -s -m "feat(testkit): fake token-exchange server and test JWTs for workload identity

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: `openai-compatible` - token exchange, refresh and retry

**Files:**
- Modify: `modules/openai-compatible/src/main/scala/org/llm4s/llmconnect/config/OpenAICompatibleConfig.scala` (add `tokenExchange: Option[TokenExchangeConfig]`)
- Modify: `modules/openai-compatible/src/main/scala/org/llm4s/llmconnect/provider/OpenAICompatibleProvider.scala` (`authExtras`, `buildConfig`)
- Modify: `modules/openai-compatible/src/main/scala/org/llm4s/llmconnect/provider/OpenAICompatibleClient.scala` (`Credential`, `Settings.credential`, headers, retry)
- Modify: `DeepSeekClient.scala`, `ZaiClient.scala`, `OpenRouterClient.scala`, `CohereClient.scala`, `MistralClient.scala` (same dir: `apiKey = Some(k)` -> `credential = Credential.Static(k)`)
- Modify: `modules/openai-compatible/src/main/scala/org/llm4s/config/OpenAICompatibleModelListers.scala` (token for `/models`)
- Test: `modules/openai-compatible/src/test/scala/org/llm4s/llmconnect/provider/OpenAICompatibleWorkloadIdentitySpec.scala`
- Modify tests: `Llm4sOpenAICompatibleModuleSpec.scala` (auth round trip) and any spec constructing `Settings(..., apiKey = ...)` (`grep -rln "Settings(" modules/openai-compatible/src/test`)

**Interfaces:**
- Consumes: `TokenExchangeConfig`, `TokenExchange.provider`, `TokenExchange.rfc8693`, `AccessTokenProvider` (Task 2); `AuthConfig`, `ProviderConfigSpec.withAuthExtras`, `ProviderDescriptor.requireAuthExtra` (Task 3); `FakeTokenExchangeServer` (Task 4); `ProviderModuleChecks.assertBuildsClient`, `ProviderTestConfig.loadSection` (existing testkit).
- Produces:
  - `enum OpenAICompatibleClient.Credential { case Anonymous; case Static(key: String); case Dynamic(provider: AccessTokenProvider) }` (`toString` redacts `Static`).
  - `OpenAICompatibleClient.Settings(providerName, displayName, model, baseUrl, credential: Credential, contextWindow, reserveCompletion)`.
  - `OpenAICompatibleProvider.TokenUrlKey = "tokenUrl"`, `ClientIdKey = "clientId"`, `ScopeKey = "scope"`, `AudienceKey = "audience"`.

- [ ] **Step 1: Write the failing spec**

```scala
// modules/openai-compatible/src/test/scala/org/llm4s/llmconnect/provider/OpenAICompatibleWorkloadIdentitySpec.scala
package org.llm4s.llmconnect.provider

import org.llm4s.config.OpenAICompatibleModelLister
import org.llm4s.error.AuthenticationError
import org.llm4s.http.Llm4sHttpClient
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, UserMessage }
import org.llm4s.testkit.{ FakeTokenExchangeServer, ProviderModuleChecks, ProviderTestConfig, TestJwt }
import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.{ Files, Path }
import java.util.concurrent.{ Callable, Executors }
import scala.jdk.CollectionConverters.*

class OpenAICompatibleWorkloadIdentitySpec extends AnyWordSpec with Matchers with EitherValues with ProviderModuleChecks:

  private given ProviderRegistry = ProviderRegistry.default

  /** The section `llm4s.providers.main` with `body`, as validation leaves it. */
  private def sectionResult(body: String): Result[NamedProviderConfig] =
    ProviderTestConfig.loadSection("main", s"llm4s.providers.main {\n$body\n}")

  private def sectionOf(body: String): NamedProviderConfig =
    sectionResult(body).fold(error => fail(error.message), identity)

  private val conversation = Conversation(Seq(UserMessage("hi")))

  private def svidFile(jwt: String): Path =
    val f = Files.createTempFile("svid", ".jwt")
    f.toFile.deleteOnExit()
    Files.writeString(f, jwt)

  private def client(fake: FakeTokenExchangeServer, svid: Path): LLMClient =
    val section = sectionOf(
      s"""provider = "openai-compatible"
         |model    = "databricks-model"
         |baseUrl  = "${fake.baseUrl}/serving-endpoints"
         |auth {
         |  identityTokenFile = "$svid"
         |  tokenUrl = "${fake.baseUrl}${FakeTokenExchangeServer.TokenPath}"
         |  clientId = "sp-uuid"
         |  scope    = "all-apis"
         |}""".stripMargin
    )
    assertBuildsClient(OpenAICompatibleProvider, section)

  "an openai-compatible section with auth" should {
    "exchange the SVID and send the access token on complete and stream" in FakeTokenExchangeServer.withServer { fake =>
      val jwt = TestJwt.es256("spiffe://llm4s.test/app", "databricks")
      val c   = client(fake, svidFile(jwt))
      c.complete(conversation, CompletionOptions()).isRight shouldBe true
      assertStreams(c)
      fake.exchanges.head("subject_token") shouldBe jwt
      fake.exchanges.head("client_id") shouldBe "sp-uuid"
      fake.exchanges.head("scope") shouldBe "all-apis"
      fake.apiAuthorizations.distinct shouldBe Seq("Bearer t1")
      fake.exchanges.size shouldBe 1
    }

    "exchange again once the token has expired" in FakeTokenExchangeServer.withServer { fake =>
      fake.setExpiresIn(0)
      val c = client(fake, svidFile(TestJwt.es256("spiffe://llm4s.test/app", "databricks")))
      c.complete(conversation, CompletionOptions()).isRight shouldBe true
      c.complete(conversation, CompletionOptions()).isRight shouldBe true
      fake.apiAuthorizations shouldBe Seq("Bearer t1", "Bearer t2")
    }

    "refresh and retry once on a 401" in FakeTokenExchangeServer.withServer { fake =>
      val c = client(fake, svidFile(TestJwt.es256("spiffe://llm4s.test/app", "databricks")))
      c.complete(conversation, CompletionOptions()).isRight shouldBe true
      fake.rejectNextApiCalls(1)
      c.complete(conversation, CompletionOptions()).isRight shouldBe true
      fake.apiAuthorizations shouldBe Seq("Bearer t1", "Bearer t1", "Bearer t2")
    }

    "refresh and retry once on a 401 while streaming" in FakeTokenExchangeServer.withServer { fake =>
      val c = client(fake, svidFile(TestJwt.es256("spiffe://llm4s.test/app", "databricks")))
      fake.rejectNextApiCalls(1)
      var chunks = 0
      c.streamComplete(conversation, CompletionOptions(), _ => chunks += 1).isRight shouldBe true
      chunks should be > 0
      fake.issuedTokens shouldBe Seq("t1", "t2")
    }

    "fail with AuthenticationError after a second 401, having tried exactly twice" in FakeTokenExchangeServer.withServer { fake =>
      val c = client(fake, svidFile(TestJwt.es256("spiffe://llm4s.test/app", "databricks")))
      fake.rejectNextApiCalls(2)
      c.complete(conversation, CompletionOptions()).left.value shouldBe an[AuthenticationError]
      fake.apiAuthorizations.size shouldBe 2
    }

    "surface a rejected exchange as AuthenticationError without calling the API" in FakeTokenExchangeServer.withServer { fake =>
      fake.setSubjectValidator(_ => Left("wrong audience"))
      val c = client(fake, svidFile(TestJwt.es256("spiffe://llm4s.test/app", "other")))
      c.complete(conversation, CompletionOptions()).left.value shouldBe an[AuthenticationError]
      fake.apiAuthorizations shouldBe empty
    }

    "make one exchange for concurrent calls" in FakeTokenExchangeServer.withServer { fake =>
      val c    = client(fake, svidFile(TestJwt.es256("spiffe://llm4s.test/app", "databricks")))
      val pool = Executors.newVirtualThreadPerTaskExecutor()
      val calls = (1 to 10).map(_ => (() => c.complete(conversation, CompletionOptions()).isRight): Callable[Boolean])
      pool.invokeAll(calls.asJava).asScala.map(_.get()).forall(identity) shouldBe true
      pool.shutdown()
      fake.issuedTokens shouldBe Seq("t1")
    }

    "present the rotated SVID on the next exchange" in FakeTokenExchangeServer.withServer { fake =>
      fake.setExpiresIn(0)
      val first = TestJwt.es256("spiffe://llm4s.test/app", "databricks")
      val file  = svidFile(first)
      val c     = client(fake, file)
      c.complete(conversation, CompletionOptions()).isRight shouldBe true
      val second = TestJwt.es256("spiffe://llm4s.test/app", "databricks")
      Files.writeString(file, second)
      c.complete(conversation, CompletionOptions()).isRight shouldBe true
      fake.exchanges.map(_("subject_token")) shouldBe Seq(first, second)
    }

    "list models with an exchanged token" in FakeTokenExchangeServer.withServer { fake =>
      val section = sectionOf(
        s"""provider = "openai-compatible"
           |model    = "m"
           |baseUrl  = "${fake.baseUrl}/serving-endpoints"
           |auth { identityTokenFile = "${svidFile(TestJwt.es256("s", "a"))}", tokenUrl = "${fake.baseUrl}${FakeTokenExchangeServer.TokenPath}" }
           |""".stripMargin
      )
      OpenAICompatibleModelLister.listModels(section, Llm4sHttpClient.create()).value.map(_.name.asString) shouldBe List("fake-model")
      fake.apiAuthorizations shouldBe Seq("Bearer t1")
    }
  }
```

`ProviderTestConfig.loadSection(name, hocon)(using ProviderRegistry): Result[NamedProviderConfig]` validates one section of a full HOCON document - the spec's `sectionOf`/`sectionResult` wrap it. Confirm the `Conversation`/`UserMessage` constructors against an existing spec such as `OpenAICompatibleClientSpec`.

- [ ] **Step 2: Run it to see it fail**

Run: `sbt "openaiCompatible/testOnly org.llm4s.llmconnect.provider.OpenAICompatibleWorkloadIdentitySpec"`
Expected: FAIL - `auth` rejected: "provider = openai-compatible does not support an auth block".

- [ ] **Step 3: Declare the auth extras and build the config**

In `OpenAICompatibleProvider`:

```scala
  val TokenUrlKey: String = "tokenUrl"
  val ClientIdKey: String = "clientId"
  val ScopeKey: String    = "scope"
  val AudienceKey: String = "audience"
```

append to `configSpec`:

```scala
      .withAuthExtras(
        Seq(
          ProviderConfigKey.required(TokenUrlKey, "the RFC 8693 token endpoint, e.g. https://<workspace>/oidc/v1/token for Databricks"),
          ProviderConfigKey.optional(ClientIdKey, "the client id sent with the exchange, e.g. a Databricks service principal's UUID"),
          ProviderConfigKey.optional(ScopeKey, "the scope requested, e.g. all-apis for Databricks"),
          ProviderConfigKey.optional(AudienceKey, "the RFC 8693 audience parameter, if the token endpoint needs one")
        )
      )
```

and in `buildConfig`, before `config <- ...`:

```scala
      tokenExchange <- section.auth match
        case None => Right(None)
        case Some(auth) =>
          ProviderDescriptor.requireAuthExtra(providerName, auth, TokenUrlKey).map { tokenUrl =>
            Some(TokenExchangeConfig(auth.identityToken, tokenUrl, auth.extra(ClientIdKey), auth.extra(ScopeKey), auth.extra(AudienceKey)))
          }
```

passing `tokenExchange = tokenExchange` to `OpenAICompatibleConfig.fromValues`. Update the Scaladoc example block with a Databricks section.

- [ ] **Step 4: Carry it on the config**

`OpenAICompatibleConfig`: add `tokenExchange: Option[TokenExchangeConfig] = None` (last field; documented: "workload-identity auth: the identity token is exchanged here for the bearer token, which replaces `apiKey`; never set together with `apiKey`"), include it in `toString` (`TokenExchangeConfig.toString` is safe), add `tokenExchange: Option[TokenExchangeConfig] = None` to `fromValues`, reject both:

```scala
      _ <- Either.cond(
        apiKey.forall(_.trim.isEmpty) || tokenExchange.isEmpty,
        (),
        ConfigurationError("OpenAI-compatible config sets both apiKey and tokenExchange; use one", List("apiKey", "auth"))
      )
```

- [ ] **Step 5: `Credential` and the retry in the client**

In `object OpenAICompatibleClient`:

```scala
  /** How a request authenticates. */
  enum Credential:
    /** No `Authorization` header, for servers that need none. */
    case Anonymous
    /** `Authorization: Bearer <key>`. */
    case Static(key: String)
    /** `Authorization: Bearer <token>`, the token fetched per request and refreshed once on a 401. */
    case Dynamic(provider: AccessTokenProvider)

    override def toString: String = this match
      case Anonymous  => "Anonymous"
      case Static(_)  => "Static(***)"
      case Dynamic(_) => "Dynamic"
```

`Settings`: replace `apiKey: Option[String]` with `credential: Credential` (Scaladoc and `toString` updated; `toString` prints `credential`). `settings(config)` takes the HTTP client so the exchange shares it:

```scala
  def settings(config: OpenAICompatibleConfig, httpClient: Llm4sHttpClient): Settings =
    Settings(
      providerName = OpenAICompatibleConfig.ProviderIdName,
      displayName = "OpenAI-compatible",
      model = config.model,
      baseUrl = config.baseUrl,
      credential = config.tokenExchange match
        case Some(exchange) => Credential.Dynamic(TokenExchange.provider(exchange, httpClient))
        case None           => config.apiKey.fold(Credential.Anonymous)(Credential.Static(_)),
      contextWindow = config.contextWindow,
      reserveCompletion = config.reserveCompletion
    )
```

In `apply(config, ...)`, create one `Llm4sHttpClient.create()` for the exchange and pass it to `settings`. In each of `DeepSeekClient`, `ZaiClient`, `OpenRouterClient`, `CohereClient`, `MistralClient`, replace `apiKey = Some(config.apiKey)` (or similar) with `credential = OpenAICompatibleClient.Credential.Static(config.apiKey)`.

In `class OpenAICompatibleClient`, replace `requestHeaders` and route both calls through one helper:

```scala
  /** The current bearer value, if this client sends one. */
  private def bearer(): Result[Option[String]] = settings.credential match
    case Credential.Anonymous         => Right(None)
    case Credential.Static(key)       => Right(Some(key))
    case Credential.Dynamic(provider) => provider.token().map(Some(_))

  /**
   * The headers every request carries, for bearer value `token`. A header the dialect repeats is
   * sent once, its values comma-joined in order (RFC 9110 section 5.3). Scoped to the provider
   * package so specs can inspect them.
   */
  protected[provider] def requestHeaders(token: Option[String]): Map[String, String] =
    Map("Content-Type" -> "application/json") ++
      token.map(t => "Authorization" -> s"Bearer $t") ++
      OpenAICompatibleClient.combineRepeated(dialect.headers)

  /**
   * Sends with the current token; when the credential is dynamic and the reply is an
   * `AuthenticationError` (401 or 403), reports the token rejected, fetches a fresh one and sends
   * exactly once more. A failure to obtain a token is returned as is, never retried here.
   */
  private def withAuthRetry[A](send: Map[String, String] => Result[A]): Result[A] =
    bearer().flatMap { token =>
      (send(requestHeaders(token)), settings.credential, token) match
        case (Left(_: AuthenticationError), Credential.Dynamic(provider), Some(rejected)) =>
          provider.invalidate(rejected)
          bearer().flatMap(fresh => send(requestHeaders(fresh)))
        case (result, _, _) => result
    }
```

`complete`: wrap the `httpClient.post(...)...` chain as `withAuthRetry { headers => httpClient.post(endpoint, headers, requestText, requestTimeout)... }`.
`streamComplete`: move `val rawStream = new StringBuilder` and `recordExchange` inside the lambda so each attempt records its own exchange:

```scala
    renderRequest(conversation, options, stream = true).flatMap { requestText =>
      withAuthRetry { headers =>
        val rawStream = new StringBuilder
        val result =
          httpClient
            .postStream(endpoint, headers, requestText, streamTimeout)
            .flatMap(response => consumeStream(response.statusCode, response.body, rawStream, onChunk, response.headers))
        recordExchange(startedAt, requestText, Option.when(rawStream.nonEmpty)(rawStream.result()), result)
        result
      }
    }
```

(Safe: `consumeStream` returns the mapped error for a non-200 status before reading any event, so `onChunk` has not been called.) Import `org.llm4s.error.AuthenticationError`, `org.llm4s.llmconnect.auth.{ AccessTokenProvider, TokenExchange }`. Fix any spec that called `requestHeaders` with no argument to pass `Some(key)`/`None`.

- [ ] **Step 6: Model listing with auth**

In `OpenAICompatibleModelLister.listModels`, exchange once when the section has `auth`:

```scala
  def listModels(config: NamedProviderConfig, httpClient: Llm4sHttpClient): Result[List[DiscoveredModel]] =
    for
      baseUrl <- config.requireBaseUrl
      withToken <- config.auth match
        case None => Right(config)
        case Some(auth) =>
          for
            tokenUrl <- ProviderDescriptor.requireAuthExtra("model-lister", auth, OpenAICompatibleProvider.TokenUrlKey)
            exchange = TokenExchangeConfig(auth.identityToken, tokenUrl,
              auth.extra(OpenAICompatibleProvider.ClientIdKey), auth.extra(OpenAICompatibleProvider.ScopeKey), auth.extra(OpenAICompatibleProvider.AudienceKey))
            token <- TokenExchange.rfc8693(exchange, httpClient)()
          yield config.withAuth(None).withApiKey(Some(ApiKey(token.value)))
      models <- delegate.listModels(withToken.withBaseUrl(Some(BaseUrl(baseUrl.asUrl.stripSuffix("/")))), httpClient)
    yield models
```

(`OpenAICompatibleModelLister` lives in `org.llm4s.config`; import `org.llm4s.llmconnect.provider.OpenAICompatibleProvider` - same module, no cycle.)

- [ ] **Step 7: Module spec round trip**

In `Llm4sOpenAICompatibleModuleSpec`, add:

```scala
    "build a client from a section with auth" in {
      val section = sectionOf(
        """provider = "openai-compatible"
          |model = "m"
          |baseUrl = "https://ws.example/serving-endpoints"
          |auth { identityTokenFile = "/var/run/svid", tokenUrl = "https://ws.example/oidc/v1/token" }""".stripMargin
      )
      assertBuildsClient(OpenAICompatibleProvider, section)
    }
```

- [ ] **Step 8: Run the module's tests**

Run: `sbt "openaiCompatible/test"`
Expected: PASS - the new spec and every existing one (they now construct `Credential.Static`).

- [ ] **Step 9: Commit**

```bash
sbt scalafmtAll
git add modules/openai-compatible
git commit -s -m "feat(openai-compatible): workload-identity auth with token exchange, refresh and one retry on 401

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: `openai` - the SDK's native workload identity

**Files:**
- Modify: `modules/openai-compatible/src/main/scala/org/llm4s/llmconnect/config/OpenAIConfig.scala` (add `workloadIdentity: Option[OpenAIWorkloadIdentity]`)
- Create: `modules/openai-compatible/src/main/scala/org/llm4s/llmconnect/config/OpenAIWorkloadIdentity.scala`
- Modify: `modules/openai/src/main/scala/org/llm4s/llmconnect/provider/OpenAIProvider.scala` (`authExtras`, `buildConfig`)
- Modify: `modules/openai/src/main/scala/org/llm4s/llmconnect/provider/OpenAIClient.scala` (`OpenAIClientTransport.openAI`, adapter, test hook)
- Test: `modules/openai/src/test/scala/org/llm4s/llmconnect/provider/OpenAIWorkloadIdentitySpec.scala`
- Modify test: `modules/openai/src/test/scala/org/llm4s/llmconnect/provider/Llm4sOpenAIModuleSpec.scala`

**Interfaces:**
- Consumes: `IdentitySource`, `IdentityTokenSource.from` (Task 1); `AuthConfig`, `ProviderDescriptor.requireAuthExtra` (Task 3).
- Produces:
  - `final case class OpenAIWorkloadIdentity(identityToken: IdentitySource, identityProviderId: String, serviceAccountId: String, clientId: Option[String] = None)` in `org.llm4s.llmconnect.config` (llm4s-openai-compatible, plain data, no SDK type).
  - `OpenAIConfig.workloadIdentity: Option[OpenAIWorkloadIdentity] = None`; `OpenAIConfig.fromValues(..., workloadIdentity: Option[OpenAIWorkloadIdentity] = None)` - `apiKey` may be `""` exactly when `workloadIdentity` is set.
  - `OpenAIProvider.IdentityProviderIdKey = "identityProviderId"`, `ServiceAccountIdKey = "serviceAccountId"`, `ClientIdKey = "clientId"`.
  - `private[provider] object OpenAIClientTransport { def sdkWorkloadIdentity(wi: OpenAIWorkloadIdentity): com.openai.auth.WorkloadIdentity; def openAI(config: OpenAIConfig, customize: OpenAIOkHttpClient.Builder => OpenAIOkHttpClient.Builder = identity): OpenAIClientTransport }` - `customize` is the test hook Task 12 uses for the proxy and trust settings.

- [ ] **Step 1: Write the failing spec**

```scala
// modules/openai/src/test/scala/org/llm4s/llmconnect/provider/OpenAIWorkloadIdentitySpec.scala
package org.llm4s.llmconnect.provider

import com.openai.auth.SubjectTokenType
import org.llm4s.llmconnect.config.{ OpenAIConfig, OpenAIWorkloadIdentity }
import org.llm4s.llmconnect.auth.IdentitySource
import org.llm4s.testkit.{ ProviderModuleChecks, ProviderTestConfig }
import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.Files

class OpenAIWorkloadIdentitySpec extends AnyWordSpec with Matchers with EitherValues with ProviderModuleChecks:

  private given ProviderRegistry = ProviderRegistry.default

  /** The section `llm4s.providers.main` with `body`, as validation leaves it. */
  private def sectionResult(body: String): Result[NamedProviderConfig] =
    ProviderTestConfig.loadSection("main", s"llm4s.providers.main {\n$body\n}")

  private def sectionOf(body: String): NamedProviderConfig =
    sectionResult(body).fold(error => fail(error.message), identity)

  private val section =
    """provider = "openai"
      |model = "gpt-4o-mini"
      |auth { identityTokenFile = "/var/run/svid", identityProviderId = "idp_1", serviceAccountId = "sa_1", clientId = "c_1" }""".stripMargin

  "an openai section with auth" should {
    "build an OpenAIConfig carrying the workload identity and no key" in {
      val config = ProviderTestConfig.loadProvider("main", s"llm4s.providers.main {\n$section\n}").value
      val openai = config.asInstanceOf[OpenAIConfig]
      openai.apiKey shouldBe ""
      openai.workloadIdentity.value shouldBe OpenAIWorkloadIdentity(
        IdentitySource.File(java.nio.file.Path.of("/var/run/svid")), "idp_1", "sa_1", Some("c_1"))
    }

    "build a client" in {
      assertBuildsClient(OpenAIProvider, sectionOf(section))
    }

    "map to the SDK's WorkloadIdentity with a JWT subject token read from the file" in {
      val file = Files.createTempFile("svid", ".jwt")
      Files.writeString(file, "eyJ.svid.sig\n")
      val sdk = OpenAIClientTransport.sdkWorkloadIdentity(
        OpenAIWorkloadIdentity(IdentitySource.File(file), "idp_1", "sa_1", Some("c_1")))
      sdk.identityProviderId shouldBe "idp_1"
      sdk.serviceAccountId shouldBe "sa_1"
      sdk.clientId shouldBe "c_1"
      sdk.provider.tokenType shouldBe SubjectTokenType.JWT
      sdk.provider.getToken(null, null) shouldBe "eyJ.svid.sig"
    }

    "reject a missing serviceAccountId" in {
      val bad = section.replace("serviceAccountId = \"sa_1\", ", "")
      sectionResult(bad).isLeft shouldBe true
    }
  }

  "auth on azure or requesty" should {
    "be rejected" in {
      for provider <- Seq("azure", "requesty") do
        sectionResult(
          s"""provider = "$provider"
             |model = "m"
             |auth { identityTokenFile = "/s" }""".stripMargin
        ).isLeft shouldBe true
    }
  }
```

Check whether the SDK's `clientId()` returns `String` or `Optional<String>` (`javap -cp <openai-java-core jar> com.openai.auth.WorkloadIdentity` showed `String`) and whether the getter names are `identityProviderId()`; adjust the assertions to the real accessors.

- [ ] **Step 2: Run it to see it fail**

Run: `sbt "openai/testOnly org.llm4s.llmconnect.provider.OpenAIWorkloadIdentitySpec"`
Expected: compilation failure - `OpenAIWorkloadIdentity` not found.

- [ ] **Step 3: Add the data type and the config field**

```scala
// modules/openai-compatible/src/main/scala/org/llm4s/llmconnect/config/OpenAIWorkloadIdentity.scala
package org.llm4s.llmconnect.config

import org.llm4s.llmconnect.auth.IdentitySource

/**
 * OpenAI workload identity federation: the identity token (e.g. a SPIFFE JWT-SVID) the OpenAI SDK
 * exchanges at OpenAI's token endpoint for the service account's access token.
 *
 * @param identityProviderId the OpenAI workload identity provider (`OPENAI_IDENTITY_PROVIDER_ID`)
 * @param serviceAccountId   the OpenAI service account to act as (`OPENAI_SERVICE_ACCOUNT_ID`)
 * @param clientId           sent with the exchange when the identity provider requires one
 */
final case class OpenAIWorkloadIdentity(
  identityToken: IdentitySource,
  identityProviderId: String,
  serviceAccountId: String,
  clientId: Option[String] = None
)
```

`OpenAIConfig`: add `workloadIdentity: Option[OpenAIWorkloadIdentity] = None` after `explicitProviderId`; `toString` adds `workloadIdentity=${workloadIdentity.map(_ => "set").getOrElse("none")}`; `fromValues` gains `workloadIdentity: Option[OpenAIWorkloadIdentity] = None` and its first check becomes:

```scala
      _ <- if workloadIdentity.isDefined then Right(()) else ProviderConfig.nonEmpty("OpenAI", "apiKey", apiKey)
```

passing `workloadIdentity = workloadIdentity` into the constructed config. Document on `apiKey`: "empty when `workloadIdentity` is set".

- [ ] **Step 4: Declare auth on `openai` only**

```scala
  val IdentityProviderIdKey: String = "identityProviderId"
  val ServiceAccountIdKey: String   = "serviceAccountId"
  val ClientIdKey: String           = "clientId"

  val configSpec: ProviderConfigSpec =
    ProviderConfigSpec
      .apiKeyAndDefaultBaseUrl(DEFAULT_BASE_URL, Seq(OpenAIConfigKeys.OPENAI_API_KEY))
      .withExtras(Seq(OpenAIConfig.OrganizationConfigKey))
      .withAuthExtras(
        Seq(
          ProviderConfigKey.required(IdentityProviderIdKey, "the OpenAI workload identity provider id"),
          ProviderConfigKey.required(ServiceAccountIdKey, "the OpenAI service account id"),
          ProviderConfigKey.optional(ClientIdKey, "the client id, if the identity provider requires one")
        )
      )

  def buildConfig(providerName: String, section: NamedProviderConfig)(using ContextWindowResolver): Result[ProviderConfig] =
    for
      workloadIdentity <- section.auth match
        case None => Right(None)
        case Some(auth) =>
          for
            idp <- ProviderDescriptor.requireAuthExtra(providerName, auth, IdentityProviderIdKey)
            sa  <- ProviderDescriptor.requireAuthExtra(providerName, auth, ServiceAccountIdKey)
          yield Some(OpenAIWorkloadIdentity(auth.identityToken, idp, sa, auth.extra(ClientIdKey)))
      apiKey  <- if workloadIdentity.isDefined then Right("") else ProviderDescriptor.requireApiKey(providerName, section).map(_.toString)
      baseUrl <- ProviderDescriptor.resolveBaseUrl(providerName, section, configSpec)
      config  <- OpenAIConfig.fromValues(section.model.asString, apiKey, section.extra(OpenAIConfig.OrganizationKey), baseUrl, workloadIdentity = workloadIdentity)
    yield config
```

(Keep `requireApiKey`'s return type as it is today; if it returns `ApiKey`, use `.asKey` instead of `.toString`.) `AzureProvider` and `RequestyProvider` declare no `authExtras`, so validation already rejects `auth` for them.

- [ ] **Step 5: Wire the SDK**

In `OpenAIClientTransport`:

```scala
  /** The SDK's workload identity for `wi`: a JWT subject token read from `wi.identityToken` on each exchange. */
  private[provider] def sdkWorkloadIdentity(wi: OpenAIWorkloadIdentity): WorkloadIdentity =
    val source = IdentityTokenSource.from(wi.identityToken)
    val subject = new SubjectTokenProvider:
      override def tokenType(): SubjectTokenType = SubjectTokenType.JWT
      // The SDK's callback contract is exceptions; mapError turns this back into a Result.
      override def getToken(httpClient: HttpClient, jsonMapper: JsonMapper): String =
        source.fetch().fold(error => throw new IllegalStateException(error.message), identity)
      override def getTokenAsync(httpClient: HttpClient, jsonMapper: JsonMapper): CompletableFuture[String] =
        CompletableFuture.supplyAsync(() => getToken(httpClient, jsonMapper))
    val builder = WorkloadIdentity.builder()
      .identityProviderId(wi.identityProviderId)
      .serviceAccountId(wi.serviceAccountId)
      .provider(subject)
    wi.clientId.foreach(builder.clientId)
    builder.build()

  def openAI(
    config: OpenAIConfig,
    customize: OpenAIOkHttpClient.Builder => OpenAIOkHttpClient.Builder = identity
  ): OpenAIClientTransport =
    val builder = OpenAIOkHttpClient.builder().baseUrl(config.baseUrl).organization(config.organization.orNull)
    config.workloadIdentity match
      case Some(wi) => builder.workloadIdentity(sdkWorkloadIdentity(wi))
      case None     => builder.apiKey(config.apiKey)
    sdk(customize(builder).build())
```

Imports: `com.openai.auth.{ SubjectTokenProvider, SubjectTokenType, WorkloadIdentity }`, `com.openai.core.http.HttpClient`, `com.fasterxml.jackson.databind.json.JsonMapper`, `java.util.concurrent.CompletableFuture`, `org.llm4s.llmconnect.auth.IdentityTokenSource`, `org.llm4s.llmconnect.config.OpenAIWorkloadIdentity`. `throw` inside the SDK callback is the SDK's contract; confirm `OpenAIClient.mapError` maps the resulting exception (wrapped by the SDK) to an `LLMError` - add a case mapping a cause message starting "Authentication failed" to `AuthenticationError("openai", ...)` if it does not. Add a Task 12 hook: `OpenAIClient` gets `private[provider] def forTransport(config, transport, ...)` if `forTest` (line 649) does not already accept a transport; read `forTest` first and reuse it.

- [ ] **Step 6: Module spec round trip**

In `Llm4sOpenAIModuleSpec`, add `assertBuildsClient(OpenAIProvider, sectionOf(<the auth section above>))`.

- [ ] **Step 7: Run the module's tests**

Run: `sbt "openai/test" "openaiCompatible/test"`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
sbt scalafmtAll
git add modules/openai modules/openai-compatible modules/provider-testkit
git commit -s -m "feat(openai): workload identity through the OpenAI SDK

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: `anthropic` - the SDK's native workload identity

**Files:**
- Create: `modules/anthropic/src/main/scala/org/llm4s/llmconnect/config/AnthropicWorkloadIdentity.scala`
- Modify: `modules/anthropic/src/main/scala/org/llm4s/llmconnect/config/AnthropicConfig.scala`
- Modify: `modules/anthropic/src/main/scala/org/llm4s/llmconnect/provider/AnthropicProvider.scala`
- Modify: `modules/anthropic/src/main/scala/org/llm4s/llmconnect/provider/AnthropicClient.scala`
- Test: `modules/anthropic/src/test/scala/org/llm4s/llmconnect/provider/AnthropicWorkloadIdentitySpec.scala`
- Modify test: `Llm4sAnthropicModuleSpec.scala`

**Interfaces:**
- Consumes: `AuthConfig`, `IdentitySource`, `ProviderDescriptor.requireAuthExtra` (Tasks 1, 3); `FakeTokenExchangeServer` (`AnthropicTokenPath`, `AnthropicMessagesPath`, `exchanges`, `apiAuthorizations`, `rejectNextApiCalls`) and `TestJwt` (Task 4).
- Produces:
  - `final case class AnthropicWorkloadIdentity(identityTokenFile: java.nio.file.Path, federationRuleId: String, organizationId: String, serviceAccountId: Option[String] = None, workspaceId: Option[String] = None)`.
  - `AnthropicConfig.workloadIdentity: Option[AnthropicWorkloadIdentity] = None`; `fromValues(..., workloadIdentity = None)`; `apiKey` is `""` exactly when it is set.
  - `AnthropicProvider.FederationRuleIdKey`, `OrganizationIdKey`, `ServiceAccountIdKey`, `WorkspaceIdKey`.

- [ ] **Step 1: Write the failing spec**

```scala
// modules/anthropic/src/test/scala/org/llm4s/llmconnect/provider/AnthropicWorkloadIdentitySpec.scala
package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, UserMessage }
import org.llm4s.testkit.{ FakeTokenExchangeServer, ProviderModuleChecks, ProviderTestConfig, TestJwt }
import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.Files

class AnthropicWorkloadIdentitySpec extends AnyWordSpec with Matchers with EitherValues with ProviderModuleChecks:

  private given ProviderRegistry = ProviderRegistry.default

  /** The section `llm4s.providers.main` with `body`, as validation leaves it. */
  private def sectionResult(body: String): Result[NamedProviderConfig] =
    ProviderTestConfig.loadSection("main", s"llm4s.providers.main {\n$body\n}")

  private def sectionOf(body: String): NamedProviderConfig =
    sectionResult(body).fold(error => fail(error.message), identity)

  private val conversation = Conversation(Seq(UserMessage("hi")))

  private def section(baseUrl: String, svid: String, idTokenKey: String = "identityTokenFile") =
    s"""provider = "anthropic"
       |model = "claude-test"
       |baseUrl = "$baseUrl"
       |auth { $idTokenKey = "$svid", federationRuleId = "fdrl_1", organizationId = "org_1", workspaceId = "wrkspc_1" }""".stripMargin

  "an anthropic section with auth" should {
    "exchange the SVID with the jwt-bearer grant and call messages with the access token" in FakeTokenExchangeServer.withServer { fake =>
      val jwt  = TestJwt.es256("spiffe://llm4s.test/app", "https://api.anthropic.com")
      val file = Files.createTempFile("svid", ".jwt")
      Files.writeString(file, jwt)
      val client = assertBuildsClient(AnthropicProvider, sectionOf(section(fake.baseUrl, file.toString)))
      client.complete(conversation, CompletionOptions()).isRight shouldBe true
      val grant = fake.exchanges.head
      grant("grant_type") shouldBe "urn:ietf:params:oauth:grant-type:jwt-bearer"
      grant("assertion") shouldBe jwt
      grant("federation_rule_id") shouldBe "fdrl_1"
      grant("organization_id") shouldBe "org_1"
      fake.apiAuthorizations.last shouldBe "Bearer t1"
    }

    "refresh after a 401" in FakeTokenExchangeServer.withServer { fake =>
      val file = Files.createTempFile("svid", ".jwt")
      Files.writeString(file, TestJwt.es256("spiffe://llm4s.test/app", "https://api.anthropic.com"))
      val client = assertBuildsClient(AnthropicProvider, sectionOf(section(fake.baseUrl, file.toString)))
      client.complete(conversation, CompletionOptions()).isRight shouldBe true
      fake.rejectNextApiCalls(1)
      client.complete(conversation, CompletionOptions()).isRight shouldBe true
      fake.issuedTokens.size should be >= 2
    }

    "reject a literal identityToken, since the SDK reads only a file" in {
      val body   = section("https://api.anthropic.com", "eyJ.x.y", "identityToken")
      val result = ProviderTestConfig.loadProvider("main", s"llm4s.providers.main {\n$body\n}")
      result.left.value.message should include("identityTokenFile")
    }
  }
```

If the SDK does not refresh on a forced 401 (it retries per its own `AuthorizingHttpClient`), the second test documents that: replace the `>= 2` assertion with the observed behaviour and note it in the PR description - do not weaken the first test.

- [ ] **Step 2: Run it to see it fail**

Run: `sbt "anthropic/testOnly org.llm4s.llmconnect.provider.AnthropicWorkloadIdentitySpec"`
Expected: FAIL - `auth` not supported by `anthropic`.

- [ ] **Step 3: Data type, config and descriptor**

```scala
// modules/anthropic/src/main/scala/org/llm4s/llmconnect/config/AnthropicWorkloadIdentity.scala
package org.llm4s.llmconnect.config

import java.nio.file.Path

/**
 * Anthropic workload identity federation: the Anthropic SDK reads the identity token (e.g. a
 * SPIFFE JWT-SVID kept fresh by spiffe-helper) from `identityTokenFile` on every exchange and
 * presents it to `<baseUrl>/v1/oauth/token` under `federationRuleId`.
 */
final case class AnthropicWorkloadIdentity(
  identityTokenFile: Path,
  federationRuleId: String,
  organizationId: String,
  serviceAccountId: Option[String] = None,
  workspaceId: Option[String] = None
)
```

`AnthropicConfig`: add `workloadIdentity: Option[AnthropicWorkloadIdentity] = None`, `fromValues(..., workloadIdentity: Option[AnthropicWorkloadIdentity] = None)` skipping the `apiKey` non-empty check when set, `toString` showing `workloadIdentity=set|none`.

`AnthropicProvider`:

```scala
  val FederationRuleIdKey: String = "federationRuleId"
  val OrganizationIdKey: String   = "organizationId"
  val ServiceAccountIdKey: String = "serviceAccountId"
  val WorkspaceIdKey: String      = "workspaceId"

  val configSpec: ProviderConfigSpec =
    ProviderConfigSpec
      .apiKeyAndDefaultBaseUrl(AnthropicConfig.DEFAULT_BASE_URL, Seq(AnthropicConfigKeys.ANTHROPIC_API_KEY))
      .withAuthExtras(
        Seq(
          ProviderConfigKey.required(FederationRuleIdKey, "the Anthropic federation rule id (fdrl_...)"),
          ProviderConfigKey.required(OrganizationIdKey, "the Anthropic organization id"),
          ProviderConfigKey.optional(ServiceAccountIdKey, "the Anthropic service account id (svac_...)"),
          ProviderConfigKey.optional(WorkspaceIdKey, "the Anthropic workspace id (wrkspc_...)")
        )
      )

  def buildConfig(providerName: String, section: NamedProviderConfig)(using ContextWindowResolver): Result[ProviderConfig] =
    for
      workloadIdentity <- section.auth match
        case None => Right(None)
        case Some(auth) =>
          for
            file <- auth.identityToken match
              case IdentitySource.File(path) => Right(path)
              case IdentitySource.Literal(_) =>
                Left(ConfigurationError(
                  s"llm4s.providers.$providerName.auth: the Anthropic SDK reads the identity token from a file; set identityTokenFile, not identityToken"))
            rule <- ProviderDescriptor.requireAuthExtra(providerName, auth, FederationRuleIdKey)
            org  <- ProviderDescriptor.requireAuthExtra(providerName, auth, OrganizationIdKey)
          yield Some(AnthropicWorkloadIdentity(file, rule, org, auth.extra(ServiceAccountIdKey), auth.extra(WorkspaceIdKey)))
      apiKey  <- if workloadIdentity.isDefined then Right("") else ProviderDescriptor.requireApiKey(providerName, section).map(_.toString)
      baseUrl <- ProviderDescriptor.resolveBaseUrl(providerName, section, configSpec)
      config  <- AnthropicConfig.fromValues(section.model.asString, apiKey, baseUrl, workloadIdentity = workloadIdentity)
    yield config
```

(As in Task 6, match `requireApiKey`'s real return type.)

- [ ] **Step 4: Wire the SDK in `AnthropicClient`**

```scala
  private val client =
    val builder = AnthropicOkHttpClient.builder().baseUrl(config.baseUrl)
    config.workloadIdentity match
      case None => builder.apiKey(config.apiKey)
      case Some(wi) =>
        val auth = AuthenticationConfig.builder()
          .`type`(AuthenticationType.OIDC_FEDERATION)
          .federationRuleId(wi.federationRuleId)
          .identityToken(IdentityTokenConfig.builder().source("file").path(wi.identityTokenFile.toString).build())
        wi.serviceAccountId.foreach(auth.serviceAccountId)
        val profile = ProfileConfig.builder().authentication(auth.build()).baseUrl(config.baseUrl).organizationId(wi.organizationId)
        wi.workspaceId.foreach(profile.workspaceId)
        builder.configurationProvider(InMemoryProfileConfigProvider.of(profile.build()))
    builder.build()
```

Imports: `com.anthropic.config.{ AuthenticationConfig, AuthenticationType, IdentityTokenConfig, InMemoryProfileConfigProvider, ProfileConfig }`. If the SDK rejects `source("file")` (check `IdentityTokenConfig`'s accepted values with a quick spike in the spec: build it and call `complete` against the fake), use the value the SDK's `ConfigurationFileProvider` documents for a file source.

- [ ] **Step 5: Module spec round trip and run**

Add to `Llm4sAnthropicModuleSpec` an `assertBuildsClient(AnthropicProvider, <auth section with identityTokenFile>)` case.

Run: `sbt "anthropic/test"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
sbt scalafmtAll
git add modules/anthropic
git commit -s -m "feat(anthropic): workload identity through the Anthropic SDK

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 8: Docs, CHANGELOG, full build, PR 1

**Files:**
- Modify: `docs/getting-started/configuration.md` (new "Workload identity (SPIFFE)" subsection under "Named provider sections", after "### API keys")
- Modify: `CHANGELOG.md` (`## [Unreleased]` / `### Added`)
- Modify: `docs/guide/writing-a-provider.md` (one paragraph on `authExtras`)

- [ ] **Step 1: Write the docs section**

Add after the "### API keys" subsection (around line 192):

````markdown
### Workload identity (SPIFFE)

A section can authenticate with a workload identity token - typically a SPIFFE JWT-SVID that
[`spiffe-helper`](https://github.com/spiffe/spiffe-helper) keeps fresh in a file - instead of an
API key. Put an `auth` block in the section, with `identityTokenFile` (re-read on every exchange,
so rotation is picked up) and the keys the provider needs. A section sets `apiKey` or `auth`,
never both; with `auth`, the shared `llm4s.credentials.<id>.apiKey` is not used.

**Databricks model serving** (the generic `openai-compatible` provider; the SVID is exchanged at
the workspace's RFC 8693 endpoint, the token cached until shortly before it expires, and refreshed
once if a request is rejected with 401):

```hocon
databricks-main {
  provider = "openai-compatible"
  baseUrl  = "https://<workspace>.cloud.databricks.com/serving-endpoints"
  model    = "<serving endpoint name>"
  auth {
    identityTokenFile = "/var/run/secrets/spiffe/databricks"
    tokenUrl = "https://<workspace>.cloud.databricks.com/oidc/v1/token"
    clientId = ${?DATABRICKS_CLIENT_ID}   # the service principal, for a service-principal federation policy
    scope    = "all-apis"
  }
}
```

**OpenAI** (the OpenAI SDK's workload identity federation):

```hocon
openai-wif {
  provider = "openai"
  model    = "gpt-4o-mini"
  auth {
    identityTokenFile  = "/var/run/secrets/spiffe/openai"
    identityProviderId = ${OPENAI_IDENTITY_PROVIDER_ID}
    serviceAccountId   = ${OPENAI_SERVICE_ACCOUNT_ID}
  }
}
```

**Anthropic** (the Anthropic SDK's workload identity federation; `identityTokenFile` only):

```hocon
anthropic-wif {
  provider = "anthropic"
  model    = "claude-sonnet-4-5"
  auth {
    identityTokenFile = "/var/run/secrets/spiffe/anthropic"
    federationRuleId  = ${ANTHROPIC_FEDERATION_RULE_ID}
    organizationId    = ${ANTHROPIC_ORGANIZATION_ID}
    serviceAccountId  = ${?ANTHROPIC_SERVICE_ACCOUNT_ID}
    workspaceId       = ${?ANTHROPIC_WORKSPACE_ID}
  }
}
```

Request each SVID for the audience its relying party expects (`jwt_audience` in `spiffe-helper`).
Other providers reject an `auth` block.
````

- [ ] **Step 2: CHANGELOG entry** under `## [Unreleased]` / `### Added`:

```markdown
- **Workload identity (SPIFFE) for providers**: a named provider section may carry an `auth`
  block instead of `apiKey` - an `identityTokenFile` (e.g. a JWT-SVID kept by `spiffe-helper`) plus
  provider keys. `openai-compatible` exchanges it at an RFC 8693 endpoint (`tokenUrl`, `clientId`,
  `scope`, `audience` - Databricks' `/oidc/v1/token`), caches the token until shortly before
  expiry and refreshes and retries once on 401; `openai` (`identityProviderId`,
  `serviceAccountId`, `clientId`) and `anthropic` (`federationRuleId`, `organizationId`,
  `serviceAccountId`, `workspaceId`) use their SDKs' workload identity federation. New core types
  in `org.llm4s.llmconnect.auth`; `ProviderConfigSpec.authExtras` declares a provider's auth keys;
  `OpenAICompatibleClient.Settings.apiKey` became `credential: Credential`; `ApiKeySource` gained
  `WorkloadIdentity`. `llm4s-provider-testkit` gains `FakeTokenExchangeServer` and `TestJwt`.
```

- [ ] **Step 3: Provider-author guide** - in `docs/guide/writing-a-provider.md`, next to where `extras` is described, add: "A provider that can authenticate with a workload identity token declares the keys it reads from a section's `auth` block in `ProviderConfigSpec.authExtras`; core parses `identityTokenFile`/`identityToken` itself and hands the rest to `buildConfig` as `section.auth`. `TokenExchange.provider` gives an RFC 8693 exchange with caching; `FakeTokenExchangeServer` in the testkit tests it."

- [ ] **Step 4: Full verification**

Run: `sbt scalafmtCheckAll "scalafixAll --check" test docs/doc`
Expected: all succeed. Also run `sbt "openai/testOnly org.llm4s.config.DocumentedProviderConfigSpec"` - PASS (the new HOCON is in new subsections it does not load; if it parses every `hocon` block in the file, make the examples load by using `${?VAR}` forms for required ids in a test env, or follow how it handles other env-bound examples).

- [ ] **Step 5: Commit and open PR 1**

```bash
git add docs CHANGELOG.md
git commit -s -m "docs: workload identity (SPIFFE) for providers

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
git push -u origin feat/spiffe-workload-identity
gh pr create --title "feat: workload identity (SPIFFE) for providers" --body "$(cat <<'EOF'
Adds an `auth` block to named provider sections so a workload can authenticate with a SPIFFE
JWT-SVID instead of an API key: RFC 8693 exchange with caching, refresh and one retry on 401 for
`openai-compatible` (Databricks), and the SDKs' native workload identity for `openai` and
`anthropic`. Layers 1 and 2 of the test plan (unit, fake-server module specs).

Spec: docs/superpowers/specs/2026-10-04-spiffe-workload-identity-design.md
Plan: docs/superpowers/plans/2026-10-04-spiffe-workload-identity.md

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## PR 2: the `@Spiffe` integration tier (layers 3 and 4)

Branch from PR 1's branch: `git checkout -b feat/spiffe-it-tier`.

### Task 9: The SPIRE stack and the `@Spiffe` tier

**Files:**
- Create: `modules/it/src/test/java/org/llm4s/it/tags/Spiffe.java`
- Modify: `project/ItTiers.scala` (`Spiffe` tag in `all`)
- Modify: `build.sbt` (alias `testSpiffe`; `it` depends on `providerTestkit % Test`, adds `Deps.nimbusJoseJwt % Test`)
- Modify: `project/Dependencies.scala` (`nimbusJoseJwt`)
- Create: `modules/it/spiffe/compose.yml`, `modules/it/spiffe/server.conf`, `modules/it/spiffe/agent.conf`, `modules/it/spiffe/helper.conf`, `modules/it/spiffe/up.sh`, `modules/it/spiffe/down.sh`
- Create: `modules/it/src/test/scala/org/llm4s/it/spiffe/SpiffeStack.scala`
- Test: `modules/it/src/test/scala/org/llm4s/it/spiffe/SpiffeStackSpec.scala`
- Modify: `.github/workflows/ci.yml` (job `spiffe-integration`)

**Interfaces:**
- Consumes: `FakeTokenExchangeServer.setSubjectValidator` (Task 4); `Tier.require(available: Boolean, what: String)` (existing).
- Produces:
  - `object SpiffeStack { val Dir: Path; def svid(audience: String): Path; def available: Boolean; def requireAvailable(): Unit; def validator(expectedAudience: String): String => Either[String, Unit]; val TrustDomain = "llm4s.test"; val Issuer = "https://spire.llm4s.test"; val Audiences = Seq("databricks", "https://api.openai.com/v1", "https://api.anthropic.com") }` - `Dir` is `modules/it/target/spiffe` (from `user.dir`); `svid(a)` is `Dir.resolve(fileName(a))` with file names `databricks.jwt`, `openai.jwt`, `anthropic.jwt`; `validator` verifies an SVID's signature against `Dir/bundle.json` (JWKS) with nimbus, plus `iss == Issuer`, `aud` contains `expectedAudience`, `sub` starts with `spiffe://llm4s.test/`, `exp` in the future.

- [ ] **Step 1: The tag and tier wiring**

```java
// modules/it/src/test/java/org/llm4s/it/tags/Spiffe.java
package org.llm4s.it.tags;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.scalatest.TagAnnotation;

/**
 * Tier: needs the SPIRE stack in {@code modules/it/spiffe} running ({@code modules/it/spiffe/up.sh}),
 * which writes real JWT-SVIDs and the trust bundle to {@code modules/it/target/spiffe}.
 *
 * <p>Run with {@code sbt testSpiffe}; CI runs it in the {@code spiffe-integration} job.
 */
@TagAnnotation
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
public @interface Spiffe {}
```

`project/ItTiers.scala`: `val Spiffe = "org.llm4s.it.tags.Spiffe"`, add to `all`. `build.sbt`: next to `testOllama`, `addCommandAlias("testSpiffe", ItTiers.alias(ItTiers.Spiffe))`, and add a line to the tier comment block (`sbt testSpiffe  Tier 2 - @Spiffe: needs the SPIRE stack (modules/it/spiffe/up.sh)`). In the `it` project add `providerTestkit % Test` to `dependsOn` and `Deps.nimbusJoseJwt % Test` to `libraryDependencies`. In `project/Dependencies.scala` add the version and `val nimbusJoseJwt = "com.nimbusds" % "nimbus-jose-jwt" % Versions.nimbusJoseJwt` - use the latest 10.x on Maven Central (`gh api repos/... ` is not available for Maven; check https://central.sonatype.com/artifact/com.nimbusds/nimbus-jose-jwt).

- [ ] **Step 2: Confirm the image layout**

Local Docker on the planning machine was unusable (read-only containerd store), so confirm these before writing the stack:

Run:
```bash
for i in ghcr.io/spiffe/spire-server:1.15.3 ghcr.io/spiffe/spire-agent:1.15.3 ghcr.io/spiffe/spiffe-helper:0.12.1; do
  docker pull -q "$i" && docker inspect "$i" --format "$i entry={{json .Config.Entrypoint}} cmd={{json .Config.Cmd}} user={{.Config.User}}"
done
```
Expected: the SPIRE images' entrypoints are `/opt/spire/bin/spire-server` / `spire-agent` (with `run`), and the helper's is its binary. Use the reported paths and users in the files below where they differ; the uid in the registration selector must be the helper's runtime uid (`0` if `user` is empty).

- [ ] **Step 3: SPIRE configuration**

```hcl
# modules/it/spiffe/server.conf
server {
  bind_address         = "0.0.0.0"
  bind_port            = "8081"
  trust_domain         = "llm4s.test"
  data_dir             = "/run/spire/data"
  log_level            = "INFO"
  jwt_issuer           = "https://spire.llm4s.test"
  default_jwt_svid_ttl = "1m"
}
plugins {
  DataStore "sql"   { plugin_data { database_type = "sqlite3" connection_string = "/run/spire/data/datastore.sqlite3" } }
  KeyManager "memory" { plugin_data {} }
  NodeAttestor "join_token" { plugin_data {} }
}
```

```hcl
# modules/it/spiffe/agent.conf
agent {
  data_dir          = "/run/spire/data"
  log_level         = "INFO"
  server_address    = "spire-server"
  server_port       = "8081"
  socket_path       = "/run/spire/sockets/agent.sock"
  trust_domain      = "llm4s.test"
  insecure_bootstrap = true
}
plugins {
  NodeAttestor "join_token" { plugin_data {} }
  KeyManager "memory" { plugin_data {} }
  WorkloadAttestor "unix" { plugin_data {} }
}
```

```hcl
# modules/it/spiffe/helper.conf
agent_address = "/run/spire/sockets/agent.sock"
cert_dir      = "/out"
daemon_mode   = true
jwt_svids = [
  { jwt_audience = "databricks",                jwt_svid_file_name = "databricks.jwt" },
  { jwt_audience = "https://api.openai.com/v1", jwt_svid_file_name = "openai.jwt" },
  { jwt_audience = "https://api.anthropic.com", jwt_svid_file_name = "anthropic.jwt" },
]
jwt_bundle_file_name = "bundle.json"
```

`jwt_bundle_file_name` writes the JWT bundle the helper receives - the JWKS `SpiffeStack.validator` verifies against - and keeps it current across key rotation. If the helper version writes it as `{ "<trust domain>": <jwks> }` rather than a bare JWKS, `SpiffeStack` unwraps it (Step 5 handles both).

- [ ] **Step 4: Compose file and scripts**

```yaml
# modules/it/spiffe/compose.yml
name: llm4s-spiffe
services:
  spire-server:
    image: ghcr.io/spiffe/spire-server:1.15.3
    command: ["-config", "/etc/spire/server.conf"]
    volumes:
      - ./server.conf:/etc/spire/server.conf:ro
    healthcheck:
      test: ["CMD", "/opt/spire/bin/spire-server", "healthcheck"]
      interval: 2s
      retries: 30
  spire-agent:
    image: ghcr.io/spiffe/spire-agent:1.15.3
    command: ["-config", "/etc/spire/agent.conf", "-joinToken", "${JOIN_TOKEN:?run up.sh}"]
    depends_on:
      spire-server: { condition: service_healthy }
    volumes:
      - ./agent.conf:/etc/spire/agent.conf:ro
      - sockets:/run/spire/sockets
    healthcheck:
      test: ["CMD", "/opt/spire/bin/spire-agent", "healthcheck", "-socketPath", "/run/spire/sockets/agent.sock"]
      interval: 2s
      retries: 30
  spiffe-helper:
    image: ghcr.io/spiffe/spiffe-helper:0.12.1
    command: ["-config", "/etc/spiffe-helper/helper.conf"]
    pid: "service:spire-agent"   # the unix attestor must see this process
    depends_on:
      spire-agent: { condition: service_healthy }
    volumes:
      - ./helper.conf:/etc/spiffe-helper/helper.conf:ro
      - sockets:/run/spire/sockets
      - ../target/spiffe:/out
volumes:
  sockets: {}
```

```bash
#!/usr/bin/env bash
# modules/it/spiffe/up.sh - start SPIRE, register the helper, wait for SVIDs.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p ../target/spiffe && rm -f ../target/spiffe/*
export JOIN_TOKEN=placeholder
docker compose up -d --wait spire-server
SERVER=(docker compose exec -T spire-server /opt/spire/bin/spire-server)
JOIN_TOKEN=$("${SERVER[@]}" token generate -spiffeID spiffe://llm4s.test/agent | awk '{print $2}')
export JOIN_TOKEN
"${SERVER[@]}" entry create -parentID spiffe://llm4s.test/agent \
  -spiffeID spiffe://llm4s.test/llm4s-it -selector unix:uid:0 -jwtSVIDTTL 60
docker compose up -d --wait spire-agent
docker compose up -d spiffe-helper
for _ in $(seq 1 60); do
  [ -s ../target/spiffe/databricks.jwt ] && [ -s ../target/spiffe/openai.jwt ] && \
  [ -s ../target/spiffe/anthropic.jwt ] && [ -s ../target/spiffe/bundle.json ] && exit 0
  sleep 1
done
docker compose logs; exit 1
```

```bash
#!/usr/bin/env bash
# modules/it/spiffe/down.sh
cd "$(dirname "$0")" && JOIN_TOKEN=x docker compose down -v
```

`chmod +x modules/it/spiffe/*.sh`. Add `modules/it/target/` is already ignored by `target/` in `.gitignore` - confirm.

Run: `modules/it/spiffe/up.sh && ls -l modules/it/target/spiffe`
Expected: four non-empty files. If the helper is not attested (`no identity issued` in `docker compose logs spiffe-helper`), fix the `unix:uid` selector to the helper's uid from Step 2.

- [ ] **Step 5: `SpiffeStack` and its spec**

```scala
// modules/it/src/test/scala/org/llm4s/it/spiffe/SpiffeStack.scala
package org.llm4s.it.spiffe

import com.nimbusds.jose.crypto.factories.DefaultJWSVerifierFactory
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jwt.SignedJWT
import org.llm4s.it.Tier

import java.nio.file.{ Files, Path }
import java.time.Instant
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** The files `modules/it/spiffe/up.sh` leaves behind, and a verifier for the SVIDs in them. */
object SpiffeStack:
  val TrustDomain = "llm4s.test"
  val Issuer      = "https://spire.llm4s.test"
  val Databricks  = "databricks"
  val OpenAI      = "https://api.openai.com/v1"
  val Anthropic   = "https://api.anthropic.com"

  val Dir: Path = Path.of(System.getProperty("user.dir")).resolve("modules/it/target/spiffe") match
    case p if Files.isDirectory(p) => p
    case _                         => Path.of(System.getProperty("user.dir")).resolve("target/spiffe") // forked from modules/it

  private val files = Map(Databricks -> "databricks.jwt", OpenAI -> "openai.jwt", Anthropic -> "anthropic.jwt")

  def svid(audience: String): Path = Dir.resolve(files(audience))

  def available: Boolean = (files.values.toSeq :+ "bundle.json").forall(f => Files.size0(Dir.resolve(f)))

  extension (files: Files.type) private def size0(p: Path): Boolean = Files.isRegularFile(p) && Files.size(p) > 0

  def requireAvailable(): Unit =
    Tier.require(available, s"no SPIRE SVIDs in $Dir - run modules/it/spiffe/up.sh")

  private def keys(): JWKSet =
    val json = ujson.read(Files.readString(Dir.resolve("bundle.json")))
    // A bare JWKS, or a map of trust domain to JWKS.
    val jwks = if json.obj.contains("keys") then json else json.obj.getOrElse(TrustDomain, json.obj.values.head)
    JWKSet.parse(jwks.render())

  /** Accepts only a JWT-SVID signed by the stack's trust domain, for `expectedAudience`, unexpired. */
  def validator(expectedAudience: String): String => Either[String, Unit] = token =>
    Try {
      val jwt    = SignedJWT.parse(token)
      val claims = jwt.getJWTClaimsSet
      val key    = keys().getKeyByKeyId(jwt.getHeader.getKeyID)
      val verified = Option(key).exists { k =>
        val verifier = DefaultJWSVerifierFactory().createJWSVerifier(jwt.getHeader, k.toECKey.toPublicKey)
        jwt.verify(verifier)
      }
      if !verified then Left("bad signature")
      else if claims.getIssuer != Issuer then Left(s"issuer ${claims.getIssuer}")
      else if !claims.getAudience.asScala.contains(expectedAudience) then Left(s"audience ${claims.getAudience}")
      else if !claims.getSubject.startsWith(s"spiffe://$TrustDomain/") then Left(s"subject ${claims.getSubject}")
      else if claims.getExpirationTime.toInstant.isBefore(Instant.now()) then Left("expired")
      else Right(())
    }.toEither.left.map(_.getMessage).flatten
```

(Clean up the `size0` extension into a plain private method if the formatter or compiler complains; it only checks a non-empty file. If SPIRE's `jwt_key_type` is RSA in your config, use `k.toRSAKey.toPublicKey` - the default is `ec-p256`.)

```scala
// modules/it/src/test/scala/org/llm4s/it/spiffe/SpiffeStackSpec.scala
package org.llm4s.it.spiffe

import org.llm4s.it.tags.Spiffe
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.Files

@Spiffe
class SpiffeStackSpec extends AnyWordSpec with Matchers:
  "the SPIRE stack" should {
    "issue SVIDs that verify for their own audience only" in {
      SpiffeStack.requireAvailable()
      val token = Files.readString(SpiffeStack.svid(SpiffeStack.Databricks)).trim
      SpiffeStack.validator(SpiffeStack.Databricks)(token) shouldBe Right(())
      SpiffeStack.validator(SpiffeStack.Anthropic)(token).isLeft shouldBe true
    }
    "reject a tampered SVID" in {
      SpiffeStack.requireAvailable()
      val token = Files.readString(SpiffeStack.svid(SpiffeStack.Databricks)).trim
      val tampered = token.dropRight(4) + (if token.endsWith("AAAA") then "BBBB" else "AAAA")
      SpiffeStack.validator(SpiffeStack.Databricks)(tampered).isLeft shouldBe true
    }
  }
```

Run: `sbt it/itTierCheck testSpiffe`
Expected: `itTierCheck` lists `SpiffeStackSpec` as `Spiffe`; both tests PASS with the stack up. With the stack down (`modules/it/spiffe/down.sh`), `sbt testSpiffe` reports them cancelled.

- [ ] **Step 6: CI job**

Add to `.github/workflows/ci.yml`, modelled on `ollama-integration` (lines 596-637), but running on pull requests too:

```yaml
  # Tier: SPIFFE - real SPIRE-issued JWT-SVIDs driving the workload-identity auth of
  # openai-compatible (Databricks), openai and anthropic against in-JVM fakes.
  spiffe-integration:
    name: SPIFFE Integration
    needs: quick-checks
    runs-on: ubuntu-latest
    permissions:
      contents: read
    steps:
      - uses: actions/checkout@v7
      - uses: actions/setup-java@v6
        with:
          distribution: 'temurin'
          java-version: 21
          cache: 'sbt'
      - uses: sbt/setup-sbt@v1
      - name: Start SPIRE
        run: modules/it/spiffe/up.sh
      - name: Run SPIFFE integration tests
        env:
          LLM4S_IT_STRICT: "true"
        run: sbt testSpiffe
      - name: SPIRE logs
        if: failure()
        run: cd modules/it/spiffe && JOIN_TOKEN=x docker compose logs
```

- [ ] **Step 7: Commit**

```bash
sbt scalafmtAll
git add modules/it project build.sbt .github/workflows/ci.yml
git commit -s -m "test(it): @Spiffe tier with a real SPIRE stack issuing JWT-SVIDs

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 10: Layer 3 - Databricks-shaped flow with real SVIDs

**Files:**
- Test: `modules/it/src/test/scala/org/llm4s/it/spiffe/SpiffeDatabricksSpec.scala`

**Interfaces:**
- Consumes: `SpiffeStack` (Task 9); `FakeTokenExchangeServer` (Task 4); `OpenAICompatibleProvider`, `ProviderModuleChecks.assertBuildsClient`, `ProviderTestConfig.loadSection` (Tasks 5, testkit).

- [ ] **Step 1: Write the spec**

```scala
// modules/it/src/test/scala/org/llm4s/it/spiffe/SpiffeDatabricksSpec.scala
package org.llm4s.it.spiffe

import org.llm4s.error.AuthenticationError
import org.llm4s.it.tags.Spiffe
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, UserMessage }
import org.llm4s.llmconnect.provider.OpenAICompatibleProvider
import org.llm4s.testkit.{ FakeTokenExchangeServer, ProviderModuleChecks, ProviderTestConfig }
import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.{ Files, Path }

@Spiffe
class SpiffeDatabricksSpec extends AnyWordSpec with Matchers with EitherValues with ProviderModuleChecks:

  private given ProviderRegistry = ProviderRegistry.default

  /** The section `llm4s.providers.main` with `body`, as validation leaves it. */
  private def sectionResult(body: String): Result[NamedProviderConfig] =
    ProviderTestConfig.loadSection("main", s"llm4s.providers.main {\n$body\n}")

  private def sectionOf(body: String): NamedProviderConfig =
    sectionResult(body).fold(error => fail(error.message), identity)

  private val conversation = Conversation(Seq(UserMessage("hi")))

  private def client(fake: FakeTokenExchangeServer, svid: Path): LLMClient =
    assertBuildsClient(
      OpenAICompatibleProvider,
      sectionOf(
        s"""provider = "openai-compatible"
           |model    = "databricks-model"
           |baseUrl  = "${fake.baseUrl}/serving-endpoints"
           |auth {
           |  identityTokenFile = "$svid"
           |  tokenUrl = "${fake.baseUrl}${FakeTokenExchangeServer.TokenPath}"
           |  clientId = "sp-uuid"
           |  scope    = "all-apis"
           |}""".stripMargin
      )
    )

  "openai-compatible with a SPIRE-issued SVID" should {
    "pass the fake Databricks exchange's signature check, then complete and stream" in FakeTokenExchangeServer.withServer { fake =>
      SpiffeStack.requireAvailable()
      fake.setSubjectValidator(SpiffeStack.validator(SpiffeStack.Databricks))
      val c = client(fake, SpiffeStack.svid(SpiffeStack.Databricks))
      c.complete(conversation, CompletionOptions()).isRight shouldBe true
      assertStreams(c)
      fake.issuedTokens shouldBe Seq("t1")
    }

    "re-exchange with the rotated SVID after it is rewritten" in FakeTokenExchangeServer.withServer { fake =>
      SpiffeStack.requireAvailable()
      fake.setSubjectValidator(SpiffeStack.validator(SpiffeStack.Databricks))
      fake.setExpiresIn(0) // every call exchanges, so the file is read each time
      val file   = SpiffeStack.svid(SpiffeStack.Databricks)
      val c      = client(fake, file)
      val before = Files.readString(file).trim
      c.complete(conversation, CompletionOptions()).isRight shouldBe true
      // SVID TTL is 60s; spiffe-helper rewrites the file before expiry.
      val deadline = System.nanoTime() + 90_000_000_000L
      while Files.readString(file).trim == before && System.nanoTime() < deadline do Thread.sleep(1000)
      Files.readString(file).trim should not be before
      c.complete(conversation, CompletionOptions()).isRight shouldBe true
      fake.exchanges.map(_("subject_token")).distinct.size shouldBe 2
    }

    "reject an SVID minted for another audience at the exchange" in FakeTokenExchangeServer.withServer { fake =>
      SpiffeStack.requireAvailable()
      fake.setSubjectValidator(SpiffeStack.validator(SpiffeStack.Databricks))
      val c = client(fake, SpiffeStack.svid(SpiffeStack.Anthropic))
      c.complete(conversation, CompletionOptions()).left.value shouldBe an[AuthenticationError]
      fake.apiAuthorizations shouldBe empty
    }

    "reject a tampered SVID at the exchange" in FakeTokenExchangeServer.withServer { fake =>
      SpiffeStack.requireAvailable()
      fake.setSubjectValidator(SpiffeStack.validator(SpiffeStack.Databricks))
      val original = Files.readString(SpiffeStack.svid(SpiffeStack.Databricks)).trim
      val tampered = Files.createTempFile("tampered", ".jwt")
      Files.writeString(tampered, original.dropRight(4) + (if original.endsWith("AAAA") then "BBBB" else "AAAA"))
      client(fake, tampered).complete(conversation, CompletionOptions()).left.value shouldBe an[AuthenticationError]
    }
  }
```

- [ ] **Step 2: Run it**

Run: `modules/it/spiffe/up.sh && sbt "it/testOnly org.llm4s.it.spiffe.SpiffeDatabricksSpec"`
Expected: PASS (the rotation test takes up to ~60 s).

- [ ] **Step 3: Commit**

```bash
sbt scalafmtAll
git add modules/it/src/test/scala/org/llm4s/it/spiffe/SpiffeDatabricksSpec.scala
git commit -s -m "test(it): openai-compatible workload identity with real SPIRE SVIDs

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 11: Layer 4 - the Anthropic SDK with real SVIDs

**Files:**
- Test: `modules/it/src/test/scala/org/llm4s/it/spiffe/SpiffeAnthropicSpec.scala`

**Interfaces:**
- Consumes: `SpiffeStack` (Task 9); `FakeTokenExchangeServer` (Task 4); `AnthropicProvider` (Task 7).

- [ ] **Step 1: Write the spec**

```scala
// modules/it/src/test/scala/org/llm4s/it/spiffe/SpiffeAnthropicSpec.scala
package org.llm4s.it.spiffe

import org.llm4s.it.tags.Spiffe
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, UserMessage }
import org.llm4s.llmconnect.provider.AnthropicProvider
import org.llm4s.testkit.{ FakeTokenExchangeServer, ProviderModuleChecks, ProviderTestConfig }
import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.types.Result
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

@Spiffe
class SpiffeAnthropicSpec extends AnyWordSpec with Matchers with ProviderModuleChecks:

  private given ProviderRegistry = ProviderRegistry.default

  /** The section `llm4s.providers.main` with `body`, as validation leaves it. */
  private def sectionResult(body: String): Result[NamedProviderConfig] =
    ProviderTestConfig.loadSection("main", s"llm4s.providers.main {\n$body\n}")

  private def sectionOf(body: String): NamedProviderConfig =
    sectionResult(body).fold(error => fail(error.message), identity)

  private val conversation = Conversation(Seq(UserMessage("hi")))

  "the Anthropic SDK with a SPIRE-issued SVID" should {
    "present a verified SVID with the jwt-bearer grant and call messages" in FakeTokenExchangeServer.withServer { fake =>
      SpiffeStack.requireAvailable()
      fake.setSubjectValidator(SpiffeStack.validator(SpiffeStack.Anthropic))
      val client = assertBuildsClient(
        AnthropicProvider,
        sectionOf(
          s"""provider = "anthropic"
             |model = "claude-test"
             |baseUrl = "${fake.baseUrl}"
             |auth {
             |  identityTokenFile = "${SpiffeStack.svid(SpiffeStack.Anthropic)}"
             |  federationRuleId = "fdrl_1"
             |  organizationId = "org_1"
             |}""".stripMargin
        )
      )
      client.complete(conversation, CompletionOptions()).isRight shouldBe true
      fake.issuedTokens should not be empty
      fake.apiAuthorizations.last shouldBe s"Bearer ${fake.issuedTokens.last}"
    }

    "re-exchange the SVID after a forced 401" in FakeTokenExchangeServer.withServer { fake =>
      SpiffeStack.requireAvailable()
      fake.setSubjectValidator(SpiffeStack.validator(SpiffeStack.Anthropic))
      val client = assertBuildsClient(
        AnthropicProvider,
        sectionOf(
          s"""provider = "anthropic"
             |model = "claude-test"
             |baseUrl = "${fake.baseUrl}"
             |auth { identityTokenFile = "${SpiffeStack.svid(SpiffeStack.Anthropic)}", federationRuleId = "f", organizationId = "o" }""".stripMargin
        )
      )
      client.complete(conversation, CompletionOptions()).isRight shouldBe true
      fake.rejectNextApiCalls(1)
      client.complete(conversation, CompletionOptions()).isRight shouldBe true
      // Mirror whatever Task 7 established about the SDK's 401 behaviour; with refresh, two tokens.
      fake.issuedTokens.size should be >= 2
    }

    "refuse an SVID for another audience" in FakeTokenExchangeServer.withServer { fake =>
      SpiffeStack.requireAvailable()
      fake.setSubjectValidator(SpiffeStack.validator(SpiffeStack.Anthropic))
      val client = assertBuildsClient(
        AnthropicProvider,
        sectionOf(
          s"""provider = "anthropic"
             |model = "claude-test"
             |baseUrl = "${fake.baseUrl}"
             |auth { identityTokenFile = "${SpiffeStack.svid(SpiffeStack.Databricks)}", federationRuleId = "f", organizationId = "o" }""".stripMargin
        )
      )
      client.complete(conversation, CompletionOptions()).isLeft shouldBe true
      fake.apiAuthorizations shouldBe empty
    }
  }
```

- [ ] **Step 2: Run it**

Run: `sbt "it/testOnly org.llm4s.it.spiffe.SpiffeAnthropicSpec"`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
sbt scalafmtAll
git add modules/it/src/test/scala/org/llm4s/it/spiffe/SpiffeAnthropicSpec.scala
git commit -s -m "test(it): Anthropic SDK workload identity with real SPIRE SVIDs

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 12: Layer 4 - the OpenAI SDK with real SVIDs (intercepting proxy)

**Files:**
- Create: `modules/it/src/test/scala/org/llm4s/it/spiffe/InterceptingTlsProxy.scala`
- Test: `modules/it/src/test/scala/org/llm4s/it/spiffe/SpiffeOpenAISpec.scala`
- Modify (if needed): `modules/openai/src/main/scala/org/llm4s/llmconnect/provider/OpenAIClient.scala` - a `private[llm4s]` constructor taking an `OpenAIClientTransport`, so the IT spec (package `org.llm4s.it.spiffe`) can inject `OpenAIClientTransport.openAI(config, customize)`. Prefer widening the existing `forTest`/`forProvider` to `private[llm4s]` over adding a new entry point.

**Interfaces:**
- Consumes: `OpenAIClientTransport.openAI(config, customize)` (Task 6); `OpenAIConfig`, `OpenAIWorkloadIdentity` (Task 6); `SpiffeStack`, `FakeTokenExchangeServer` (Tasks 9, 4).
- Produces: `final class InterceptingTlsProxy(target: InetSocketAddress, keyStore: Path, password: Array[Char]) extends AutoCloseable` with `port: Int`, `trustManager: X509TrustManager`, `requests: Seq[String]` (CONNECT targets seen); `InterceptingTlsProxy.keyStoreFor(host: String, dir: Path): Path` (generates a PKCS12 keystore with `keytool`, CN and SAN = `host`).

The SDK posts its token exchange to the hard-coded `https://auth.openai.com/oauth/token`. The spec points the SDK's OkHttp client at a local HTTP proxy, trusts a throwaway certificate for `auth.openai.com`, and the proxy terminates TLS and pipes the decrypted request to the fake (path `/oauth/token` -> add that path to the fake in this task, see Step 2).

- [ ] **Step 1: Check the risk first**

Write only this test, before the proxy:

```scala
    "route the SDK's token exchange through the configured OkHttp client" in {
      // Build OpenAIClientTransport.openAI(config, _.proxy(new Proxy(Proxy.Type.HTTP, InetSocketAddress("localhost", 9))))
      // with a workloadIdentity, call createChatCompletion, and assert the failure is a
      // connection error to localhost:9 (the proxy), not a DNS/TLS error for auth.openai.com.
    }
```

Run it. If the failure is to the proxy, continue with Steps 2-5. If the SDK reaches `auth.openai.com` directly (bypassing the proxy), the token client is not the builder's OkHttp client: skip Steps 2-4, and instead test `com.openai.auth.WorkloadIdentityAuth(sdkWorkloadIdentity(wi), httpClient, jsonMapper)` directly with an `httpClient` that records the request (a small `com.openai.core.http.HttpClient` implementation returning a canned `{"access_token":"t1","expires_in":3600}`), asserting the recorded body carries the real SVID from `SpiffeStack.svid(SpiffeStack.OpenAI)` and `SpiffeStack.validator(SpiffeStack.OpenAI)` accepts it. Record the gap in the PR description ("OpenAI SDK token client not proxyable; exchange verified at the SDK seam").

- [ ] **Step 2: Fake path for OpenAI's exchange**

In `FakeTokenExchangeServer` (testkit), add `val OpenAITokenPath: String = "/oauth/token"` and a context `server.createContext(OpenAITokenPath, ex => exchange(ex, formOrJson(body(ex)), "subject_token"))` where `formOrJson` uses `jsonFields` when the body starts with `{` and `formFields` otherwise (the SDK's encoding is not documented; this accepts both). Add a case to `FakeTokenExchangeServerSpec` posting JSON to it.

- [ ] **Step 3: The proxy**

```scala
// modules/it/src/test/scala/org/llm4s/it/spiffe/InterceptingTlsProxy.scala
package org.llm4s.it.spiffe

import java.io.{ InputStream, OutputStream }
import java.net.{ InetSocketAddress, ServerSocket, Socket }
import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }
import java.security.KeyStore
import java.util.concurrent.Executors
import javax.net.ssl.*
import scala.collection.mutable
import scala.util.{ Try, Using }

/**
 * An HTTP proxy for tests: a CONNECT is answered, TLS is terminated with `keyStore`'s certificate,
 * and the decrypted bytes are piped to `target`; a plain request is piped to `target` as is.
 */
final class InterceptingTlsProxy(target: InetSocketAddress, keyStore: Path, password: Array[Char]) extends AutoCloseable:
  private val ks = KeyStore.getInstance("PKCS12")
  Using.resource(Files.newInputStream(keyStore))(ks.load(_, password))

  private val sslContext =
    val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
    kmf.init(ks, password)
    val ctx = SSLContext.getInstance("TLS")
    ctx.init(kmf.getKeyManagers, null, null)
    ctx

  /** Trusts exactly the proxy's certificate. */
  val trustManager: X509TrustManager =
    val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
    tmf.init(ks)
    tmf.getTrustManagers.collectFirst { case x: X509TrustManager => x }.get

  private val server  = new ServerSocket(0)
  private val pool    = Executors.newVirtualThreadPerTaskExecutor()
  private val seen    = mutable.Buffer.empty[String]
  val port: Int       = server.getLocalPort
  def requests: Seq[String] = seen.synchronized(seen.toSeq)

  pool.submit((() => while !server.isClosed do Try(server.accept()).foreach(s => pool.submit((() => handle(s)): Runnable))): Runnable)

  private def handle(client: Socket): Unit = Try {
    val in   = client.getInputStream
    val head = readHead(in)
    val first = head.linesIterator.next()
    seen.synchronized(seen += first)
    val upstream = new Socket(target.getHostString, target.getPort)
    if first.startsWith("CONNECT ") then
      client.getOutputStream.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.US_ASCII))
      client.getOutputStream.flush()
      val tls = sslContext.getSocketFactory.createSocket(client, null, client.getPort, false).asInstanceOf[SSLSocket]
      tls.setUseClientMode(false)
      tls.startHandshake()
      pipeBoth(tls.getInputStream, tls.getOutputStream, upstream)
    else
      // Absolute-form request line ("POST http://localhost:port/path HTTP/1.1") -> origin-form.
      val rewritten = head.replaceFirst("^(\\S+) https?://[^/]+", "$1 ")
      upstream.getOutputStream.write(rewritten.getBytes(StandardCharsets.ISO_8859_1))
      pipeBoth(in, client.getOutputStream, upstream)
  }: Unit

  private def readHead(in: InputStream): String =
    val out = new java.io.ByteArrayOutputStream()
    var last4 = 0
    while last4 != 0x0d0a0d0a do
      val b = in.read()
      if b < 0 then return out.toString(StandardCharsets.ISO_8859_1)
      out.write(b)
      last4 = (last4 << 8) | b
    out.toString(StandardCharsets.ISO_8859_1)

  private def pipeBoth(clientIn: InputStream, clientOut: OutputStream, upstream: Socket): Unit =
    pool.submit((() => Try(clientIn.transferTo(upstream.getOutputStream))): Runnable)
    Try(upstream.getInputStream.transferTo(clientOut))

  def close(): Unit =
    server.close()
    pool.shutdownNow(): Unit

object InterceptingTlsProxy:
  val Password: Array[Char] = "changeit".toCharArray

  /** A PKCS12 keystore with a self-signed certificate for `host`, made with the JDK's keytool. */
  def keyStoreFor(host: String, dir: Path): Path =
    val file    = dir.resolve(s"$host.p12")
    Files.deleteIfExists(file)
    val keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString
    val exit = new ProcessBuilder(
      keytool, "-genkeypair", "-alias", "proxy", "-keyalg", "EC", "-groupname", "secp256r1",
      "-dname", s"CN=$host", "-ext", s"SAN=dns:$host", "-validity", "2",
      "-storetype", "PKCS12", "-keystore", file.toString, "-storepass", "changeit", "-keypass", "changeit"
    ).inheritIO().start().waitFor()
    require(exit == 0, s"keytool failed with exit code $exit")
    file
```

(The `return` inside `readHead` and the `.get` are test-only conveniences; if scalafix or the compiler objects, restructure `readHead` as a tail-recursive helper. No checked-in keystore: it is generated per run.)

- [ ] **Step 4: The spec**

```scala
// modules/it/src/test/scala/org/llm4s/it/spiffe/SpiffeOpenAISpec.scala
package org.llm4s.it.spiffe

import org.llm4s.it.tags.Spiffe
import org.llm4s.llmconnect.auth.IdentitySource
import org.llm4s.llmconnect.config.{ OpenAIConfig, OpenAIWorkloadIdentity }
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, UserMessage }
import org.llm4s.llmconnect.provider.{ OpenAIClient, OpenAIClientTransport }
import org.llm4s.testkit.FakeTokenExchangeServer
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.net.{ InetSocketAddress, Proxy, URI }
import java.nio.file.Files
import javax.net.ssl.HttpsURLConnection

@Spiffe
class SpiffeOpenAISpec extends AnyWordSpec with Matchers:

  "the OpenAI SDK with a SPIRE-issued SVID" should {
    "exchange a verified SVID at auth.openai.com (intercepted) and call chat completions" in FakeTokenExchangeServer.withServer { fake =>
      SpiffeStack.requireAvailable()
      fake.setSubjectValidator(SpiffeStack.validator(SpiffeStack.OpenAI))
      val dir   = Files.createTempDirectory("proxy")
      val fakeAddr = InetSocketAddress("localhost", URI.create(fake.baseUrl).getPort)
      val proxy = InterceptingTlsProxy(fakeAddr, InterceptingTlsProxy.keyStoreFor("auth.openai.com", dir), InterceptingTlsProxy.Password)
      val config = OpenAIConfig(
        apiKey = "",
        model = "gpt-test",
        organization = None,
        baseUrl = s"${fake.baseUrl}/v1",
        contextWindow = 8192,
        reserveCompletion = 1024,
        workloadIdentity = Some(OpenAIWorkloadIdentity(IdentitySource.File(SpiffeStack.svid(SpiffeStack.OpenAI)), "idp_1", "sa_1"))
      )
      val transport = OpenAIClientTransport.openAI(
        config,
        _.proxy(new Proxy(Proxy.Type.HTTP, InetSocketAddress("localhost", proxy.port)))
          .sslSocketFactory(sslFactoryTrusting(proxy))
          .trustManager(proxy.trustManager)
          .hostnameVerifier(HttpsURLConnection.getDefaultHostnameVerifier)
      )
      val client = OpenAIClient.forTest(config, transport) // use the private[llm4s] entry point from this task's Files list
      try
        client.complete(Conversation(Seq(UserMessage("hi"))), CompletionOptions()).isRight shouldBe true
        proxy.requests.exists(_.startsWith("CONNECT auth.openai.com:443")) shouldBe true
        fake.issuedTokens should not be empty
        fake.apiAuthorizations.last shouldBe s"Bearer ${fake.issuedTokens.last}"
      finally proxy.close()
    }
  }

  private def sslFactoryTrusting(proxy: InterceptingTlsProxy) =
    val ctx = javax.net.ssl.SSLContext.getInstance("TLS")
    ctx.init(null, Array(proxy.trustManager), null)
    ctx.getSocketFactory
```

Replace `try ... finally` with `Using.resource(proxy) { _ => ... }` - scalafix bans `try`/`finally` (the snippet shows intent). Match `OpenAIClient.forTest`'s real parameters.

- [ ] **Step 5: Run it**

Run: `sbt "it/testOnly org.llm4s.it.spiffe.SpiffeOpenAISpec"`
Expected: PASS. If OkHttp refuses the proxied plain-HTTP API call to `localhost` (it may bypass the proxy for localhost - then the API call goes direct, which is fine), keep the assertions on the exchange and the `Authorization` header.

- [ ] **Step 6: Full tier, then commit and open PR 2**

Run: `sbt it/itTierCheck testSpiffe && modules/it/spiffe/down.sh`
Expected: all `@Spiffe` suites PASS.

```bash
sbt scalafmtAll
git add modules/it modules/openai modules/provider-testkit
git commit -s -m "test(it): OpenAI SDK workload identity with real SPIRE SVIDs via an intercepting proxy

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
git push -u origin feat/spiffe-it-tier
gh pr create --base feat/spiffe-workload-identity --title "test(it): @Spiffe tier - real SPIRE SVIDs for workload identity" --body "$(cat <<'EOF'
Adds the `@Spiffe` integration tier: SPIRE server + agent + spiffe-helper in Docker Compose
(modules/it/spiffe), CI job `spiffe-integration`, and suites driving openai-compatible
(Databricks-shaped exchange), the Anthropic SDK and the OpenAI SDK with real JWT-SVIDs verified
against SPIRE's trust bundle. An admin needs to add `SPIFFE Integration` to the required checks.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```
