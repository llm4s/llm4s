package org.llm4s.schema

// The build compiles with -source:3.3, which turns off named tuple values and field access by name; Scala 3.7's
// default source level has them.
import scala.language.`3.7`

import org.llm4s.schema.Fixtures.*
import org.llm4s.schema.NamedTupleFixtures.*
import org.llm4s.schema.namedtuples.given
import org.scalacheck.{ Arbitrary, Gen }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import upickle.default.{ read, write, ReadWriter }

import scala.util.Try

/**
 * The `ReadWriter` of [[namedtuples]] agrees with the derived schema (#2328): what it writes satisfies the schema and
 * reads back equal, every document the schema allows reads back, and it writes exactly what uPickle writes for the
 * case class with the same fields, so swapping it for an instance uPickle may add later is safe.
 */
class NamedTupleRoundTripSpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks {

  private val text: Gen[String] = Gen.oneOf(Gen.alphaNumStr, Gen.const(""), Gen.const("é \"quoted\" \n line"))
  private val inner: Gen[Inner] = Gen.choose(-1000, 1000).map(Inner(_))
  private val color: Gen[Color] = Gen.oneOf(Color.values.toSeq)
  // a Long within 2^53, which uPickle writes as a number (see the guide on larger values)
  private val long: Gen[Long]     = Gen.choose(-(1L << 53), 1L << 53)
  private val double: Gen[Double] = Gen.choose(-1e9, 1e9)

  private val mixedClass: Gen[MixedClass] = for {
    name       <- text
    count      <- Arbitrary.arbitrary[Int]
    big        <- long
    ratio      <- double
    flag       <- Arbitrary.arbitrary[Boolean]
    amount     <- Gen.choose(-1000000L, 1000000L).map(c => BigDecimal(c) / 100)
    inn        <- inner
    col        <- color
    described  <- text.map(Described(_))
    items      <- Gen.listOf(inner)
    tags       <- Gen.listOf(text).map(_.toVector)
    maybe      <- Gen.option(color)
    maybeInner <- Gen.option(inner)
    options    <- Gen.listOf(Gen.option(Arbitrary.arbitrary[Int]))
  } yield MixedClass(
    name,
    count,
    big,
    ratio,
    flag,
    amount,
    inn,
    col,
    described,
    items,
    tags,
    maybe,
    maybeInner,
    options
  )

  private def toTuple(c: MixedClass): Mixed = (
    name = c.name,
    count = c.count,
    big = c.big,
    ratio = c.ratio,
    flag = c.flag,
    amount = c.amount,
    inner = c.inner,
    color = c.color,
    described = c.described,
    items = c.items,
    tags = c.tags,
    maybe = c.maybe,
    maybeInner = c.maybeInner,
    options = c.options
  )

  private val point: Gen[(x: Int, y: Int)] =
    for {
      x <- Arbitrary.arbitrary[Int]
      y <- Arbitrary.arbitrary[Int]
    } yield (x = x, y = y)
  private val plot: Gen[Plot] = for {
    title  <- text
    p      <- point
    points <- Gen.listOf(point)
    label <- Gen.option(
      text.map[(text: String)](Tuple1(_))
    ) // a one-element named tuple value; scalafmt drops the parentheses of `(text = t)`
  } yield (title = title, point = p, points = points, label = label)

  private def conforms[A: SchemaOf: ReadWriter](value: A): Unit = {
    val json = ujson.read(write(value))
    withClue(s"${ujson.write(json)}: ") {
      JsonSchemaValidator.validate(SchemaOf[A].toJsonSchema(), json) shouldBe Nil
      JsonSchemaValidator.validate(SchemaOf[A].toJsonSchema(strict = false), json) shouldBe Nil
      read[A](json) shouldBe value
    }
  }

  private val sampleMixed = MixedClass(
    "n",
    1,
    2L,
    0.5,
    true,
    BigDecimal("1.25"),
    Inner(3),
    Color.Blue,
    Described("d"),
    List(Inner(4)),
    Vector("t"),
    Some(Color.Red),
    None,
    List(Some(1), None)
  )

  extension (value: ujson.Value) private def tap(f: ujson.Value => Unit): ujson.Value = { f(value); value }

  // uPickle reports a failure as a trace exception naming the path, with the reader's message as its cause
  private def failure(read: => Any): String =
    Iterator
      .iterate(Try(read).failed.get)(_.getCause)
      .takeWhile(_ != null)
      .map(_.getMessage)
      .mkString(" | ")

  "What the named tuple writer produces" should "satisfy the schema and read back equal" in {
    forAll(mixedClass)(c => conforms[Mixed](toTuple(c)))
    forAll(plot)(o => conforms[Plot](o))
  }

  it should "be exactly what uPickle writes for the case class with the same fields" in {
    forAll(mixedClass)(c => write[Mixed](toTuple(c)) shouldBe write(c))
    forAll(plot) { o =>
      val asClass = PlotClass(
        o.title,
        Point(o.point.x, o.point.y),
        o.points.map(p => Point(p.x, p.y)),
        o.label.map(l => Label(l.text))
      )
      write[Plot](o) shouldBe write(asClass)
    }
    write[Review]((sentiment = Color.Red, confidence = 0.5, quote = None)) shouldBe
      write(ReviewClass(Color.Red, 0.5, None))
  }

  it should "read what uPickle writes for the case class" in {
    forAll(mixedClass)(c => read[Mixed](write(c)) shouldBe toTuple(c))
  }

  "Every document the schema allows" should "be read into a named tuple that is written as a document it allows" in {
    def allRead[A: SchemaOf: ReadWriter]: Unit = {
      val schema  = SchemaOf[A].toJsonSchema()
      val samples = SchemaSamples.of(schema)
      samples should not be empty
      for (sample <- samples)
        withClue(s"${ujson.write(sample)}: ") {
          JsonSchemaValidator.validate(schema, sample) shouldBe Nil
          val value = Try(read[A](sample)).toEither.left.map(_.toString)
          value shouldBe a[Right[?, ?]]
          value.foreach(v => JsonSchemaValidator.validate(schema, ujson.read(write(v))) shouldBe Nil)
        }
    }
    allRead[Review]
    allRead[Mixed]
    allRead[Plot]
    allRead[WithSum]
    allRead[HoldsTuples]
  }

  "The named tuple reader" should "read an absent Option element as None, as null is" in {
    read[Review]("""{"sentiment":"Green","confidence":0.9}""") shouldBe
      ((sentiment = Color.Green, confidence = 0.9, quote = None))
    read[Review]("""{"sentiment":"Green","confidence":0.9,"quote":null}""") shouldBe
      ((sentiment = Color.Green, confidence = 0.9, quote = None))
  }

  it should "refuse a missing element that is not an Option, naming it as uPickle does for a case class" in {
    failure(read[Review]("""{"quote":"x"}""")) should include("missing keys in dictionary: sentiment, confidence")
    failure(read[ReviewClass]("""{"quote":"x"}""")) should include(
      "missing keys in dictionary: sentiment, confidence"
    )
  }

  it should "ignore unknown keys and keep the first of a repeated key, as uPickle's case class reader does" in {
    val json = """{"sentiment":"Red","extra":{"a":[1,2]},"confidence":1,"confidence":2,"quote":null}"""
    read[Review](json) shouldBe ((sentiment = Color.Red, confidence = 1.0, quote = None))
    read[ReviewClass](json) shouldBe ReviewClass(Color.Red, 1.0, None)
  }

  it should "refuse a document that is not an object" in {
    failure(read[Review]("""["Red", 1.0, null]""")) should include("expected dictionary")
    failure(read[Review]("\"Red\"")) should include("expected dictionary")
  }

  it should "name the element whose value its reader refuses, in a trace" in {
    val error = Try(read[Mixed](ujson.read(write(toTuple(sampleMixed))).tap(_("amount") = "abc"), trace = true))
    error.failed.get match {
      case traced: upickle.core.TraceVisitor.TraceException => traced.jsonPath shouldBe "$['amount']"
      case other                                            => fail(s"unexpected $other")
    }
  }

  it should "read and write the empty named tuple as an empty object" in {
    write[NamedTuple.Empty](NamedTuple.Empty) shouldBe "{}"
    read[NamedTuple.Empty]("""{"ignored":1}""") shouldBe NamedTuple.Empty
  }

  it should "read and write a named tuple longer than 22 elements" in {
    type Wide = (
      a1: Int,
      a2: Int,
      a3: Int,
      a4: Int,
      a5: Int,
      a6: Int,
      a7: Int,
      a8: Int,
      a9: Int,
      a10: Int,
      a11: Int,
      a12: Int,
      a13: Int,
      a14: Int,
      a15: Int,
      a16: Int,
      a17: Int,
      a18: Int,
      a19: Int,
      a20: Int,
      a21: Int,
      a22: Int,
      a23: Option[String]
    )
    val json  = ujson.Obj.from((1 to 22).map(i => s"a$i" -> ujson.Num(i)) :+ ("a23" -> ujson.Str("x")))
    val value = read[Wide](json)
    value.a1 shouldBe 1
    value.a22 shouldBe 22
    value.a23 shouldBe Some("x")
    ujson.read(write[Wide](value)) shouldBe json
    JsonSchemaValidator.validate(SchemaOf[Wide].toJsonSchema(), json) shouldBe Nil
  }

}
