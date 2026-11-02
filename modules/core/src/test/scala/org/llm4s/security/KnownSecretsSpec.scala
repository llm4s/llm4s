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

  it should "keep a value another holder still holds" in KnownSecrets.isolated {
    KnownSecrets.register("shared-token-0001", KnownSecrets.Kind.Token)
    KnownSecrets.register("shared-token-0001", KnownSecrets.Kind.Token)
    KnownSecrets.forget("shared-token-0001")
    KnownSecrets.scrub("shared-token-0001", P) shouldBe P
    KnownSecrets.forget("shared-token-0001")
    KnownSecrets.scrub("shared-token-0001", P) shouldBe "shared-token-0001"
  }

  it should "never forget a configured key" in KnownSecrets.isolated {
    KnownSecrets.register("configured-key-0001", KnownSecrets.Kind.Configured)
    KnownSecrets.register("configured-key-0001", KnownSecrets.Kind.Token)
    KnownSecrets.forget("configured-key-0001")
    KnownSecrets.forget("configured-key-0001")
    KnownSecrets.scrub("configured-key-0001", P) shouldBe P
  }

  it should "evict tokens before configured keys at the cap" in KnownSecrets.isolated {
    KnownSecrets.register("configured-key-0001", KnownSecrets.Kind.Configured)
    (0 until KnownSecrets.Cap).foreach(i => KnownSecrets.register(f"token-$i%08d", KnownSecrets.Kind.Token))
    KnownSecrets.scrub("configured-key-0001", P) shouldBe P
    KnownSecrets.size should be <= KnownSecrets.Cap
  }

  it should "keep every configured key past the cap" in KnownSecrets.isolated {
    val keys = (0 until KnownSecrets.Cap + 10).map(i => f"configured-$i%08d")
    keys.foreach(KnownSecrets.register(_, KnownSecrets.Kind.Configured))
    KnownSecrets.register("token-after-0001", KnownSecrets.Kind.Token)
    keys.foreach(k => KnownSecrets.scrub(k, P) shouldBe P)
  }

  it should "leave text alone when nothing is registered" in KnownSecrets.isolated {
    KnownSecrets.scrub("nothing secret here", P) shouldBe "nothing secret here"
  }

  it should "keep an isolated registry apart from other threads" in KnownSecrets.isolated {
    KnownSecrets.register("isolated-only-value", KnownSecrets.Kind.Token)
    val seen = new java.util.concurrent.atomic.AtomicReference[String]()
    val pool = Executors.newSingleThreadExecutor() // started here, so it inherits this registry
    pool.submit(new Runnable { def run(): Unit = seen.set(KnownSecrets.scrub("isolated-only-value", P)) }).get()
    pool.shutdown()
    seen.get() shouldBe P
    val outside = new java.util.concurrent.atomic.AtomicReference[String]()
    val other = Thread
      .ofPlatform()
      .inheritInheritableThreadLocals(false)
      .start(() => outside.set(KnownSecrets.scrub("isolated-only-value", P)))
    other.join()
    outside.get() shouldBe "isolated-only-value" // the process-wide registry never saw it
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

  it should "evict the oldest token at the cap and keep the newest" in KnownSecrets.isolated {
    val tokens = (0 to KnownSecrets.Cap).map(i => f"token-$i%08d")
    tokens.foreach(KnownSecrets.register(_, KnownSecrets.Kind.Token))
    KnownSecrets.size shouldBe KnownSecrets.Cap
    KnownSecrets.scrub(tokens.head, P) shouldBe tokens.head
    KnownSecrets.scrub(tokens.last, P) shouldBe P
    KnownSecrets.overCapWarned shouldBe false
  }

  it should "keep a new token when configured keys alone fill the cap, and warn once" in KnownSecrets.isolated {
    (0 until KnownSecrets.Cap).foreach(i => KnownSecrets.register(f"configured-$i%08d", KnownSecrets.Kind.Configured))
    KnownSecrets.overCapWarned shouldBe false
    KnownSecrets.register("token-new-0001", KnownSecrets.Kind.Token)
    KnownSecrets.scrub("token-new-0001", P) shouldBe P
    KnownSecrets.overCapWarned shouldBe true
    // the next token replaces the previous one, which is now the oldest evictable entry
    KnownSecrets.register("token-new-0002", KnownSecrets.Kind.Token)
    KnownSecrets.scrub("token-new-0002", P) shouldBe P
    KnownSecrets.scrub("token-new-0001", P) shouldBe "token-new-0001"
  }

  it should "move a re-registered token to the most recent end" in KnownSecrets.isolated {
    val tokens = (0 until KnownSecrets.Cap).map(i => f"token-$i%08d")
    tokens.foreach(KnownSecrets.register(_, KnownSecrets.Kind.Token))
    KnownSecrets.register(tokens.head, KnownSecrets.Kind.Token) // still in use: no longer the oldest
    KnownSecrets.register("token-overflow-0001", KnownSecrets.Kind.Token)
    KnownSecrets.scrub(tokens.head, P) shouldBe P
    KnownSecrets.scrub(tokens(1), P) shouldBe tokens(1)
    KnownSecrets.scrub("token-overflow-0001", P) shouldBe P
  }

  it should "register an identifier, and never forget it" in KnownSecrets.isolated {
    KnownSecrets.registerIdentifier("client-id-0123456789")
    KnownSecrets.scrub("id=client-id-0123456789", P) shouldBe s"id=$P"
    KnownSecrets.forget("client-id-0123456789")
    KnownSecrets.scrub("id=client-id-0123456789", P) shouldBe s"id=$P"
  }

  it should "never evict an identifier at the cap" in KnownSecrets.isolated {
    KnownSecrets.registerIdentifier("client-id-0123456789")
    (0 to KnownSecrets.Cap).foreach(i => KnownSecrets.register(f"token-$i%08d", KnownSecrets.Kind.Token))
    KnownSecrets.scrub("client-id-0123456789", P) shouldBe P
  }
}
