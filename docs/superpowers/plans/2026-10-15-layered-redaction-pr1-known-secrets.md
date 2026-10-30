# Layered Redaction, PR 1 (Layer A: known secrets): Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** llm4s never emits a credential it holds. Every credential becomes a self-registering `Secret`, and every `Redaction` entry point removes all registered values by exact match before doing anything else.

**Architecture:**
- `Secret` is a public class: `toString` is `***`, the raw value is read through `reveal`, and creating one registers it.
- `KnownSecrets` is a process-wide registry. It is matched with the existing Aho–Corasick `ExactMatcher`, which is rebuilt copy-on-write when the registry changes.
- `ApiKey` and every config credential field become `Secret`.
- Runtime tokens register themselves when they are obtained.
- The per-caller `secrets` parameters are deleted, because the registry replaces them.

This is PR 1 of 4. PRs 2–4 (the trace wrapper, structural redaction B1, coarse text redaction B2) each get their own plan once this one merges.

**Tech Stack:** Scala 3.7.1, sbt, ScalaTest, ScalaCheck (`scalatestplus` `scalacheck-1-18`, a test dependency of every module through `commonSettings`), `LocalProviderTestServer` from `llm4s-provider-testkit`.

**Refreshed** against `main` at `2931f2ee8` (2026-10-30): line numbers, paths, names and sbt commands. Line numbers are approximate; `grep -n` before editing.

**Spec:** `docs/superpowers/specs/2026-10-15-layered-redaction-design.md`. Read the "Guarantees", "Secret", "KnownSecrets" and "Delivery → 1" sections.

## Global Constraints

- **Error handling.** No `try`/`catch`/`finally` keywords, outside the safety packages. Use `Result`, `Try(...).toResult` or `Using`.
- **Configuration.** No `sys.env` or `System.getenv`. Configuration is read only through the existing loaders.
- **Commits.** Every commit uses `git commit -s` and ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- **API changes.** This is pre-1.0, so break APIs outright, with no shims and no `@deprecated`. Record each break in the CHANGELOG and in `docs/reference/migration.md`.
- **Stability annotations.** A new top-level public type in `llm4s-core` needs `@Stable` or `@Experimental` (from `org.llm4s.annotation`); `sbt stabilityTierCheck` (a root task) enforces this.
- **Growth-prone configs** (`final case class X private (...)`, such as `EmbeddingProviderConfig` and `NamedProviderConfig`). Change the credential's type in the constructor, in every `apply` overload (the defaults one and the older forwarding one) and in its `with*` setter(s).
- **Durations** are `FiniteDuration`.
- **Coverage floors** are never lowered.
- **CHANGELOG** (rules in CLAUDE.md, "Reading files and output efficiently"). `[Unreleased]` is organised by area.
  - Add one entry of 1–2 lines under `### Security`.
  - Add one line under `### Breaking changes`.
  - Never add a duplicate heading.
- **Spec values to use verbatim:**
  - minimum registered length: **8** characters;
  - forms registered: **raw, JSON-escaped, URL-encoded** (plus base64 of `user:password` for Basic);
  - placeholder: **`[REDACTED]`**;
  - `Secret.toString`: **`***`**.
- **Review rule** (spec, "Review rule"). This PR is layer A, so any finding where a registered credential is emitted is a defect.

## Rulings (deviations from the spec)

- **`Secret.bearer` is not added.** A Bearer header carries the raw token, which `Secret(token)` / `Secret.token(token)` already registers, so a separate builder would add nothing. If wrong, adding one later costs one method.
- **`remoteBody` and `scrubRemote` stay in PR 1, without their `secrets` parameter.** The spec removes them when the facade is consolidated in PR 4. Until then they still do one job nothing else does: they scrub a body's own sensitive JSON values wherever those values are repeated elsewhere in the body (`sensitiveJsonValues`). If wrong, PR 4 deletes them as planned anyway.

## Review Focus

These are inputs and conditions the spec implies but does not test directly. Each one has a test in the task named.

1. **A token that rotates while an error body echoing the old token is still in flight.** The old token must still be masked. A token source must therefore forget only the generation *before* the previous one, never the token it has just replaced. Pinned in Task 4: `TokenRotationSpec`, "a body echoing the token just replaced is still masked".
2. **A configured credential shorter than 8 characters**, such as a local test key `abc`. It is not registered, which is documented. One DEBUG line is logged per value, and no exception is thrown. Pinned in Task 1: `KnownSecretsSpec`, "refuses a value under 8 characters and logs once".
3. **A config printed without a client ever being built**, as `config-policy`'s Doctor and `toString` do. The credential must still be masked through `Secret.toString`. Pinned in Task 3: `SecretConfigToStringSpec`.
4. **Configs compared by value** in user code and in tests. Two configs built from the same key must compare equal, so `Secret` has value equality. Pinned in Task 1: `SecretSpec`, "is equal by value and hashes by value".
5. **Java callers.** The `java-api` builders take `String` keys and must wrap them in a `Secret`, so that a Java user's key is registered too. Pinned in Task 3: a `javaApi` test that the key passed to the builder is masked in an error body.

---

## File structure

- **Create** `modules/core/src/main/scala/org/llm4s/security/Secret.scala`: the public `Secret` class and its companion.
- **Create** `modules/core/src/main/scala/org/llm4s/security/KnownSecrets.scala`: the `private[llm4s]` registry.
- **Create** `modules/core/src/main/scala/org/llm4s/util/ExactMatcher.scala`: the existing Aho–Corasick matcher, moved out of `Redaction.scala` unchanged except that it becomes `private[llm4s]`.
- **Modify** `modules/core/src/main/scala/org/llm4s/util/Redaction.scala`:
  - every public entry point scrubs `KnownSecrets` first;
  - delete `scrub(input, secrets, …)` and `identifiers`;
  - drop the `secrets` parameter from `remoteBody` and `scrubRemote`.
- **Modify** `modules/core/src/main/scala/org/llm4s/types/ProviderModelTypes.scala`: `ApiKey` becomes `Secret`.
- **Modify** every config with a credential field (listed in Task 3), their loaders, and the `java-api` builders.
- **Modify** the runtime token sources (Task 4):
  - `VertexAIAuthProvider`;
  - `WatsonXClient`;
  - `TokenExchange`;
  - `CachingAccessTokenProvider` (the cache behind `TokenExchange` and `llm4s-azure-identity`'s `AzureEntraId`);
  - the OpenAI, Anthropic and OpenAI-compatible `credentialSecrets`;
  - Langfuse Basic auth.
- **Create** the tests named in each task.
- **Create** `modules/provider-testkit/src/main/scala/org/llm4s/testkit/CredentialEchoChecks.scala`: a shared end-to-end echo check (Task 5).
- **Modify** the docs: `CHANGELOG.md`, `docs/reference/migration.md`, `docs/reference/security.md`.

---

### Task 1: `Secret` and `KnownSecrets`

**Files:**
- Create: `modules/core/src/main/scala/org/llm4s/util/ExactMatcher.scala`. Move `ExactMatcher` (class and companion) and `jsonEscaped` out of `Redaction.scala`, at about lines 2168–2264. Make them `private[llm4s]`.
- Modify: `modules/core/src/main/scala/org/llm4s/util/Redaction.scala`. Use the moved `ExactMatcher` and delete the private copy.
- Create: `modules/core/src/main/scala/org/llm4s/security/Secret.scala`
- Create: `modules/core/src/main/scala/org/llm4s/security/KnownSecrets.scala`
- Test: `modules/core/src/test/scala/org/llm4s/security/SecretSpec.scala`
- Test: `modules/core/src/test/scala/org/llm4s/security/KnownSecretsSpec.scala`

**Interfaces:**
- Produces:
  - `final class Secret private (value: String)`, with `def reveal: String`, `override def toString = "***"`, and value-based `equals`/`hashCode`.
  - `object Secret`:
    - `def apply(value: String): Secret` (kind `Configured`);
    - `def token(value: String): Secret` (kind `Token`, evictable);
    - `def basic(user: String, password: String): Secret` (registers the password, and the base64 of `user:password` as `Configured`).
  - `private[llm4s] object KnownSecrets`:
    - `def register(value: String, kind: Kind): Unit`;
    - `def registerIdentifier(value: String): Unit`;
    - `def forget(value: String): Unit`;
    - `def scrub(text: String, placeholder: String): String`;
    - `def isolated[A](body: => A): A` (test hook);
    - `enum Kind { case Configured, Token, Identifier }`;
    - `val MinLength = 8`;
    - `val Cap = 4096`.

- [ ] **Step 1: Move `ExactMatcher` and confirm nothing changes**

Cut `final private class ExactMatcher`, `private object ExactMatcher` and `jsonEscaped` from `Redaction.scala` into `util/ExactMatcher.scala`, as `private[llm4s] final class ExactMatcher` and `private[llm4s] object ExactMatcher`. Put `jsonEscaped` in `object ExactMatcher` as `private[llm4s] def jsonEscaped(s: String): String`. Update the references in `Redaction.scala`.

Run: `sbt "core/testOnly org.llm4s.util.Redaction*"`
Expected: PASS, with the same test count as before the move.

- [ ] **Step 2: Write the failing `SecretSpec`**

```scala
package org.llm4s.security

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SecretSpec extends AnyFlatSpec with Matchers {

  "Secret" should "never show its value in toString" in {
    Secret("sk-live-0123456789abcdef").toString shouldBe "***"
    s"${Secret("sk-live-0123456789abcdef")}" shouldBe "***"
  }

  it should "reveal its value only when asked" in {
    Secret("sk-live-0123456789abcdef").reveal shouldBe "sk-live-0123456789abcdef"
  }

  it should "be equal by value and hash by value" in {
    Secret("sk-live-0123456789abcdef") shouldBe Secret("sk-live-0123456789abcdef")
    Secret("sk-live-0123456789abcdef").hashCode shouldBe Secret("sk-live-0123456789abcdef").hashCode
    Secret("sk-live-0123456789abcdef") should not be Secret("sk-live-fedcba9876543210")
  }

  it should "register itself, so its value is scrubbed from any text" in KnownSecrets.isolated {
    val s = Secret("sk-live-0123456789abcdef")
    KnownSecrets.scrub(s"""{"error":"bad key ${s.reveal}"}""", "[REDACTED]") shouldBe """{"error":"bad key [REDACTED]"}"""
  }

  it should "register the Basic header form" in KnownSecrets.isolated {
    Secret.basic("pk-lf-public-0001", "sk-lf-secret-0001")
    val header = java.util.Base64.getEncoder.encodeToString("pk-lf-public-0001:sk-lf-secret-0001".getBytes("UTF-8"))
    KnownSecrets.scrub(s"Authorization: Basic $header", "[REDACTED]") shouldBe "Authorization: Basic [REDACTED]"
  }
}
```

- [ ] **Step 3: Write the failing `KnownSecretsSpec`**

```scala
package org.llm4s.security

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.{ CountDownLatch, Executors, TimeUnit }

class KnownSecretsSpec extends AnyFlatSpec with Matchers {
  private val P = "[REDACTED]"

  "KnownSecrets" should "scrub the raw, URL-encoded and JSON-escaped forms" in KnownSecrets.isolated {
    val v = "tok/en+with\"quote&amp"
    KnownSecrets.register(v, KnownSecrets.Kind.Configured)
    KnownSecrets.scrub(s"raw=$v", P) shouldBe s"raw=$P"
    KnownSecrets.scrub(s"q=${URLEncoder.encode(v, StandardCharsets.UTF_8)}", P) shouldBe s"q=$P"
    KnownSecrets.scrub("""{"k":"tok/en+with\"quote&amp"}""", P) shouldBe s"""{"k":"$P"}"""
  }

  it should "refuse a value under 8 characters and log once" in KnownSecrets.isolated {
    KnownSecrets.register("abc", KnownSecrets.Kind.Configured)
    KnownSecrets.register("abc", KnownSecrets.Kind.Configured)
    KnownSecrets.scrub("key abc", P) shouldBe "key abc"
    KnownSecrets.refusedCount shouldBe 1
  }

  it should "forget a value" in KnownSecrets.isolated {
    KnownSecrets.register("token-0123456789", KnownSecrets.Kind.Token)
    KnownSecrets.forget("token-0123456789")
    KnownSecrets.scrub("token-0123456789", P) shouldBe "token-0123456789"
  }

  it should "evict tokens before configured keys at the cap" in KnownSecrets.isolated {
    KnownSecrets.register("configured-key-0001", KnownSecrets.Kind.Configured)
    (0 until KnownSecrets.Cap).foreach(i => KnownSecrets.register(f"token-$i%08d", KnownSecrets.Kind.Token))
    KnownSecrets.scrub("configured-key-0001", P) shouldBe P
    KnownSecrets.size should be <= KnownSecrets.Cap
  }

  it should "leave text alone when nothing is registered" in KnownSecrets.isolated {
    KnownSecrets.scrub("nothing secret here", P) shouldBe "nothing secret here"
  }

  it should "be safe to register while other threads scan" in KnownSecrets.isolated {
    val pool  = Executors.newFixedThreadPool(8)
    val start = new CountDownLatch(1)
    val done  = new CountDownLatch(8)
    (0 until 8).foreach { t =>
      pool.execute { () =>
        start.await()
        (0 until 200).foreach { i =>
          val v = f"conc-$t%02d-$i%06d"
          KnownSecrets.register(v, KnownSecrets.Kind.Token)
          KnownSecrets.scrub(s"x $v y", P) shouldBe s"x $P y"
        }
        done.countDown()
      }
    }
    start.countDown()
    done.await(30, TimeUnit.SECONDS) shouldBe true
    pool.shutdown()
  }
}
```

- [ ] **Step 4: Run the tests and confirm they fail**

Run: `sbt "core/testOnly org.llm4s.security.*"`
Expected: FAIL to compile, because `Secret` and `KnownSecrets` are not defined.

- [ ] **Step 5: Implement `KnownSecrets`**

```scala
package org.llm4s.security

import org.llm4s.util.ExactMatcher
import org.slf4j.LoggerFactory
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference

/**
 * Every credential llm4s holds, so that diagnostic text can be scrubbed of it by exact match (layer A of
 * the layered redaction design, #1932). A `Secret` registers itself when it is created; a token source
 * registers each token it obtains and forgets the generation before the one it replaced. Scrubbing reads
 * one immutable snapshot, so it takes no lock and is linear in the text.
 */
private[llm4s] object KnownSecrets {
  enum Kind { case Configured, Token, Identifier }

  val MinLength = 8
  val Cap       = 4096

  private val log = LoggerFactory.getLogger(getClass)

  /** Insertion-ordered entries, plus the matcher built from their forms. */
  final private case class State(entries: Vector[(String, Kind)], matcher: Option[ExactMatcher], refused: Set[String])

  private val empty = State(Vector.empty, None, Set.empty)
  private val state = new AtomicReference[State](empty)

  def register(value: String, kind: Kind): Unit = Option(value).map(_.trim).foreach { v =>
    state.updateAndGet { s =>
      if (v.length < MinLength) {
        if (!s.refused.contains(v)) log.debug(s"Not registering a credential shorter than $MinLength characters")
        s.copy(refused = s.refused + v)
      } else if (s.entries.exists(_._1 == v)) s
      else rebuild(evict(s.entries :+ (v -> kind)), s.refused)
    }: Unit
  }

  def registerIdentifier(value: String): Unit = register(value, Kind.Identifier)

  def forget(value: String): Unit = Option(value).map(_.trim).foreach { v =>
    state.updateAndGet(s => rebuild(s.entries.filterNot(_._1 == v), s.refused)): Unit
  }

  def scrub(text: String, placeholder: String): String =
    if (text == null || text.isEmpty) text
    else state.get().matcher.fold(text)(_.replaceAll(text, placeholder))

  private[security] def size: Int         = state.get().entries.size
  private[security] def refusedCount: Int = state.get().refused.size

  /** Runs `body` against an empty registry and restores the previous one afterwards (tests only). */
  def isolated[A](body: => A): A = {
    val saved = state.getAndSet(empty)
    scala.util.Using.resource(new AutoCloseable { def close(): Unit = state.set(saved) })(_ => body)
  }

  private def evict(entries: Vector[(String, Kind)]): Vector[(String, Kind)] =
    if (entries.size <= Cap) entries
    else {
      val overflow  = entries.size - Cap
      val evictable = entries.zipWithIndex.collect { case ((_, k), i) if k != Kind.Configured => i }.take(overflow).toSet
      entries.zipWithIndex.collect { case (e, i) if !evictable(i) => e }.takeRight(Cap)
    }

  private def rebuild(entries: Vector[(String, Kind)], refused: Set[String]): State = {
    val forms = entries.iterator
      .map(_._1)
      .flatMap(v => Iterator(v, URLEncoder.encode(v, StandardCharsets.UTF_8), ExactMatcher.jsonEscaped(v)))
      .toSeq
      .distinct
    State(entries, if (forms.isEmpty) None else Some(ExactMatcher(forms)), refused)
  }
}
```

Note: `Using.resource` restores the registry even when `body` throws, and avoids the banned `try`/`finally`.

- [ ] **Step 6: Implement `Secret`**

```scala
package org.llm4s.security

import org.llm4s.annotation.Stable
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * A credential. Its `toString` is `***`, and its value is read only through [[reveal]]. Creating one
 * registers the value with llm4s, which then removes it from every error message, log line, exchange log
 * and trace it would otherwise appear in. Values under 8 characters are not registered (they would mask
 * ordinary words).
 */
@Stable
final class Secret private (private val value: String) {
  def reveal: String                 = value
  override def toString: String      = "***"
  override def equals(o: Any): Boolean = o match {
    case s: Secret => s.value == value
    case _         => false
  }
  override def hashCode: Int = value.hashCode
}

@Stable
object Secret {
  /** A configured credential: an API key, a password, a client secret. Never evicted from the registry. */
  def apply(value: String): Secret = {
    KnownSecrets.register(value, KnownSecrets.Kind.Configured)
    new Secret(value)
  }

  /** A short-lived token obtained at runtime (OAuth, IAM, token exchange). May be evicted at the registry's cap. */
  def token(value: String): Secret = {
    KnownSecrets.register(value, KnownSecrets.Kind.Token)
    new Secret(value)
  }

  /** HTTP Basic credentials: registers the password and the header's base64 of `user:password`. */
  def basic(user: String, password: String): Secret = {
    KnownSecrets.register(password, KnownSecrets.Kind.Configured)
    Secret(Base64.getEncoder.encodeToString(s"$user:$password".getBytes(StandardCharsets.UTF_8)))
  }
}
```

- [ ] **Step 7: Run the tests and confirm they pass**

Run: `sbt "core/testOnly org.llm4s.security.*" stabilityTierCheck`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add modules/core/src/main/scala/org/llm4s/security modules/core/src/main/scala/org/llm4s/util/ExactMatcher.scala modules/core/src/main/scala/org/llm4s/util/Redaction.scala modules/core/src/test/scala/org/llm4s/security
git commit -s -m "feat(core): Secret and KnownSecrets, a registry of credentials scrubbed by exact match (#1932)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Every `Redaction` entry point scrubs known secrets first

**Files:**
- Modify: `modules/core/src/main/scala/org/llm4s/util/Redaction.scala`
  - `redact` (~:576), `safeBody` (~:134), `redactForLogging` (~:2084), `safe` (~:2109), `remoteBody` (~:2126), `scrubRemote` (~:2135): each scrubs known secrets first.
  - Delete `scrub(input, secrets, placeholder)` (~:2149) and `identifiers` (~:2275).
  - Remove the `secrets` parameter from `remoteBody` and `scrubRemote`.
  - Keep the `sensitiveJsonValues` step inside `scrubRemote`.
- Test: `modules/core/src/test/scala/org/llm4s/util/RedactionKnownSecretsSpec.scala`
- Modify: the existing `RedactionScrubSpec`, `RedactionRemoteBodySpec` and `RedactionPluralKeysSpec` (all in `modules/core/src/test/scala/org/llm4s/util/`). Rewrite the tests that passed `secrets` to register a `Secret` inside `KnownSecrets.isolated` instead. Delete the tests of `identifiers`; Task 4 covers the behaviour that replaces it.

**Interfaces:**
- Consumes: `KnownSecrets.scrub(text, placeholder)` from Task 1.
- Produces:
  - `Redaction.remoteBody(body: String, maxLength: Int = 512): String`
  - `Redaction.scrubRemote(body: String): String`
  - `Redaction.scrubKnown(text: String): String`, which is `KnownSecrets.scrub(text, RedactionPlaceholder)`. PR 2 uses it for traces.

- [ ] **Step 1: Write the failing test**

```scala
package org.llm4s.util

import org.llm4s.security.{ KnownSecrets, Secret }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RedactionKnownSecretsSpec extends AnyFlatSpec with Matchers {
  private val key = "zq8-not-a-vendor-shape-41f0c2"

  // a value no heuristic recognises: only the registry can catch it
  private val shapes = Seq(
    s"plain text $key here",
    s"""{"detail":"unexpected value $key"}""",
    s"weird<<$key>>syntax",
    s"""{"o":"{\\"note\\":\\"$key\\"}"}"""
  )

  "every Redaction entry point" should "remove a registered credential, whatever the syntax" in KnownSecrets.isolated {
    Secret(key)
    shapes.foreach { text =>
      Redaction.redact(text) should not include key
      Redaction.safeBody(text) should not include key
      Redaction.redactForLogging(text) should not include key
      Redaction.safe(text) should not include key
      Redaction.remoteBody(text) should not include key
      Redaction.scrubRemote(text) should not include key
      Redaction.scrubKnown(text) should not include key
    }
  }

  it should "leave an unregistered value of the same shape to the existing rules" in KnownSecrets.isolated {
    Redaction.scrubKnown(s"plain text $key here") shouldBe s"plain text $key here"
  }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `sbt "core/testOnly org.llm4s.util.RedactionKnownSecretsSpec"`
Expected: FAIL. `scrubKnown` does not exist yet, and once it does the key survives `redact`.

- [ ] **Step 3: Implement the change**

In `Redaction.scala`, add:

```scala
  /** Layer A only: removes every credential registered with llm4s, by exact match. Used for trace content. */
  def scrubKnown(text: String): String = KnownSecrets.scrub(text, RedactionPlaceholder)
```

Then make each entry point run `scrubKnown` on its input before doing anything else:
- `redact(input, placeholder)`: begin with `val scrubbed = KnownSecrets.scrub(input, placeholder)`, and use `scrubbed` from then on.
- `safeBody(body, maxLength)`: `val scrubbed = scrubKnown(body)` before `safeBodyPrefix`. Measure the "original length" note on `body.length`, unchanged.
- `redactForLogging` and `safe`: these delegate to `redact`, so they are covered. Confirm by reading them.
- `remoteBody(body, maxLength)`: `redactForLogging(scrubRemote(body), maxLength)`.
- `scrubRemote(body)`: `scrubEcho(scrubKnown(body), sensitiveJsonValues(body))`, where `scrubEcho` is the old `scrub` renamed, made `private`, and taking the echoed JSON values only.
- Delete `identifiers`. Delete the public `scrub`, or make it private as `scrubEcho`.

- [ ] **Step 4: Fix the callers that no longer compile**

Run: `sbt compile 2>&1 | grep -E "\[error\]" | head -40`

Each error is a call that passed `secrets`. For each one, remove the argument; Task 4 replaces the secrets by registration. The known sites are:
- `AnthropicClient.scala:1017` (`mapError`'s `safe`)
- `TokenExchange.scala:233` and `:278`
- `ProviderModelListers.scala:71`
- `OpenAICompatibleClient.scala:120`, `:152` and `:182`
- `OpenAIClient.scala:835` and `:850`

`WatsonXClient` never calls the shared `scrub`; its own private `scrub` is Task 4's. The tests that call the removed parameters are `AnthropicWorkloadIdentitySpec` and `OpenAIWorkloadIdentitySpec` (through `credentialSecrets`/`mapError`) besides the three core specs above.

Leave each `credentialSecrets` method in place for now. Task 4 turns each one into registration.

- [ ] **Step 5: Run the tests and confirm they pass**

Run: `sbt "core/testOnly org.llm4s.util.Redaction*" compile`
Expected: PASS. The rewritten scrub and remote-body specs pass.

- [ ] **Step 6: Commit**

```bash
git commit -s -am "feat(core): every Redaction entry point removes registered credentials first; drop per-caller secrets (#1932)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: `ApiKey` and every config credential become `Secret`

**Files:**
- Modify: `modules/core/src/main/scala/org/llm4s/types/ProviderModelTypes.scala` (~:25 and ~:43–57)
- Modify the configs. In each one, the credential fields change from `String` to `Secret`, and from `Option[String]` to `Option[Secret]`. The "currently" paths are on `main`:
  - core: `llmconnect/config/EmbeddingProviderConfig.scala` (`apiKey`, both `apply` overloads and `withApiKey`), `llmconnect/spi/EmbeddingConfigSpec.scala` (`EmbeddingProviderSection.apiKey`, an `Option`), `config/SharedCredentials.scala` (the resolved `apiKey`, which becomes `Secret`), and `RawNamedProviderSection.apiKey` in `config/ProvidersConfigModel.scala` where it becomes an `ApiKey`. `NamedProviderConfig.apiKey` is already `Option[ApiKey]`, so the `ApiKey` change covers it.
  - openai-compatible: `OpenAIConfig`, `OpenAICompatibleConfig` (`Option`), `DeepSeekConfig`, `MistralConfig`, `CohereConfig`, `ZaiConfig`, `XaiConfig`; the `clientId` in `OpenAIWorkloadIdentity`
  - openai: `AzureConfig`
  - anthropic: `AnthropicConfig` (the `AnthropicWorkloadIdentity` ids are identifiers, Task 4)
  - gemini: `GeminiConfig`
  - image: `ImageProcessingConfig`, plus the image-generation configs in `imagegeneration/ImageGeneration.scala` (find them with `grep -rn apiKey modules/image/src/main`)
  - speech: `SpeechConfig` (both `apiKey` fields)
  - providers: `watsonx/WatsonXConfig`, `jev/JevConfig`, `bedrock/BedrockConfig` (`BedrockCredentials.accessKeyId` / `secretAccessKey`, the static keys), `jina`, `cohere`, `voyage` (find them with `grep -rln apiKey modules/providers/*/src/main`)
  - observability: `LangfuseConfig` (`publicKey`, `secretKey`, both `Option`)
  - agent-tools: the search-tool configs (`*SearchToolConfig`) and any `apiKey` field there
  - rag: the reranker config's `apiKey`
- Modify each config's loader. Wherever a loader reads a `String` key, wrap it with `Secret(_)` at the point it is read: in the `ProviderDescriptor`'s config builder and in the `*ConfigLoader`.
- Modify each config's `toString`. Remove `Redaction.secret(...)` / `Redaction.secretOpt(...)`; interpolating the field now prints `***` or `Some(***)`. Remove `Redaction.secret` and `secretOpt` from `Redaction` once nothing uses them.
- Modify each client's use of a key. `config.apiKey` becomes `config.apiKey.reveal` exactly where it is placed in a header, a URL or an SDK builder.
- Modify the `java-api` builders. `ClientSettings.java` and the `*ClientBuilder.java` builders keep taking `String`; `ClientBuilds.scala`, which turns the settings into a config, wraps the key with `Secret(_)` (or `ApiKey(_)`).
- Modify the tests across all modules. Replace `apiKey = "…"` with `apiKey = Secret("…")`.
- Test: `modules/core/src/test/scala/org/llm4s/security/SecretConfigToStringSpec.scala`, plus one `javaApi` test.

**Interfaces:**
- Consumes: `Secret` from Task 1.
- Produces: `ApiKey` is `opaque type ApiKey = Secret`, with `ApiKey(value: String): ApiKey = Secret(value)` and `extension (k: ApiKey) def asKey: String = k.reveal`. Every config's credential field is `Secret` or `Option[Secret]`.

- [ ] **Step 1: Write the failing tests**

```scala
package org.llm4s.security

import org.llm4s.llmconnect.config.EmbeddingProviderConfig
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SecretConfigToStringSpec extends AnyFlatSpec with Matchers {
  "a config holding a Secret" should "print *** for the credential and register it" in KnownSecrets.isolated {
    val key = "emb-key-0123456789abcdef"
    val cfg = EmbeddingProviderConfig(baseUrl = "https://example.test", model = "m", apiKey = Secret(key))
    cfg.toString should not include key
    cfg.toString should include("***")
    org.llm4s.util.Redaction.safeBody(s"echo $key") should not include key
  }
}
```

Adjust the `EmbeddingProviderConfig` constructor arguments to its real `apply` signature.

Add the `javaApi` test. Build a client through the Java builder with key `"java-key-0123456789"`, then assert that `Redaction.safeBody("echo java-key-0123456789")` does not include the key. Place it in `ClientBuildersSpec` (`modules/java-api/src/test`).

- [ ] **Step 2: Run them and confirm they fail**

Run: `sbt "core/testOnly org.llm4s.security.SecretConfigToStringSpec"`
Expected: FAIL to compile, because `apiKey` expects a `String`.

- [ ] **Step 3: Change `ApiKey`**

```scala
  /** A secret key used to authenticate with a provider. Prints as `***`; registered with llm4s for redaction. */
  opaque type ApiKey = org.llm4s.security.Secret

  object ApiKey:
    /** Wraps a raw string as an [[ApiKey]], registering it for redaction. */
    def apply(value: String): ApiKey = org.llm4s.security.Secret(value)

  /** Returns the underlying key string. Use only where the key is sent: a header, a URL, an SDK builder. */
  extension (value: ApiKey) def asKey: String = value.reveal
```

- [ ] **Step 4: Convert the configs, loaders, `toString`s and clients, one module at a time**

For each module, in the order core, openai-compatible, openai, anthropic, gemini, providers/*, image, speech, observability, agent-tools, rag, java-api (sbt ids: `core`, `openaiCompatible`, `openai`, `anthropic`, `gemini`, `watsonx`, `jev`, `bedrock`, `jina`, `cohere`, `voyage`, `image`, `speech`, `observability`, `agentTools`, `rag`, `javaApi`):
- change the field type;
- wrap the value where the loader reads it;
- fix `toString`;
- use `.reveal` at the send site;
- update that module's tests.

After each module, run: `sbt <module>/test 2>&1 | grep -E "\[error\]|Failed tests|Tests: " | tail -5`
Expected: compiles, and the tests pass.

- [ ] **Step 5: Run the cross-module checks**

Run: `sbt compile Test/compile stabilityTierCheck "core/testOnly org.llm4s.security.*" "javaApi/test"`
Expected: PASS.

- [ ] **Step 6: Commit (one commit per module group is fine)**

```bash
git commit -s -am "feat!: ApiKey and every config credential are Secret, so each configured key is registered for redaction (#1932)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Runtime tokens and identifiers register themselves

**Files:**
- Modify: `modules/gemini/src/main/scala/org/llm4s/llmconnect/provider/VertexAIAuthProvider.scala`:
  - `cachedToken` (~:71–89): wrap each access token obtained with `Secret.token(...)`, and keep the previous generation;
  - JWT assertion and refresh token (~:129, ~:212): `Secret.token`.
- Modify: `modules/providers/watsonx/src/main/scala/org/llm4s/llmconnect/provider/WatsonXClient.scala`: `tokenCache` (~:162–231). Each IAM token becomes `Secret.token`. Remove the private `scrub(text, token)` (~:205), which blanks the API key and token by plain `replace`, and its four callers (~:218, ~:258, ~:288, ~:437).
- Modify: `modules/core/src/main/scala/org/llm4s/llmconnect/auth/TokenExchange.scala`:
  - the subject JWT (`form`, ~:205) and the exchanged access token (`parse`, ~:249): `Secret.token`;
  - `config.clientId`: `KnownSecrets.registerIdentifier`;
  - delete the secrets assembly, `sensitiveValues` (~:219), and the `secrets` parameter of its private `safeBody` (~:232) and `parse` (~:193).
- Modify: `modules/core/src/main/scala/org/llm4s/llmconnect/auth/AccessTokenProvider.scala`: `CachingAccessTokenProvider` holds a `TokenGenerations` and rotates each token it caches. This registers the exchanged workload-identity tokens and the Azure Entra ID tokens (`AzureEntraId`, `llm4s-azure-identity`) in one place.
- Modify: `OpenAIClient.credentialSecrets` (~:859), `AnthropicClient.credentialSecrets` (~:1052), `OpenAICompatibleClient.credentialSecrets` (~:361). Replace each with a `registerCredentials(config)` that calls `KnownSecrets.registerIdentifier` for the workload-identity ids (federation rule, organisation, service account, workspace, client id). The keys are already `Secret`s after Task 3. Delete the now-unused `credentialSecrets` vals and parameters.
- Modify: `LangfuseBatchSender.scala:77` and `LangfuseTracing.scala:110`. Build the header from `Secret.basic(publicKey.reveal, secretKey.reveal).reveal`.
- Create: `modules/core/src/main/scala/org/llm4s/security/TokenGenerations.scala`. A small helper: `final class TokenGenerations { def rotate(next: String): Secret }`. It registers `next` as a token, keeps the current token and the one before it, and forgets the generation before that.
- Test: `modules/core/src/test/scala/org/llm4s/security/TokenRotationSpec.scala`
- Test: extend each token source's existing spec with one case: an error body echoing the token is masked.

**Interfaces:**
- Consumes: `Secret.token`, `KnownSecrets.registerIdentifier` and `KnownSecrets.forget` from Task 1.
- Produces: `org.llm4s.security.TokenGenerations` (`private[llm4s]`), with `def rotate(next: String): Secret`.

- [ ] **Step 1: Write the failing test**

```scala
package org.llm4s.security

import org.llm4s.util.Redaction
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class TokenRotationSpec extends AnyFlatSpec with Matchers {
  "TokenGenerations" should "mask the current token and the one it just replaced" in KnownSecrets.isolated {
    val g = new TokenGenerations
    g.rotate("ya29.first-token-0001")
    g.rotate("ya29.second-token-0002")
    Redaction.safeBody("late body echoing ya29.first-token-0001") should not include "first-token-0001"
    Redaction.safeBody("body echoing ya29.second-token-0002") should not include "second-token-0002"
  }

  it should "forget the generation before the previous one" in KnownSecrets.isolated {
    val g = new TokenGenerations
    g.rotate("ya29.first-token-0001"); g.rotate("ya29.second-token-0002"); g.rotate("ya29.third-token-0003")
    KnownSecrets.scrub("ya29.first-token-0001", "[REDACTED]") shouldBe "ya29.first-token-0001"
  }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `sbt "core/testOnly org.llm4s.security.TokenRotationSpec"`
Expected: FAIL to compile, because `TokenGenerations` is not defined.

- [ ] **Step 3: Implement `TokenGenerations`**

```scala
package org.llm4s.security

import java.util.concurrent.atomic.AtomicReference

/**
 * Registers each token a source obtains, and keeps the token it replaced registered too: an error body
 * for a request made with the old token may still be on its way. The generation before that is forgotten.
 */
final private[llm4s] class TokenGenerations {
  private val held = new AtomicReference[Vector[String]](Vector.empty)

  def rotate(next: String): Secret = {
    val secret  = Secret.token(next)
    val dropped = held.getAndUpdate(gens => (gens :+ next).takeRight(2))
    (dropped :+ next).dropRight(2).foreach(KnownSecrets.forget)
    secret
  }
}
```

- [ ] **Step 4: Wire up each source**
  - **Vertex.** Hold a `TokenGenerations`. Where `cachedToken = Some(t)` is set, call `generations.rotate(t.value)`. Wrap the JWT assertion and the refresh token in `Secret.token` where they are built.
  - **watsonx.** Hold a `TokenGenerations`, and call `rotate(token)` where `tokenCache.set(...)` is called. Delete the private `scrub(...)` and pass each body to `Redaction.safeBody` (or `HttpErrorMapper`) only.
  - **`CachingAccessTokenProvider`.** Call `generations.rotate(token.value)` where a fetched token is cached.
  - **TokenExchange.** Call `Secret.token(jwt)` for the subject token and `Secret.token(accessToken)` for the exchanged token. Call `KnownSecrets.registerIdentifier` for `clientId`. Delete the `secrets` sequence.
  - **OpenAI, Anthropic and OpenAI-compatible.** Replace each `credentialSecrets(...)` with `registerCredentials(config)`, called once at client construction. It registers the workload-identity ids as identifiers. Delete the passing of `secrets` to `mapError` and its parameter.
  - **Langfuse.** Build the Basic header from `Secret.basic(...)`.

- [ ] **Step 5: Add an echo test to each source's spec**

In `VertexAIAuthProviderSpec` (gemini), `WatsonXClientSpec` (watsonx), `TokenExchangeSpec` and `CachingAccessTokenProviderSpec` (core, `llmconnect/auth`) and `OpenAIWorkloadIdentitySpec` (openai), add one case to each. The fake token endpoint (these specs already use a local server) issues token `T`. The next call's error body echoes `T`. The `LLMError.message` must not contain `T`.

Run: `sbt core/test gemini/test watsonx/test openai/test anthropic/test openaiCompatible/test azureIdentity/test observability/test`.
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git commit -s -am "feat: runtime tokens and workload-identity ids register themselves; drop per-client secret lists (#1932)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: End-to-end echo checks, one per client

**Files:**
- Create: `modules/provider-testkit/src/main/scala/org/llm4s/testkit/CredentialEchoChecks.scala`
- Test, one spec per client module, named `<Client>CredentialEchoSpec`:
  - openai (OpenAI, Azure);
  - openai-compatible (generic, DeepSeek, Mistral, Cohere chat, Z.ai, OpenRouter, xAI);
  - anthropic;
  - gemini;
  - ollama (embedding only, if it has a key);
  - providers (jina, cohere embeddings, voyage, watsonx, jev);
  - rag (Cohere reranker);
  - image (OpenAI image, HuggingFace, StableDiffusion, the vision clients);
  - speech (OpenAI TTS/STT, ElevenLabs).

**Interfaces:**
- Consumes: `LocalProviderTestServer` (provider-testkit) and the configs with `Secret` fields from Task 3.
- Produces: `CredentialEchoChecks.assertNotEchoed(key: String)(call: String => Either[org.llm4s.error.LLMError, ?]): Unit`. It starts a local server (`LocalProviderTestServer.withServer(path)(handler)(test)`, which passes `test` the base URL) that answers every request with HTTP 401 and a JSON body echoing `key` in four shapes (raw, inside a JSON string, JSON-escaped, URL-encoded). It runs `call(baseUrl)` and asserts that the `Left` message does not contain `key` in any form.

- [ ] **Step 1: Write the helper**

```scala
package org.llm4s.testkit

import org.llm4s.error.LLMError
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object CredentialEchoChecks {
  def echoBody(key: String): String =
    s"""{"error":{"message":"Incorrect API key provided: $key","raw":"$key","url":"?key=${URLEncoder
        .encode(key, StandardCharsets.UTF_8)}","nested":"{\\"k\\":\\"$key\\"}"}}"""

  /** Fails unless `call` returns a Left whose message holds none of `key`'s forms. */
  def assertNotEchoed(key: String)(call: String => Either[LLMError, ?]): Unit =
    LocalProviderTestServer.withServer("/")(LocalProviderTestServer.sendJsonResponse(_, 401, echoBody(key))) { baseUrl =>
      call(baseUrl) match {
        case Left(err) =>
          val forms = Seq(key, URLEncoder.encode(key, StandardCharsets.UTF_8))
          forms.foreach(f => assert(!err.message.contains(f), s"credential echoed in: ${err.message}"))
        case Right(r) => throw new AssertionError(s"expected an error, got $r")
      }
    }
}
```

`withServer` binds the handler to a path prefix, so `"/"` answers every request; `sendJsonResponse(exchange, status, body)` is the existing fixed-response helper in the same object.

- [ ] **Step 2: Write one spec per client and confirm it passes**

Each spec builds the client from its config with `apiKey = Secret("echo-key-0123456789abcdef")` and `baseUrl = <local>`, then makes one call through `assertNotEchoed`. The call is `complete`, `embed`, `rerank`, `generate` or `transcribe`, whichever the client offers.

Before Task 2's change, many of these leaked through `safeBody`, which was told no secrets. To prove the specs bite, temporarily revert Task 2's `scrubKnown` call in `safeBody`, confirm at least the embedding and image specs FAIL, then restore it.

Run: `sbt test 2>&1 | grep -E "CredentialEcho|\*\*\* FAILED|Failed tests" | tail -40`
Expected: every `CredentialEchoSpec` passes.

- [ ] **Step 3: Commit**

```bash
git commit -s -am "test: every client keeps a credential the provider echoes back out of its error (#1932)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Documentation and CHANGELOG

**Files:**
- Modify: `CHANGELOG.md`
- Modify: `docs/reference/migration.md`
- Modify: `docs/reference/security.md`

- [ ] **Step 1: Update the CHANGELOG**

Find the headings with `grep -n '^## \|^### ' CHANGELOG.md | head -20`.

Under `### Security` in `[Unreleased]`, add:

```markdown
- **Added** `Secret`: every credential llm4s holds (configured keys, runtime tokens) is registered and removed by exact match from all error messages, logs and exchange logs, whatever the syntax around it ([#1932](https://github.com/llm4s/llm4s/issues/1932), [design](docs/superpowers/specs/2026-10-15-layered-redaction-design.md)).
```

Under `### Breaking changes`, add:

```markdown
- `ApiKey` and every config credential field (`apiKey`, Langfuse keys, search-tool keys) are `org.llm4s.security.Secret`, not `String`; [migration](docs/reference/migration.md#credentials-are-secret-1932).
```

- [ ] **Step 2: Update the migration guide**

Add a section `## Credentials are Secret (#1932)` that covers four things:
- before and after of building a config: `OpenAIConfig(apiKey = "sk-…")` becomes `apiKey = Secret("sk-…")`;
- reading the value: `.reveal`;
- that `toString` prints `***`;
- Java: the builders still take `String`.

- [ ] **Step 3: Update `security.md`**

Add a "What llm4s redacts" section. It states guarantee A in one paragraph:
- every registered credential is removed from diagnostic text by exact match;
- the forms covered are raw, JSON-escaped, URL-encoded and Basic base64;
- values under 8 characters are not registered;
- runtime tokens are kept for one rotation.

It also says that the heuristics for unknown secrets are best effort, and that the layered design is completed by PRs 2–4.

- [ ] **Step 4: Check the links and commit**

Run: `scripts/check-docs-site-links.sh`
Expected: OK.

```bash
git commit -s -am "docs: Secret, the known-secret guarantee and its migration (#1932)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Final checks (before the PR)

- `sbt scalafmtAll`
- `sbt test`, run in module batches on a shared machine.
- `sbt stabilityTierCheck frozenDependencyCheck`
- `scripts/check-docs-site-links.sh`
- The PR body states the review rule from the spec: for this PR, any emitted registered credential is a defect.
