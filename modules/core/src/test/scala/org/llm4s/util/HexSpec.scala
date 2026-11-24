package org.llm4s.util

import org.llm4s.llmconnect.caching.CacheKeyGenerator
import org.llm4s.types.ArtifactKey
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** [[Hex]] gives what the per-byte `String.format` encoders it replaced gave, so no digest moves (#2642). */
class HexSpec extends AnyFlatSpec with Matchers:

  private val everyByte: Array[Byte] = (0 until 256).map(_.toByte).toArray

  "Hex.of" should "write every byte as the three per-byte encoders it replaced did" in {
    Hex.of(everyByte) shouldBe everyByte.map(b => f"${b & 0xff}%02x").mkString
    Hex.of(everyByte) shouldBe everyByte.map(b => f"$b%02x").mkString
    Hex.of(everyByte) shouldBe everyByte.map("%02x".format(_)).mkString
    Hex.of(Array.emptyByteArray) shouldBe ""
    Hex.of(Array[Byte](0, 15, 16, 127, -128, -1)) shouldBe "000f107f80ff"
  }

  "The digests it now writes" should "be the ones they always were" in {
    // pinned from SHA-256 computed outside the JVM
    ArtifactKey.fromContent("hello").asString shouldBe
      "content_2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
    CacheKeyGenerator.sha256("a", "é𝄞") shouldBe "58eb6fd911e009c412af9498af753b329c8a9e43a4dafc0fd298043e36153c15"
  }
