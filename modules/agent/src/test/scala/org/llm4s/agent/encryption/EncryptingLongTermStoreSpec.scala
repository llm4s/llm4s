package org.llm4s.agent.encryption

import org.llm4s.agent.graph.*
import org.llm4s.agent.harness.*
import org.llm4s.error.ProcessingError
import org.llm4s.types.Result
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * [[EncryptingLongTermStore]] stores no item value and no recorded outcome in plain, binds each to its tenant,
 * namespace and key, and answers every call as the store it wraps would answer it unencrypted (#2449, #2453).
 */
class EncryptingLongTermStoreSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues {

  private val Secret = "the-patient-has-condition-x"
  private val ns     = Namespace(None, Vector("notes"))

  private def key(id: String): EncryptionKey = EncryptionKey.generate(KeyId(id))
  private def tenant(id: String): Namespace  = Namespace(Some(TenantId(id)), Vector("notes"))

  private def effectKey(): EffectKey =
    RunContext.outsideRuntime(ThreadId(s"encs-${UUID.randomUUID()}")).effectKey("spec", UUID.randomUUID().toString)

  private def call(): FileContext =
    FileContext.withoutState(RunContext.outsideRuntime(ThreadId(s"encs-${UUID.randomUUID()}")))

  /** `value` written at `at` as one unguarded effect: through a wrapper, or straight into the store under it. */
  private def put(store: LongTermStore, at: String, value: ujson.Value, in: Namespace = ns): Result[Unit] =
    store.modify[Unit](in, at, effectKey(), FencingToken(0))(_ => Right(StoreChange.Put(value, ())))

  private def stored(raw: LongTermStore, at: String, in: Namespace = ns): ujson.Value =
    raw.get(in, at).value.value.value

  private def secret: ujson.Value = ujson.Obj("note" -> Secret, "n" -> 1)

  "EncryptingLongTermStore" should "store no value in plain, and hand back the value that was written" in {
    val raw   = InMemoryLongTermStore()
    val store = EncryptingLongTermStore(raw, StaticKeySource(key("k1")))

    store.put(ns, "a/1", secret, call()).value shouldBe true
    store.put(ns, "a/1", secret, call()).value shouldBe false

    val kept = ujson.write(stored(raw, "a/1"))
    kept should (not.include(Secret).and(include("1.k1.")))
    PayloadCipher.envelope(stored(raw, "a/1")) should not be empty

    store.get(ns, "a/1").value.value.value shouldBe secret
    store.get(ns, "a/1").value.value.updated shouldBe raw.get(ns, "a/1").value.value.updated
    store.list(ns, "a/", 10).value.items.map(e => e.key -> e.item.value) shouldBe Vector("a/1" -> secret)
    store.search(ns, "CONDITION", 10).value.items.map(_.key) shouldBe Vector("a/1")
    store.get(ns, "absent").value shouldBe None
    store.inner shouldBe raw
    store.delete(ns, "a/1", call()).value shouldBe true
    raw.list(ns, "", 10).value.items shouldBe empty
    raw.appliedKeys shouldBe 0
  }

  it should "encrypt the same value differently each time, and say nothing of its keys" in {
    val raw   = InMemoryLongTermStore()
    val k1    = key("k1")
    val store = EncryptingLongTermStore(raw, StaticKeySource(k1))
    put(store, "a", secret).value
    val first = stored(raw, "a")
    put(store, "a", secret).value
    stored(raw, "a") should not be first
    (store.toString should not).include("k1")
  }

  it should "return a value exactly as written, an unpaired surrogate included" in {
    val store = EncryptingLongTermStore(InMemoryLongTermStore(), StaticKeySource(key("k1")))
    val lone  = "half " + 0xd800.toChar + " é 😀 " + 0xdc00.toChar
    val value = ujson.Obj("text" -> lone, "nul" -> "a\u0000b", "n" -> ujson.Arr(1, 1.5, 1e300, ujson.Null))
    put(store, "odd", value).value
    val read = store.get(ns, "odd").value.value.value
    read shouldBe value
    read("text").str.toVector shouldBe lone.toVector
  }

  it should "not open a value moved to another key, namespace or tenant" in {
    val raw   = InMemoryLongTermStore()
    val store = EncryptingLongTermStore(raw, StaticKeySource(key("k1")))
    put(store, "a", secret).value
    val ciphertext = stored(raw, "a")

    put(raw, "b", ciphertext).value
    val other = Namespace(None, Vector("other"))
    put(raw, "a", ciphertext, other).value
    put(raw, "a", ciphertext, tenant("acme")).value
    // a namespace is its whole path: a ciphertext does not open one level up or down either
    put(raw, "a", ciphertext, Namespace(None, Vector("notes", "deeper"))).value

    store.get(ns, "b").left.value shouldBe EncryptionError.DecryptionFailed(
      EncryptionError.Owner.Item(ns, "b"),
      "value",
      KeyId("k1")
    )
    store.get(other, "a").left.value shouldBe a[EncryptionError.DecryptionFailed]
    store.get(tenant("acme"), "a").left.value shouldBe a[EncryptionError.DecryptionFailed]
    store.get(Namespace(None, Vector("notes", "deeper")), "a").left.value shouldBe a[EncryptionError.DecryptionFailed]
    store.list(ns, "", 10).left.value shouldBe a[EncryptionError.DecryptionFailed]
    store.get(ns, "a").value.value.value shouldBe secret

    // two namespaces that differ only in an unpaired surrogate, which UTF-8 cannot tell apart, are two namespaces
    val high = Namespace(None, Vector("odd" + 0xd800.toChar))
    val low  = Namespace(None, Vector("odd" + 0xdc00.toChar))
    put(store, "a", secret, high).value
    put(raw, "a", stored(raw, "a", high), low).value
    store.get(high, "a").value.value.value shouldBe secret
    store.get(low, "a").left.value shouldBe a[EncryptionError.DecryptionFailed]
  }

  it should "fail a tampered or damaged value typed, never returning what is stored" in {
    val raw   = InMemoryLongTermStore()
    val store = EncryptingLongTermStore(raw, StaticKeySource(key("k1")))
    put(store, "a", secret).value
    val text = PayloadCipher.envelope(stored(raw, "a")).value
    // one character of the ciphertext changed, well inside it: every bit of that character is data
    val at      = text.length - 10
    val flipped = text.updated(at, if (text(at) == 'A') 'B' else 'A')
    def planted(envelope: String): Result[Option[StoreItem]] = {
      put(raw, "a", ujson.Obj(PayloadCipher.Marker -> envelope)).value
      store.get(ns, "a")
    }

    planted(flipped).left.value shouldBe a[EncryptionError.DecryptionFailed]
    planted("1.k1.AAAA").left.value shouldBe a[EncryptionError.MalformedCiphertext]
    planted("2.k1." + text.split('.').last).left.value shouldBe a[EncryptionError.MalformedCiphertext]
    val failure = planted(flipped).left.value
    failure.message should (include("item 'a'").and(include("notes")).and(not.include(text.split('.').last)))
  }

  it should "read old-key items and write with the new key after a rotation" in {
    val raw = InMemoryLongTermStore()
    val k1  = key("k1")
    val k2  = key("k2")
    put(EncryptingLongTermStore(raw, StaticKeySource(k1)), "old", secret).value

    val rotated = EncryptingLongTermStore(raw, StaticKeySource(k2).withRetiredKey(k1))
    rotated.get(ns, "old").value.value.value shouldBe secret
    put(rotated, "new", secret).value
    ujson.write(stored(raw, "old")) should include("1.k1.")
    ujson.write(stored(raw, "new")) should include("1.k2.")
    // a rewrite moves an item to the current key
    rotated
      .modify[Unit](ns, "old", effectKey(), FencingToken(0))(view =>
        Right(StoreChange.Put(view.current.value.value, ()))
      )
      .value
    ujson.write(stored(raw, "old")) should include("1.k2.")
    rotated.search(ns, "condition", 10).value.items.map(_.key) shouldBe Vector("new", "old")
  }

  it should "fail typed for a key it does not hold, and write nothing with no key to write under" in {
    val raw = InMemoryLongTermStore()
    put(EncryptingLongTermStore(raw, StaticKeySource(key("k1"))), "a", secret).value

    val without = EncryptingLongTermStore(raw, StaticKeySource(key("k2")))
    without.get(ns, "a").left.value shouldBe EncryptionError.UnknownKey(KeyId("k1"))
    without.list(ns, "", 10).left.value shouldBe EncryptionError.UnknownKey(KeyId("k1"))
    without.search(ns, "condition", 10).left.value shouldBe EncryptionError.UnknownKey(KeyId("k1"))
    // the same id over other material is a wrong key, not garbage
    val impostor = EncryptingLongTermStore(raw, StaticKeySource(key("k1")))
    impostor.get(ns, "a").left.value shouldBe a[EncryptionError.DecryptionFailed]

    val keyless = EncryptingLongTermStore(raw, StaticKeySource.tenantsOnly)
    val before  = raw.appliedKeys
    put(keyless, "b", secret).left.value shouldBe EncryptionError.NoCurrentKey(None)
    keyless.put(ns, "b", secret, call()).left.value shouldBe EncryptionError.NoCurrentKey(None)
    raw.get(ns, "b").value shouldBe None
    raw.appliedKeys shouldBe before
  }

  it should "seal each tenant's items under that tenant's key" in {
    val raw     = InMemoryLongTermStore()
    val acme    = key("acme-1")
    val globex  = key("globex-1")
    val default = key("default-1")
    val keys = StaticKeySource(default)
      .withTenantKey(TenantId("acme"), acme)
      .withTenantKey(TenantId("globex"), globex)
    val store = EncryptingLongTermStore(raw, keys)
    put(store, "a", secret, tenant("acme")).value
    put(store, "a", secret, tenant("globex")).value
    put(store, "a", secret).value

    ujson.write(stored(raw, "a", tenant("acme"))) should include("1.acme-1.")
    ujson.write(stored(raw, "a", tenant("globex"))) should include("1.globex-1.")
    ujson.write(stored(raw, "a")) should include("1.default-1.")

    // without acme's key, acme's items are unreadable and globex's are not
    val globexOnly = EncryptingLongTermStore(raw, StaticKeySource.tenantsOnly.withTenantKey(TenantId("globex"), globex))
    globexOnly.get(tenant("acme"), "a").left.value shouldBe EncryptionError.UnknownKey(KeyId("acme-1"))
    globexOnly.get(tenant("globex"), "a").value.value.value shouldBe secret
    put(globexOnly, "b", secret, tenant("acme")).left.value shouldBe
      EncryptionError.NoCurrentKey(Some(TenantId("acme")))
  }

  it should "search as an unencrypted store does over the same data" in {
    val plain = InMemoryLongTermStore()
    val raw   = InMemoryLongTermStore()
    val store = EncryptingLongTermStore(raw, StaticKeySource(key("k1")))
    val data = Vector(
      "a/deep"   -> ujson.Obj("list" -> ujson.Arr(ujson.Obj("s" -> "a GREAT find")), "n" -> 42),
      "b/Title"  -> ujson.Str("nothing here"),
      "c/none"   -> ujson.Obj("great" -> "the name of a field is not searched", "n" -> 42),
      "d/İ"      -> ujson.Str("İstanbul and ISTANBUL"),
      "e/great"  -> ujson.Num(7),
      "f/secret" -> ujson.Str(Secret)
    )
    data.foreach { (k, v) =>
      put(plain, k, v).value
      put(store, k, v).value
    }
    def same(query: String, limit: Int) = {
      val expected = plain.search(ns, query, limit).value
      val found    = store.search(ns, query, limit).value
      found.items.map(e => e.key -> e.item.value) shouldBe expected.items.map(e => e.key -> e.item.value)
      found.truncated shouldBe expected.truncated
    }
    for
      query <- Vector("great", "TITLE", "42", "", "istanbul", "%", "condition", "1.k1", "llm4s:enc", "none")
      limit <- Vector(1, 2, 10)
    do same(query, limit)

    // the ciphertext is never what is matched
    store.search(ns, "1.k1.", 10).value.items shouldBe empty
    store.search(ns, "a", 0).left.value shouldBe a[BackendRefused]
    store.search(ns, "x" * 2000, 10).left.value.message should include("longer than 1024 bytes")
    store.search(Namespace(None, Vector("empty")), "great", 10).value shouldBe Listing(Vector.empty, truncated = false)
  }

  it should "fail a search over an item it cannot open, rather than leave the item out" in {
    val raw   = InMemoryLongTermStore()
    val store = EncryptingLongTermStore(raw, StaticKeySource(key("k1")))
    put(store, "a", ujson.Str("great")).value
    put(raw, "b", ujson.Str("great, and in plain")).value
    store.search(ns, "great", 10).left.value shouldBe EncryptionError.NotEncrypted(
      EncryptionError.Owner.Item(ns, "b"),
      "value"
    )
  }

  it should "offer limits that mean what the store's own do, counted over what was written" in {
    val wanted = StoreLimits(maxItemBytes = 20, maxItems = 3, maxBytes = 40)
    val raw    = InMemoryLongTermStore(EncryptingLongTermStore.storageLimits(wanted))
    // the longest key id there is: the room an item needs under it is the room that was reserved
    val store = EncryptingLongTermStore(raw, StaticKeySource(key("k" * 64)))
    store.limits shouldBe wanted

    // 1 + 19 is 20: exactly at the item limit, in bytes of what the caller wrote
    put(store, "a", ujson.Str("0123456789abcdefg")).value
    put(store, "b", ujson.Str("0123456789abcdefgh")).left.value.message should include("over the limit of 20")
    // 20 + (1 + 19) is 40: exactly at the namespace's limit, and one byte over
    put(store, "b", ujson.Str("0123456789abcdefg")).value
    put(store, "c", ujson.Str("")).left.value.message should include("43 bytes, over its limit of 40")
    put(store, "b", ujson.Str("0123456789")).value
    put(store, "c", ujson.Str("")).value
    val fourth = put(store, "d", ujson.Str("")).left.value
    fourth shouldBe a[BackendRefused]
    fourth.message should include("4 items, over its limit of 3")
    raw.get(ns, "d").value shouldBe None
  }

  it should "derive its limits from the store under it, and refuse a store too small to hold an item" in {
    val defaults = EncryptingLongTermStore(InMemoryLongTermStore(), StaticKeySource(key("k1"))).limits
    defaults.maxItems shouldBe StoreLimits.default.maxItems
    defaults.maxItemBytes should (be < StoreLimits.default.maxItemBytes).and(be > StoreLimits.default.maxItemBytes / 2)
    defaults.maxBytes should (be < StoreLimits.default.maxBytes).and(be > StoreLimits.default.maxBytes / 2)
    // what the derived limits need is no more than the store has
    val needed = EncryptingLongTermStore.storageLimits(defaults)
    needed.maxItemBytes should be <= StoreLimits.default.maxItemBytes
    needed.maxBytes should be <= StoreLimits.default.maxBytes

    an[IllegalArgumentException] should be thrownBy
      EncryptingLongTermStore(InMemoryLongTermStore(StoreLimits(maxItemBytes = 100)), StaticKeySource(key("k1")))
    an[IllegalArgumentException] should be thrownBy
      EncryptingLongTermStore(InMemoryLongTermStore(StoreLimits(maxBytes = 1000)), StaticKeySource(key("k1")))
    // an item limit whose sealed form is over the replay cap cannot be offered by any store
    an[IllegalArgumentException] should be thrownBy
      EncryptingLongTermStore.storageLimits(StoreLimits(maxItemBytes = ReplayGuard.DefaultMaxOutcomeBytes))
  }

  it should "never be refused by the store under it for an item within its own limits" in {
    val wanted = StoreLimits(maxItemBytes = 300, maxItems = 7, maxBytes = 1500)
    val raw    = InMemoryLongTermStore(EncryptingLongTermStore.storageLimits(wanted))
    val store  = EncryptingLongTermStore(raw, StaticKeySource(key("K" * 64)))
    // every size up to the limit, filling the namespace to its last byte again and again
    (0 to 297).foreach { n =>
      (1 to 5).foreach(i => put(store, i.toString, ujson.Str("x" * n)).value)
      (store.get(ns, "5").value.value.value.str should have).length(n)
    }
    put(store, "1", ujson.Str("x" * 298)).left.value.message should include("over the limit of 300")
  }

  it should "refuse an item stored in plain by default, and read it when plaintext reads are on" in {
    val raw  = InMemoryLongTermStore()
    val keys = StaticKeySource(key("k1"))
    put(raw, "old", secret).value
    val lookalike = ujson.Obj(PayloadCipher.Marker -> "1.k1.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")

    val strict  = EncryptingLongTermStore(raw, keys)
    val refused = EncryptionError.NotEncrypted(EncryptionError.Owner.Item(ns, "old"), "value")
    strict.get(ns, "old").left.value shouldBe refused
    strict.list(ns, "", 10).left.value shouldBe refused
    strict.search(ns, "", 10).left.value shouldBe refused
    // a change is decided against the item as it is, so one that cannot be read cannot be changed either
    put(strict, "old", ujson.Str("new")).left.value shouldBe refused
    strict.delete(ns, "old", call()).left.value shouldBe refused
    stored(raw, "old") shouldBe secret

    val migrating = EncryptingLongTermStore(raw, keys, EncryptionSettings(plaintextReads = true))
    migrating.get(ns, "old").value.value.value shouldBe secret
    migrating.search(ns, "condition", 10).value.items.map(_.key) shouldBe Vector("old")
    // writes are encrypted whatever the setting, and a plaintext that looks like an envelope is sealed like any other
    put(migrating, "old", secret).value
    put(migrating, "lookalike", lookalike).value
    ujson.write(stored(raw, "old")) should not.include(Secret)
    strict.get(ns, "old").value.value.value shouldBe secret
    strict.get(ns, "lookalike").value.value.value shouldBe lookalike
  }

  "EncryptingLongTermStore.modify" should "show a change the plaintext of its namespace, and store ciphertext" in {
    val raw   = InMemoryLongTermStore()
    val store = EncryptingLongTermStore(raw, StaticKeySource(key("k1")))
    put(store, "a/1", ujson.Str("one")).value
    put(store, "a/2", ujson.Str("two")).value
    put(store, "b/1", ujson.Num(1)).value

    val outcome = store
      .modify[Vector[String]](ns, "b/1", effectKey(), FencingToken(0)) { view =>
        for
          other    <- view.get("a/1")
          absent   <- view.get("nope")
          siblings <- view.withPrefix("a/", 10)
          cut      <- view.withPrefix("", 1)
        yield
          absent shouldBe None
          cut.map(_.key) shouldBe Vector("a/1")
          val seen = Vector(other.value.value.str) ++ siblings.map(_.item.value.str)
          StoreChange.Put(ujson.Num(view.current.value.value.num + 1), seen)
      }
      .value
    outcome shouldBe Vector("one", "one", "two")
    store.get(ns, "b/1").value.value.value shouldBe ujson.Num(2)
    PayloadCipher.envelope(stored(raw, "b/1")) should not be empty
  }

  it should "answer a replay with the first outcome, its change not run, whatever was encrypted since" in {
    val raw    = InMemoryLongTermStore()
    val store  = EncryptingLongTermStore(raw, StaticKeySource(key("k1")))
    val effect = effectKey()
    val runs   = new AtomicInteger()
    def once() = store.modify[String](ns, "a", effect, FencingToken(1)) { view =>
      runs.incrementAndGet()
      Right(StoreChange.Put(secret, s"$Secret was ${view.current.fold("new")(_ => "there")}"))
    }

    once().value shouldBe s"$Secret was new"
    val first = stored(raw, "a")
    put(store, "a", ujson.Str("another writer's")).value
    once().value shouldBe s"$Secret was new"
    runs.get shouldBe 1
    store.get(ns, "a").value.value.value shouldBe ujson.Str("another writer's")
    first should not be stored(raw, "a")

    // the outcome is recorded sealed: the store's own answer to the applied key is an envelope
    val recorded = raw.modify[ujson.Value](ns, "a", effect, FencingToken(1))(_ => fail("an applied key ran")).value
    PayloadCipher.envelope(recorded) should not be empty
    ujson.write(recorded) should not.include(Secret)
    // and is bound to its effect: planted under another applied key, it does not open
    val other = effectKey()
    raw.modify[ujson.Value](ns, "a", other, FencingToken(0))(_ => Right(StoreChange.Keep(recorded))).value
    store.modify[String](ns, "a", other, FencingToken(0))(_ => fail("an applied key ran")).left.value shouldBe
      a[EncryptionError.DecryptionFailed]

    // a token older than the newest the scope has seen is refused by the store, as without encryption
    store.modify[String](ns, "a", effect, FencingToken(0))(_ => fail("a stale claim ran")).left.value shouldBe
      a[ProcessingError]
    store.forget(effect, FencingToken(1)).value
    once().value shouldBe s"$Secret was there"
    runs.get shouldBe 2
  }

  it should "write and record nothing when the change, a bound or the encryption refuses" in {
    val raw   = InMemoryLongTermStore()
    val store = EncryptingLongTermStore(raw, StaticKeySource(key("k1")))
    put(store, "held", ujson.Str("kept")).value
    val kept   = stored(raw, "held")
    val before = raw.appliedKeys
    store
      .modify[Unit](ns, "held", effectKey(), FencingToken(0))(_ => Left(BackendRefused("put", "held", "no")))
      .left
      .value shouldBe a[BackendRefused]
    val deep = (1 to LongTermStore.MaxDepth + 1).foldLeft[ujson.Value](ujson.Num(1))((v, _) => ujson.Arr(v))
    put(store, "held", deep).left.value.message should include("nested deeper")
    put(store, "held", ujson.Str("x" * store.limits.maxItemBytes)).left.value.message should include("over the limit")
    stored(raw, "held") shouldBe kept
    raw.appliedKeys shouldBe before
  }

  "A StoreFileBackend over an EncryptingLongTermStore" should "keep its files encrypted with no more wiring" in {
    val raw     = InMemoryLongTermStore()
    val store   = EncryptingLongTermStore(raw, StaticKeySource(key("k1")))
    val backend = StoreFileBackend(store, Vector("memories")).value
    val path    = VPath("/notes/patient.md").value
    val ctx     = call()

    backend.write(path, s"line one\n$Secret\n", ctx).value
    backend.edit(path, Secret, s"$Secret, confirmed", replaceAll = false, call()).value

    val held = raw.list(Namespace(None, Vector("memories")), "", 10).value.items
    held.map(_.key) shouldBe Vector(path.value)
    held.foreach(entry => PayloadCipher.envelope(entry.item.value) should not be empty)
    ujson.write(held.map(_.item.value)) should not.include(Secret)

    backend.read(path, 0, 10, call()).value.lines shouldBe Vector("line one", s"$Secret, confirmed")
    // the same files, read without the keys, are refused rather than shown as ciphertext
    StoreFileBackend(raw, Vector("memories")).value.read(path, 0, 10, call()).isLeft shouldBe true
  }
}
