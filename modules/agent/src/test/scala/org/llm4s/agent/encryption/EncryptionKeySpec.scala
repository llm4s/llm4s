package org.llm4s.agent.encryption

import org.llm4s.agent.graph.TenantId
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.{ Base64, HexFormat }

/** Keys keep their material to themselves, and derive subkeys as RFC 5869 says (#2449). */
class EncryptionKeySpec extends AnyFlatSpec with Matchers with EitherValues {

  private val hex = HexFormat.of()

  "EncryptionKey" should "derive a subkey as HKDF-Expand does (RFC 5869, test case 1)" in {
    val prk  = hex.parseHex("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5")
    val info = hex.parseHex("f0f1f2f3f4f5f6f7f8f9")
    val okm  = "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf"
    val key  = EncryptionKey.of(KeyId("rfc"), prk).value
    hex.formatHex(key.subkey(info).getEncoded) shouldBe okm
    key.subkey(info).getAlgorithm shouldBe "AES"
  }

  it should "never print its material" in {
    val material = Array.tabulate[Byte](32)(i => (i * 7 + 3).toByte)
    val encoded  = Base64.getEncoder.encodeToString(material)
    val key      = EncryptionKey.fromBase64(KeyId("k1"), encoded).value
    val source   = StaticKeySource(key).withTenantKey(TenantId("acme"), key).withRetiredKey(key)
    val printed  = s"$key ${key.hashCode} $source ${source.current(None)} ${source.key(KeyId("k1"))}"
    printed should include("k1")
    (printed should not).include(encoded)
    (printed should not).include(encoded.take(12))
    (printed should not).include(hex.formatHex(material).take(12))
    key.toString shouldBe "EncryptionKey(k1, ***)"
  }

  it should "be read from standard and URL-safe Base64, padded or not" in {
    val material = Array.fill[Byte](32)(0xfb.toByte)
    val standard = Base64.getEncoder.encodeToString(material)
    val urlSafe  = Base64.getUrlEncoder.withoutPadding().encodeToString(material)
    standard should include("+")
    val reference = EncryptionKey.of(KeyId("k"), material).value
    Seq(standard, urlSafe, s"  $standard\n").foreach { text =>
      EncryptionKey.fromBase64(KeyId("k"), text).value.sameMaterial(reference) shouldBe true
    }
  }

  it should "refuse material that is not 32 bytes or not Base64, without quoting it" in {
    val short = Base64.getEncoder.encodeToString(Array.fill[Byte](16)(7))
    val wrong = EncryptionKey.fromBase64(KeyId("k1"), short).left.value
    wrong shouldBe a[EncryptionError.InvalidKey]
    wrong.message should (include("32 bytes").and(include("16")).and(not).include(short))

    val garbage = "not base64 at all !!"
    val invalid = EncryptionKey.fromBase64(KeyId("k1"), garbage).left.value
    invalid.message should (include("not Base64").and(not).include(garbage))
  }

  it should "copy the material it is given" in {
    val material = Array.fill[Byte](32)(1)
    val key      = EncryptionKey.of(KeyId("k"), material).value
    val before   = key.subkey(Array[Byte](1)).getEncoded.toVector
    java.util.Arrays.fill(material, 0.toByte)
    key.subkey(Array[Byte](1)).getEncoded.toVector shouldBe before
  }

  "KeyId" should "accept only ids an envelope can carry" in {
    KeyId.of("2026-11_a").value.value shouldBe "2026-11_a"
    Seq("", "a.b", "a b", "k/1", "x" * 65).foreach(bad =>
      KeyId.of(bad).left.value shouldBe a[EncryptionError.InvalidKey]
    )
    an[IllegalArgumentException] should be thrownBy KeyId("a.b")
  }

  "StaticKeySource" should "answer a tenant's key, else the default, and every key by id" in {
    val default = EncryptionKey.generate(KeyId("default"))
    val acme    = EncryptionKey.generate(KeyId("acme"))
    val old     = EncryptionKey.generate(KeyId("old"))
    val source  = StaticKeySource(default).withTenantKey(TenantId("acme"), acme).withRetiredKey(old)
    source.current(None).value shouldBe default
    source.current(Some(TenantId("globex"))).value shouldBe default
    source.current(Some(TenantId("acme"))).value shouldBe acme
    Seq(default, acme, old).foreach(k => source.key(k.id).value shouldBe k)
    source.key(KeyId("never")).left.value shouldBe EncryptionError.UnknownKey(KeyId("never"))
  }

  it should "have no key for a tenant given none when it has no default" in {
    val source = StaticKeySource.tenantsOnly.withTenantKey(TenantId("acme"), EncryptionKey.generate(KeyId("acme")))
    source.current(Some(TenantId("globex"))).left.value shouldBe EncryptionError.NoCurrentKey(Some(TenantId("globex")))
    source.current(None).left.value shouldBe EncryptionError.NoCurrentKey(None)
  }

  it should "refuse two different keys under one id" in {
    val source = StaticKeySource(EncryptionKey.generate(KeyId("k1")))
    an[IllegalArgumentException] should be thrownBy source.withRetiredKey(EncryptionKey.generate(KeyId("k1")))
  }
}
