package org.llm4s.util

import org.llm4s.testutil.LinearTime
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.{ Random, Try }

/**
 * A follow-up of #1675 and #1687 (#1706): Python literals that kept a credential readable - a `pytest` diff, a
 * notebook's `text/plain` lines, a dict cut off after a comma, `str(dict)` inside a `repr`, Python's `\'` inside a JSON
 * string, a keyword argument holding a list, a `\"` in an earlier leaf, and a `'key': "..."` before a key of any
 * length or kind. They are closed by a reading that only adds to what the passes replace, so every character redacted
 * before is redacted still.
 */
class RedactionPythonReprSpec extends AnyFlatSpec with Matchers {

  private val R = Redaction.RedactionPlaceholder

  private def parses(json: String): Boolean = Try(ujson.read(json)).isSuccess

  /** The strings of a JSON document that hold a JSON document themselves, and whether each parses. */
  private def innerDocuments(json: String): Seq[Boolean] = {
    def walk(v: ujson.Value): Seq[Boolean] = v match {
      case ujson.Str(s) =>
        val t = s.trim
        if (t.startsWith("{") || t.startsWith("[")) Seq(parses(t)) ++ Try(ujson.read(t)).toOption.toSeq.flatMap(walk)
        else Nil
      case ujson.Arr(xs) => xs.toSeq.flatMap(walk)
      case ujson.Obj(m)  => m.values.toSeq.flatMap(walk)
      case _             => Nil
    }
    Try(ujson.read(json)).toOption.toSeq.flatMap(walk)
  }

  "Redaction.redact" should "redact a value of a pytest assertion diff, whose next line starts with E (#1706)" in {
    val diff = "E         {'db_password': \"gV3JVQH9QHa'\",\nE          'note': 'hello'}"
    Redaction.redact(diff) shouldBe s"E         {'db_password': \"$R\",\nE          'note': 'hello'}"
    val full = "E       AssertionError: assert {'token': [\"a'QZ1\", 'QZ2']} == {}\nE         Full diff:\n" +
      "E         - {}\nE         + {'token': [\"a'QZ1\",\nE         +            'QZ2']}"
    val out = Redaction.redact(full)
    (out should not).include("QZ1")
    (out should not).include("QZ2")
    out should include("E         + {'token': [")
  }

  it should "redact the values of a notebook's text/plain line array (#1706)" in {
    val notebook =
      """{
        | "output_type": "execute_result",
        | "data": {
        |  "text/plain": [
        |   "{'api_key': 'AAKEY',\n",
        |   " 'credentials': ['abc',\n",
        |   "                 \"QZ'SECRET\"],\n",
        |   " 'password': \"QQ'SECRETZZ\",\n",
        |   " 'passwd': 987654321,\n",
        |   " 'user': 'bob'}"
        |  ]
        | }
        |}""".stripMargin
    val out = Redaction.redact(notebook)
    Seq("AAKEY", "abc", "SECRET", "987654321").foreach(s => (out should not).include(s))
    out should include(" 'user': 'bob'}")
    parses(out) shouldBe true
    ujson.read(out)("data")("text/plain").arr.length shouldBe 6
  }

  it should "redact a value of a dict cut off right after a comma (#1706)" in {
    Redaction.redact("""{'db_password': "x'QZSECRET",""") shouldBe s"""{'db_password': "$R","""
    Redaction.redact("""INFO kwargs={'db_password': "x'QZSECRET", ... (truncated)""") shouldBe
      s"""INFO kwargs={'db_password': "$R", ... (truncated)"""
  }

  it should "redact a value of str(dict) inside a repr'd dict (#1706)" in {
    Redaction.redact("""{'body': '{\'password\': "it\'sSECRET", \'user\': \'bob\'}'}""") shouldBe
      s"""{'body': '{\\'password\\': "$R", \\'user\\': \\'bob\\'}'}"""
    Redaction.redact("""{'body': '{\'password\': \'QZSECRET\', \'user\': \'bob\'}'}""") shouldBe
      s"""{'body': '{\\'password\\': \\'$R\\', \\'user\\': \\'bob\\'}'}"""
  }

  it should "redact a Python value holding \\' inside a JSON string whole (#1706)" in {
    val input = """{"m": "{'password': 'QQ\\'SECRETZZ\"x'}"}"""
    val out   = Redaction.redact(input)
    out shouldBe s"""{"m": "{'password': '$R$R\\"$R'}"}"""
    parses(out) shouldBe true
    (Redaction.redact("""{"m": "{'api_key': 'a:b\\'c=d\"QZSECRET', 'n': 1}"}""") should not).include("QZSECRET")
  }

  it should "redact every leaf of a keyword argument that holds a container (#1706)" in {
    Redaction.redact("Settings(token=['a:b', 'QQSECRETZZ'])") shouldBe s"Settings(token=$R'$R', '$R'])"
    Redaction.redact("Settings(token=('a:b', 'QZSECRET'), retries=3)") shouldBe
      s"Settings(token=$R'$R', '$R'), retries=3)"
    (Redaction.redact("Settings(credentials={'user': 'bob', 'pw': 'QZSECRET'})") should not).include("QZSECRET")
    (Redaction.redact("""{"code": "Config(token='a:b\\'c=d\"QZSECRET')"}""") should not).include("QZSECRET")
  }

  it should "redact a leaf after a \\\" in an earlier single-quoted leaf (#1706)" in {
    val input = """{"extra": "{'credentials': ['abc\"d'], 'secret': [\"KGQBJ9'\"]}"}"""
    val out   = Redaction.redact(input)
    out shouldBe s"""{"extra": "{'credentials': ['$R'], 'secret': [\\"$R\\"]}"}"""
    parses(out) shouldBe true
  }

  it should "redact a 'key': \"...\" value whatever key follows it (#1706)" in {
    Seq(
      "'" + "k" * 70 + "': 1",
      "1: 2",
      "None: 2",
      "**extra",
      "('a', 1): 't'"
    ).foreach { next =>
      Redaction.redact(s"""{'password': "x'QZSECRET", $next}""") shouldBe s"""{'password': "$R", $next}"""
    }
  }

  it should "leave text with no sensitive Python field as it was (#1706)" in {
    Seq(
      "{'user': 'bob', 'note': \"it's fine\"}",
      "Settings(region='us-east-1', retries=[1, 2])",
      "E         {'model': 'gpt-4o',\nE          'mode': 'x'}",
      """{"m": "{'msg': 'say \"hi\"', 'n': 1}"}""",
      "the 'token' field is required; don't set password= in .env"
    ).foreach(input => Redaction.redact(input) shouldBe input)
  }

  it should "never throw, whatever the input (#1706)" in {
    // Pieces of Python literals, escapes, quotes, keys and JSON punctuation, valid or not, joined at random.
    val bs  = "\\"
    val rnd = new Random(17061)
    val pieces = Seq(
      bs,
      bs * 2,
      bs * 3,
      bs * 7,
      "'",
      bs + "'",
      bs * 2 + "'",
      "\"",
      bs + "\"",
      bs * 3 + "\"",
      s"${bs}u",
      s"${bs}u00",
      s"${bs}u0027",
      s"${bs}uZZZZ",
      s"${bs}n",
      s"${bs}${bs}n",
      "'password': ",
      bs + "'token" + bs + "': ",
      "token=",
      "api_key=[",
      "secret=(",
      "b'",
      "rb\"",
      "{",
      "}",
      "[",
      "]",
      "(",
      ")",
      ",",
      ":",
      " ",
      "\n",
      "\nE    ",
      "\nE   + ",
      "\r",
      "True",
      "None",
      "123",
      "-4",
      "x",
      "QZ",
      "\", \"",
      "\u0000",
      "￿"
    )
    (1 to 2000).foreach { _ =>
      val noise = Seq.fill(1 + rnd.nextInt(40))(pieces(rnd.nextInt(pieces.length))).mkString
      val input = rnd.nextInt(3) match {
        case 0 => noise
        case 1 => ujson.write(ujson.Obj("m" -> noise))
        case _ => ujson.write(ujson.Obj("text/plain" -> ujson.Arr(noise + "\n", noise)))
      }
      withClue(s"input $input: ") {
        Try(Redaction.redact(input)).isSuccess shouldBe true
        Try(Redaction.safeBody(input)).isSuccess shouldBe true
      }
    }
  }

  /** Python literals in #1706's shapes, each with its credentials, unique words that appear nowhere else. */
  final private class Docs(seed: Long) {
    private val rnd                    = new Random(seed)
    private var n                      = 0
    private var secrets                = Vector.empty[String]
    private def pick[A](xs: Seq[A]): A = xs(rnd.nextInt(xs.length))
    private val keys                   = Seq("password", "token", "api_key", "client_secret", "secret", "credentials")

    /** Python's repr of a str. */
    private def py(s: String): String = {
      val q = if (s.contains('\'') && !s.contains('"')) '"' else '\''
      q.toString + s.flatMap {
        case '\\'        => "\\\\"
        case c if c == q => "\\" + c
        case c           => c.toString
      } + q
    }

    private def secret(): String = {
      n += 1
      val w = s"Qz${n}w"
      secrets :+= w
      py(pick(Seq(w, s"x'$w", s"$w\"q", s"a:b'c=d\"$w", s"$w\\'x", s"O'$w", s"$w, '9", s"$w'}", s"$w\" , \"k\": \"")))
    }

    private def value(): String =
      pick(Seq(secret(), secret(), s"[${secret()}, ${secret()}]", s"(${secret()},)", s"{'pw': ${secret()}}"))

    private def dict(): String =
      (0 until 1 + rnd.nextInt(3))
        .map(_ => if (rnd.nextBoolean()) s"'${pick(keys)}': ${value()}" else s"'${pick(Seq("user", "note"))}': 'bob'")
        .mkString("{", ", ", "}")

    def next(): (String, Seq[String]) = {
      secrets = Vector.empty
      val d = dict()
      val text = rnd.nextInt(8) match {
        case 0 => "E         " + d.replace(", '", ",\nE          '")
        case 1 =>
          val lines = d.replace(", '", ",\n '").split("\n", -1).toSeq
          ujson.write(ujson.Obj("text/plain" -> ujson.Arr.from(lines.init.map(_ + "\n") :+ lines.last)))
        case 2 => d.take(rnd.nextInt(d.length) + 1)
        case 3 => s"{'body': ${py(d)}}"
        case 4 => ujson.write(ujson.Obj("m" -> d, "n" -> 1))
        case 5 => s"Settings(${pick(keys)}=${value()}, retries=3)"
        case 6 => ujson.write(ujson.Obj("extra" -> s"{'note': ['abc\"d'], ${d.drop(1)}"))
        case _ =>
          s"""{'${pick(keys)}': ${secret()}, ${pick(Seq("1: 2", "None: 1", "**extra", "'" + "k" * 70 + "': 1"))}}"""
      }
      (text, secrets)
    }
  }

  it should "replace all it replaces without the added readings, and more (#1706)" in {
    // The reading of #1706 adds edits only where no edit already is: every range the passes and the readings before
    // it replace is replaced as before, nothing they redact is written out, and JSON that parses without it - and
    // every JSON document inside a string of it - still parses. Deterministic.
    val docs   = new Docs(1706)
    var closed = 0
    (1 to 2000).foreach { _ =>
      val (input, secrets) = docs.next()
      val (out, added)     = Redaction.redactedWithRanges(input, singleQuotedFromInput = true)
      val (base, kept)     = Redaction.redactedWithRanges(input, singleQuotedFromInput = true, addedReadings = false)
      withClue(s"input $input, output $out, without $base: ") {
        Redaction.redact(input) shouldBe out
        kept.filterNot(added.contains) shouldBe empty
        added.filterNot(kept.contains).foreach { case (s, e) =>
          kept.exists { case (ks, ke) => ke > s && ks < e } shouldBe false
        }
        secrets.filterNot(base.contains).filter(out.contains) shouldBe empty
        if (parses(base)) {
          parses(out) shouldBe true
          innerDocuments(out).count(identity) should be >= innerDocuments(base).count(identity)
        }
      }
      closed += secrets.count(s => base.contains(s) && !out.contains(s))
    }
    // and it is the reading that redacts what the passes left
    closed should be > 500
  }

  it should "read Python values in time linear in the input (#1706)" in {
    val bs = "\\"
    val shapes: Seq[(String, Int => String)] = Seq(
      "values never closed"   -> (n => "{'password': 'a, " * n),
      "values after a comma"  -> (n => "{" + "'password': \"x'y\", " * n + "'n': 1}"),
      "nested containers"     -> (n => "{'token': " + "[" * n + "'x'" + "]" * n + "}"),
      "containers never shut" -> (n => "{'token': [" + "'a', [" * n),
      "runs before quotes"    -> (n => "{'password': '" + (bs * 7 + "'") * n + "'}"),
      "escaped keys"          -> (n => (bs * 5 + "'password" + bs * 5 + "': " + bs * 5 + "'x" + bs * 5 + "', ") * n),
      "quotes and spaces"     -> (n => ("\"" + " " * 30) * n + "{'password': 'x'}"),
      "notebook lines" -> (n => "{\"text/plain\": [\"{'token': ['a',\\n\", " + "\"  'b',\\n\", " * n + "\"'c']}\"]}"),
      "pytest lines"   -> (n => "E   {'token': ['a',\n" + "E    'b',\n" * n + "E    'c']}"),
      "keyword arguments" -> (n => "Settings(" + "token=['a', " * n),
      "joined strings"    -> (n => "{'password': " + "'a' " * n + "}")
    )
    shapes.foreach { case (name, build) =>
      LinearTime.assertLinear(name, build(1000), build(4000))(Redaction.redact(_))
    }
  }
}
