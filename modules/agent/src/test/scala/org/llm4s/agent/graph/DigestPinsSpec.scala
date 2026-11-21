package org.llm4s.agent.graph

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** The agent's digests and canonical JSON are unchanged by #2642's faster encoders: recorded keys still match. */
class DigestPinsSpec extends AnyFlatSpec with Matchers:

  "Sha256.hex" should "keep its digest" in {
    // SHA-256("abc"), computed outside the JVM
    Sha256.hex("abc") shouldBe "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
  }

  /** `canonical` before #2642, kept as the reference: sort a copy, then write it. */
  private def reference(json: ujson.Value): String =
    def sorted(value: ujson.Value): ujson.Value = value match
      case null               => ujson.Null
      case ujson.Obj(members) => ujson.Obj.from(members.toSeq.sortBy(_._1).map((k, v) => k -> sorted(v)))
      case ujson.Arr(items)   => ujson.Arr.from(items.map(sorted))
      case other              => other
    ujson.write(sorted(json), escapeUnicode = true)

  "KeyDigest.canonical" should "write the text the sorted copy it replaced did" in {
    val values = Seq[ujson.Value](
      ujson.Null,
      null: ujson.Value,
      ujson.Str("é ✓ 𝄞 \"quoted\" \\ \n\t\u0001  "),
      ujson.Num(1.5),
      ujson.Num(3),
      ujson.Num(-0.0),
      ujson.Num(1e21),
      ujson.Num(123456789012.0),
      ujson.True,
      ujson.False,
      ujson.Arr(),
      ujson.Obj(),
      ujson.Obj(
        "b" -> 1,
        "a" -> ujson.Arr(ujson.Obj("z" -> ujson.Null, "y" -> (null: ujson.Value)), "x"),
        "é" -> 2,
        "A" -> 3,
        ""  -> 4
      ),
      ujson.Arr(ujson.Arr(ujson.Arr()), ujson.Obj("k" -> ujson.Obj("j" -> ujson.Obj("i" -> "deep"))))
    )
    values.foreach(v => KeyDigest.canonical(v) shouldBe reference(v))
    KeyDigest.canonical(ujson.Obj("b" -> 1, "a" -> ujson.Arr(true, "x"))) shouldBe """{"a":[true,"x"],"b":1}"""
  }

  it should "leave the value it writes as it was" in {
    val value  = ujson.Obj("b" -> 1, "a" -> ujson.Arr(ujson.Obj("d" -> 1, "c" -> 2)))
    val before = ujson.write(value)
    KeyDigest.canonical(value): Unit
    ujson.write(value) shouldBe before
  }
