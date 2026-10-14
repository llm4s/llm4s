package org.llm4s.util

import org.llm4s.testutil.{ LinearTime, PerformanceTest }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.Random

/**
 * The timing backstop of `Redaction` (#1932), run by `sbt testPerformance`. Redaction is one forward pass over a small
 * tokenizer, linear in its input by construction; these two tests are the check that it stays so, in place of the
 * per-shape scaling tests that guarded each regex pass (#1675, #1687, #1697, ...).
 */
class RedactionScalingSpec extends AnyFlatSpec with Matchers {

  private def u(s: String): String = s.replace("~", "\\" + "u00")

  /**
   * Units that made a pass of the redaction before #1932 grow with the square of its input - each repeated, a shape
   * that asks a lookahead, a rescan or a search per unit - with the operation that read them.
   */
  private val shapes: Seq[(String, Int => String)] = Seq(
    "JSON mentions of 'token': [" -> (n =>
      "[" + s"""{"role": "user", "content": "see 'token': [ for details", "api_key": "hunter2value"}, """ * n + "{}]"
    ),
    "single-quoted fields" -> (n =>
      "{" + "'note': 'see \"password\": \" here', 'apiKey': [\"7YJ1'}\", -99], " * n + "}"
    ),
    "values never closed"   -> (n => "{'password': 'a, " * n),
    "nested containers"     -> (n => "{'token': " + "[" * n + "'x'" + "]" * n + "}"),
    "containers never shut" -> (n => "{'token': [" + "'a', [" * n),
    "runs before quotes"    -> (n => "{'password': '" + ("\\" * 7 + "'") * n + "'}"),
    "quotes and spaces"     -> (n => ("\"" + " " * 30) * n + "{'password': 'x'}"),
    "notebook lines" -> (n => "{\"text/plain\": [\"{'token': ['a',\\n\", " + "\"  'b',\\n\", " * n + "\"'c']}\"]}"),
    "pytest lines"   -> (n => "E   {'token': ['a',\n" + "E    'b',\n" * n + "E    'c']}"),
    "keyword calls"  -> (n => "token=S(" * n),
    "joined strings" -> (n => "{'password': " + "'a' " * n + "}"),
    "escaped quotes, one deep" -> (n => "password=" + "\\'" * n),
    "an open escaped value"    -> (n => "token=\\\\'" + "a&b=c" * n),
    "a long backslash run"     -> (n => "password=\\\"" + "\\" * (n * 10) + "x\\\""),
    "Gson log lines"           -> (n => "{\"log\":\"" + "user\\u003dbob\\ntoken\\u003dabc " * n + "\"}"),
    "queries before JSON"      -> (n => "?a=1&token=abc,{\\\"api_key\\\":\\\"S\\\"} " * n),
    "Bearer pairs"             -> (n => "Bearer password=x " * n),
    "quoted query credentials" -> (n => u("?password=~27") + u("a~26b=1;c,d") * n),
    "merged escaped passes"    -> (n => u("?token=ab'cd~26password='x y'~26") * n),
    "cookie headers"           -> (n => """{"log":"Cookie: a\r\nCookie: b\"c\"", "x": 1}, """ * n),
    "one long cookie value"    -> (n => """{"log":"Cookie: """ + """\" , [x""" * n),
    "plural arrays"            -> (n => "{\"api_keys\": [\"a\", " * n),
    "matrix parameters"        -> (n => ";jsessionid=a" * n),
    "URLs"                     -> (n => "https://u:p@" + "a:" * n + "//x"),
    "list of URLs"             -> (n => "redis://u:p@h," * n),
    "spaceless assertions"     -> (n => "{\"b\":\"" + "{\\\"assertion\\\":\\\"Q\\\"}," * n + "\"}"),
    "vendor key prefixes"      -> (n => "eyJ" * n + ".eyJx.y AIza" * n),
    "strings at depth three" -> (n =>
      "{\"a\":\"{\\\"b\\\":\\\"{\\\\\\\"c\\\\\\\": \\\\\\\"" + "x " * n + "\\\\\\\"}\\\"}\"}"
    )
  )

  "Redaction.redact" should "run in time linear in its input on every shape that was quadratic before (#1932)" taggedAs PerformanceTest in {
    shapes.foreach { case (name, build) =>
      LinearTime.assertLinear(name, build(1000), build(4000))(Redaction.redact(_))
    }
    def echoing(n: Int): String =
      (1 to n).map(i => s"""{"access_token":"tok-$i-${i * 7919}","token_type":"Bearer"}""").mkString("[", ",", "]")
    LinearTime.assertLinear("scrubRemote of echoed tokens", echoing(1000), echoing(4000))(
      Redaction.scrubRemote(_, Seq("configured-secret"))
    )
  }

  /** Text with credentials in the shapes a provider error body echoes, `size` characters or a little more. */
  private def body(seed: Long, size: Int): String = {
    val rnd = new Random(seed)
    val sb  = new StringBuilder
    var i   = 0
    while (sb.length < size) {
      i += 1
      sb ++= (rnd.nextInt(6) match {
        case 0 => s"""{"error": {"message": "bad request $i", "api_key": "Kq${i}secret"}}, """
        case 1 => s"Authorization: Bearer Tk${i}bearer\n"
        case 2 => s"GET /v1?key=Qk${i}query&page=$i HTTP/1.1\n"
        case 3 => s"{'password': 'Pw${i}py', 'user': 'bob'} "
        case 4 => s"""{"password": "${"p" * rnd.nextInt(900)}", "n": $i} """
        case _ => "lorem ipsum dolor sit amet " * (1 + rnd.nextInt(20))
      })
    }
    sb.toString
  }

  "Redaction.safeBody" should "cost about what a 128 KiB body costs, whatever the body's size (#1680)" taggedAs PerformanceTest in {
    // Redacting 16 MiB whole costs about 128 times redacting 128 KiB; a prefix of at most 64 KiB, about as much.
    val big   = body(16, 16 * 1024 * 1024)
    val small = body(16, 128 * 1024).take(128 * 1024)
    def best(f: => Any): Long = (0 until 3).map { _ =>
      val start = System.nanoTime(); f; System.nanoTime() - start
    }.min
    (0 until 2).foreach { _ =>
      Redaction.safeBody(big, 2048); Redaction.safeBody(small, 2048)
    }
    val prefix = best(Redaction.safeBody(big, 2048))
    val whole  = best(Redaction.safeBody(small, 2048))
    withClue(s"safeBody of 16 MiB took ${prefix / 1000000} ms, of 128 KiB ${whole / 1000000} ms: ") {
      prefix should be < 8 * whole
    }
  }
}
