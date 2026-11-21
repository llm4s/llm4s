package org.llm4s.schema

import org.scalacheck.Gen
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

/** `PartialJson` on every prefix of golden documents and of random ones: always a prefix of the final value. */
class PartialJsonSpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks {

  /** Whether `partial` is a prefix of `full`: what it has, `full` has, equal or (a string, the last element) longer. */
  private def isPrefix(partial: ujson.Value, full: ujson.Value): Boolean = (partial, full) match {
    case (ujson.Obj(p), ujson.Obj(f)) => p.forall { case (key, value) => f.get(key).exists(isPrefix(value, _)) }
    case (ujson.Arr(p), ujson.Arr(f)) =>
      p.size <= f.size && p.indices.forall(i => if (i == p.size - 1) isPrefix(p(i), f(i)) else p(i) == f(i))
    case (ujson.Str(p), ujson.Str(f)) => f.startsWith(p)
    case _                            => partial == full
  }

  private def everyPrefixIsAPrefix(document: String, partialStrings: Boolean = true): Unit = {
    val full = ujson.read(document)
    PartialJson.parse(document, partialStrings) shouldBe Some(full)
    (0 to document.length).foreach { end =>
      val prefix = document.substring(0, end)
      PartialJson.parse(prefix, partialStrings).foreach { value =>
        withClue(s"prefix '$prefix' gave $value: ") {
          isPrefix(value, full) shouldBe true
          if (!partialStrings) value.toString.length should be <= full.toString.length
        }
      }
    }
  }

  private val golden = List(
    """{"vendor": "Acme", "amount": 1250.5, "paid": true, "note": null}""",
    """{"a": {"b": {"c": [1, [2, [3, {"d": "e"}]], []]}}, "f": {}}""",
    """ { "escaped" : "quote \" backslash \\ slash \/ tab \t newline \n" , "x" : false } """,
    """{"unicode": "café 😀 ☃", "raw": "café 😀"}""",
    """[-0.5, 12.25e3, 1E-7, 0, -0, 123456789012345678, true, false, null, "s"]""",
    """{"empty": "", "nested": [{"k": []}, {"k": [{}]}]}""",
    "\"just a string\"",
    "[]"
  )

  "PartialJson.parse" should "return a prefix of the final value for every prefix of the golden documents" in {
    golden.foreach(document => everyPrefixIsAPrefix(document))
  }

  it should "return the complete strings only, and still a prefix, with partialStrings = false" in {
    golden.foreach(document => everyPrefixIsAPrefix(document, partialStrings = false))
    PartialJson.parse("""{"a": "unfinis""", partialStrings = false) shouldBe Some(ujson.Obj())
    PartialJson.parse("""["done", "unfinis""", partialStrings = false) shouldBe Some(ujson.Arr("done"))
  }

  it should "surface a number only once it is complete, never 1 while 12.5 is arriving" in {
    PartialJson.parse("""{"a": 1""") shouldBe Some(ujson.Obj())
    PartialJson.parse("""{"a": 12""") shouldBe Some(ujson.Obj())
    PartialJson.parse("""{"a": 12.""") shouldBe Some(ujson.Obj())
    PartialJson.parse("""{"a": 12.5""") shouldBe Some(ujson.Obj())
    PartialJson.parse("""{"a": 12.5 """) shouldBe Some(ujson.Obj("a" -> 12.5))
    PartialJson.parse("""{"a": 12.5,""") shouldBe Some(ujson.Obj("a" -> 12.5))
    PartialJson.parse("""{"a": 12.5}""") shouldBe Some(ujson.Obj("a" -> 12.5))
    PartialJson.parse("""{"a": 1e""") shouldBe Some(ujson.Obj())
    PartialJson.parse("""[1, -""") shouldBe Some(ujson.Arr(1))
    PartialJson.parse("""[1, 23""") shouldBe Some(ujson.Arr(1))
    PartialJson.parse("12") shouldBe None
    PartialJson.parse("12 ") shouldBe Some(ujson.Num(12))
  }

  it should "surface a literal only once it is complete" in {
    PartialJson.parse("""{"a": tr""") shouldBe Some(ujson.Obj())
    PartialJson.parse("""{"a": true""") shouldBe Some(ujson.Obj("a" -> true))
    PartialJson.parse("""{"a": nul""") shouldBe Some(ujson.Obj())
    PartialJson.parse("""[fals""") shouldBe Some(ujson.Arr())
  }

  it should "leave out a key still being written, and a key whose value has not started" in {
    PartialJson.parse("""{"ven""") shouldBe Some(ujson.Obj())
    PartialJson.parse("""{"vendor"""") shouldBe Some(ujson.Obj())
    PartialJson.parse("""{"vendor": """) shouldBe Some(ujson.Obj())
    PartialJson.parse("""{"vendor": "Ac""") shouldBe Some(ujson.Obj("vendor" -> "Ac"))
    PartialJson.parse("""{"items": [{"sku": "A""") shouldBe Some(
      ujson.Obj("items" -> ujson.Arr(ujson.Obj("sku" -> "A")))
    )
  }

  it should "not show an escape before it has finished, split anywhere" in {
    PartialJson.parse("""["caf\""") shouldBe Some(ujson.Arr("caf"))
    PartialJson.parse("""["caf\u00""") shouldBe Some(ujson.Arr("caf"))
    PartialJson.parse("""["café""") shouldBe Some(ujson.Arr("café"))
    // the high half of a surrogate pair is held back until the low half arrives
    PartialJson.parse("""["\ud83d""") shouldBe Some(ujson.Arr(""))
    PartialJson.parse("""["\ud83d\ude""") shouldBe Some(ujson.Arr(""))
    PartialJson.parse("""["😀""") shouldBe Some(ujson.Arr("😀"))
  }

  it should "return None before any value has started" in {
    PartialJson.parse("") shouldBe None
    PartialJson.parse("   \n") shouldBe None
    PartialJson.parse("{") shouldBe Some(ujson.Obj())
  }

  it should "stop at JSON that is not valid, keeping what came before, and ignore text after the value" in {
    PartialJson.parse("""{"a": 1, "b": oops, "c": 3}""") shouldBe Some(ujson.Obj("a" -> 1))
    PartialJson.parse("""{"a": 1 "b": 2}""") shouldBe Some(ujson.Obj("a" -> 1))
    PartialJson.parse("""{"a": 1,}""") shouldBe Some(ujson.Obj("a" -> 1))
    PartialJson.parse("""[1 2]""") shouldBe Some(ujson.Arr(1))
    PartialJson.parse("""{"a" 1}""") shouldBe Some(ujson.Obj())
    PartialJson.parse("""{1: 2}""") shouldBe Some(ujson.Obj())
    PartialJson.parse("""["\x"]""") shouldBe Some(ujson.Arr(""))
    PartialJson.parse("""["\u12g4"]""") shouldBe Some(ujson.Arr(""))
    PartialJson.parse("""{"n": 01}""") shouldBe Some(ujson.Obj())
    PartialJson.parse("""{"a": 1} trailing""") shouldBe Some(ujson.Obj("a" -> 1))
    PartialJson.parse("oops") shouldBe None
  }

  it should "keep the last value of a repeated key, as ujson does" in {
    PartialJson.parse("""{"a": 1, "a": 2}""") shouldBe Some(ujson.read("""{"a": 1, "a": 2}"""))
  }

  it should "cut nesting deeper than 512 levels instead of overflowing the stack" in {
    val deep  = "[" * 100000
    val value = PartialJson.parse(deep)
    value shouldBe defined
    def depth(v: ujson.Value): Int = v match {
      case ujson.Arr(items) => 1 + items.headOption.map(depth).getOrElse(0)
      case _                => 0
    }
    depth(value.get) shouldBe PartialJson.MaxDepth + 1
  }

  it should "render a complete tree as JSON, numbers as written" in {
    val tree = PartialJson.parseTree("""{"a": [1, 9007199254740993, "x\"y", true, false, null, {}]}""")
    tree.map(_.render) shouldBe Some("""{"a":[1,9007199254740993,"x\"y",true,false,null,{}]}""")
    tree.map(_.isComplete) shouldBe Some(true)
  }

  // ---- random documents chopped at random points

  private lazy val text: Gen[String] =
    Gen
      .listOf(Gen.oneOf(Gen.alphaNumChar, Gen.oneOf('"', '\\', '/', '\n', '\t', '\u0001', 'é', '☃', ' ', '{', ']')))
      .map(_.mkString)
      .flatMap(s => Gen.oneOf(s, s + "😀", "😀" + s))

  private lazy val number: Gen[ujson.Value] = Gen.oneOf(
    Gen.choose(-1000000L, 1000000L).map(n => ujson.Num(n.toDouble)),
    Gen.choose(-1e6, 1e6).map(ujson.Num(_)),
    Gen.choose(-1e-3, 1e-3).map(ujson.Num(_))
  )

  private def json(depth: Int): Gen[ujson.Value] = {
    val leaf = Gen.oneOf(
      text.map(ujson.Str(_)),
      number,
      Gen.oneOf(ujson.True, ujson.False, ujson.Null)
    )
    if (depth <= 0) leaf
    else
      Gen.frequency(
        3 -> leaf,
        1 -> Gen.listOfN(3, json(depth - 1)).map(items => ujson.Arr(items*)),
        2 -> Gen
          .listOfN(4, Gen.zip(Gen.identifier.map(_.take(6)), json(depth - 1)))
          .map(fields => ujson.Obj.from(fields.toMap))
      )
  }

  private lazy val document: Gen[String] = for {
    inner <- json(3)
    // a structured reply is an object or an array; a bare number at the root never knows it is complete
    value         <- Gen.oneOf(ujson.Arr(inner), ujson.Obj("root" -> inner))
    indent        <- Gen.oneOf(-1, 2)
    escapeUnicode <- Gen.oneOf(true, false)
  } yield ujson.write(value, indent = indent, escapeUnicode = escapeUnicode)

  it should "return a prefix of the final value for random documents chopped at random points" in {
    forAll(document, Gen.choose(0.0, 1.0)) { (doc, at) =>
      val full   = ujson.read(doc)
      val prefix = doc.substring(0, (doc.length * at).toInt)
      PartialJson.parse(doc) shouldBe Some(full)
      PartialJson.parse(prefix).foreach(value => isPrefix(value, full) shouldBe true)
      PartialJson.parse(prefix, partialStrings = false).foreach(value => isPrefix(value, full) shouldBe true)
    }
  }

  it should "never throw, whatever the text" in {
    forAll(
      Gen.listOf(Gen.oneOf(Gen.asciiPrintableChar, Gen.oneOf('\\', 'u', '"', '{', '[', ':', ','))).map(_.mkString)
    )(noise => noException should be thrownBy PartialJson.parse(noise))
  }
}
