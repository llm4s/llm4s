package org.llm4s.util

import org.llm4s.testutil.LinearTime
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.{ Random, Try }

/**
 * The password of a URL's userinfo, `scheme://user:password@host` (#1816): a provider `baseUrl`, a proxy or a
 * database URL in a connection error, an HTTP client error or the provider exchange log.
 */
class RedactionUrlUserinfoSpec extends AnyFlatSpec with Matchers {

  private val R = Redaction.RedactionPlaceholder

  private def parses(json: String): Boolean = Try(ujson.read(json)).isSuccess

  "Redaction.redact" should "redact the password of a URL's userinfo and keep the user and the host (#1816)" in {
    Redaction.redact("https://alice:s3cret-pass@gw.example.com/v1") shouldBe s"https://alice:$R@gw.example.com/v1"
    Redaction.redact("connect to postgres://app:hunter2@db:5432/prod failed") shouldBe
      s"connect to postgres://app:$R@db:5432/prod failed"
    Redaction.redact("jdbc:postgresql://app:hunter2@db/prod") shouldBe s"jdbc:postgresql://app:$R@db/prod"
    Redaction.redact("redis://:hunter2@cache:6379/0") shouldBe s"redis://:$R@cache:6379/0"
    Redaction.redact("mongodb+srv://u:hunter2@cluster0.x.net") shouldBe s"mongodb+srv://u:$R@cluster0.x.net"
    Redaction.redact("http://u:p@[::1]:8080/") shouldBe s"http://u:$R@[::1]:8080/"
  }

  it should "redact it inside a JSON string, with the slashes escaped or not, and the JSON still parses (#1816)" in {
    val escaped = """{"baseUrl":"https:\/\/alice:s3cret-pass@gw.example.com\/v1","n":1}"""
    Redaction.redact(escaped) shouldBe s"""{"baseUrl":"https:\\/\\/alice:$R@gw.example.com\\/v1","n":1}"""
    val plain = """{"baseUrl":"https://alice:s3cret-pass@gw.example.com/v1"}"""
    Redaction.redact(plain) shouldBe s"""{"baseUrl":"https://alice:$R@gw.example.com/v1"}"""
    // one string deeper, the slashes behind three backslashes each
    val nested = ujson.write(ujson.Obj("log" -> escaped))
    val out    = Redaction.redact(nested)
    (out should not).include("s3cret-pass")
    parses(out) shouldBe true
    parses(ujson.read(out)("log").str) shouldBe true
    Seq(escaped, plain).foreach(json => parses(Redaction.redact(json)) shouldBe true)
  }

  it should "redact it in a query-string context (#1816)" in {
    Redaction.redact("GET /cb?next=https://bob:hunter2@idp.example.com/x&state=abc") shouldBe
      s"GET /cb?next=https://bob:$R@idp.example.com/x&state=abc"
    Redaction.redact("?redirect=ftp://u:pw0rd@files.example.com") shouldBe s"?redirect=ftp://u:$R@files.example.com"
  }

  it should "read a percent-encoded userinfo, and a password that holds @ or : unencoded, whole (#1816)" in {
    Redaction.redact("https://svc%40corp:p%40ss%3Aw0rd@h.example.com/") shouldBe s"https://svc%40corp:$R@h.example.com/"
    Redaction.redact("https://u:p@ss@h.example.com/") shouldBe s"https://u:$R@h.example.com/"
    Redaction.redact("https://u:pa:ss@h.example.com/") shouldBe s"https://u:$R@h.example.com/"
  }

  it should "leave a URL with no password as it was (#1816)" in {
    Seq(
      "https://gw.example.com/v1",              // no userinfo
      "https://alice@gw.example.com/v1",        // user only
      "https://alice@gw.example.com:8443/v1",   // user only, with a port
      "https://gw.example.com/users/@alice:x",  // `@` in the path
      "https://gw.example.com/x?email=a@b.com", // `@` in the query
      "https://gw.example.com#a:b@c",           // `@` in the fragment
      "https://alice:@gw.example.com/",         // an empty password
      "git@github.com:org/repo.git",            // no scheme
      "mailto:bob@example.com",
      "at 12:30 bob@example.com wrote"
    ).foreach(input => Redaction.redact(input) shouldBe input)
  }

  it should "redact it in a Python repr and in prose, and keep what follows the URL (#1816)" in {
    Redaction.redact("Settings(url='https://u:hunter2@h.io/v1', retries=3)") shouldBe
      s"Settings(url='https://u:$R@h.io/v1', retries=3)"
    Redaction.redact("""<a href="https://u:hunter2@h.io">x</a> and bob@example.com""") shouldBe
      s"""<a href="https://u:$R@h.io">x</a> and bob@example.com"""
  }

  it should "redact the password of every URL of a list joined by , ; ) or ', and a second redaction changes nothing (#1816)" in {
    Seq(
      "redis://u:p1@h1,redis://u:p2@h2"                      -> s"redis://u:$R@h1,redis://u:$R@h2",
      "https://u:p1@h1;https://u:p2@h2"                      -> s"https://u:$R@h1;https://u:$R@h2",
      "https://u:p1@h1,https://u:p2@h2"                      -> s"https://u:$R@h1,https://u:$R@h2",
      "REDIS_URLS=redis://u:p1@h1:6379;redis://u:p2@h2:6379" -> s"REDIS_URLS=redis://u:$R@h1:6379;redis://u:$R@h2:6379",
      "urls=[redis://u:p1@h1,amqp://u:p2@h2]"                -> s"urls=[redis://u:$R@h1,amqp://u:$R@h2]",
      "(https://u:p1@h1),(https://u:p2@h2)"                  -> s"(https://u:$R@h1),(https://u:$R@h2)",
      "proxy=http://u:p1@h1:3128,https=http://u:p2@h2:3128"  -> s"proxy=http://u:$R@h1:3128,https=http://u:$R@h2:3128",
      "https://h1,https://u:p2@h2"                           -> s"https://h1,https://u:$R@h2",
      "https://u:p1@h1'https://u:p2@h2"                      -> s"https://u:$R@h1'https://u:$R@h2",
      "https://u:p1@h1:443:https://u:p2@h2"                  -> s"https://u:$R@h1:443:https://u:$R@h2",
      "https:\\/\\/u:p1@h1,https:\\/\\/u:p2@h2"              -> s"https:\\/\\/u:$R@h1,https:\\/\\/u:$R@h2",
      "mongodb://u:p1@h1:27017,h2:27017/db?replicaSet=rs0"   -> s"mongodb://u:$R@h1:27017,h2:27017/db?replicaSet=rs0",
      "redis://u:p1@h1,u2:p2@h2"                             -> s"redis://u:$R@h1,u2:$R@h2"
    ).foreach { case (input, expected) =>
      withClue(s"input $input: ") {
        val once = Redaction.redact(input)
        once shouldBe expected
        Redaction.redact(once) shouldBe once
      }
    }
    val json = ujson.write(ujson.Obj("urls" -> "redis://u:p1@h1,redis://u:p2@h2"))
    val out  = Redaction.redact(json)
    out shouldBe s"""{"urls":"redis://u:$R@h1,redis://u:$R@h2"}"""
    parses(out) shouldBe true
  }

  it should "keep an email address or a host and port after a , in an authority (#1816)" in {
    Redaction.redact("https://u:p@h1,a@b.com") shouldBe s"https://u:$R@h1,a@b.com"
    Redaction.redact("https://u:p@h1;a@b.com") shouldBe s"https://u:$R@h1;a@b.com"
    Seq(
      "https://example.com:8080,bob@corp.com",
      "https://10.0.0.1:443;ops@corp.com",
      "a@b.com,https://h1"
    ).foreach(input => Redaction.redact(input) shouldBe input)
    Redaction.redact("https://example.com:8080,u:p@h2") shouldBe s"https://example.com:8080,u:$R@h2"
    // a password holding `,` or `;` and no `@` before it is still read whole
    Redaction.redact("https://u:pa,ss;w@h") shouldBe s"https://u:$R@h"
    Redaction.redact("https://u:1234,5@h") shouldBe s"https://u:$R@h"
  }

  /** Documents with URLs whose password is a unique word, in the shapes the tests above cover, mixed. */
  private def docs(seed: Long, n: Int): Seq[(String, String)] = {
    val rnd                    = new Random(seed)
    def pick[A](xs: Seq[A]): A = xs(rnd.nextInt(xs.length))
    (1 to n).map { i =>
      val secret = s"Pw${i}x" + pick(Seq("", "%40", "@", "!", "&", "=", "'", "+", ",", ";", ":")) + s"Zq$i"
      val url = s"${pick(Seq("https", "postgres", "redis", "jdbc:mysql"))}://${pick(Seq("u", "a%40b", ""))}:$secret@" +
        pick(Seq("h.io", "db:5432", "[::1]:80")) + pick(Seq("", "/", "/v1?x=1", "#f"))
      val doc = pick(
        Seq(
          url,
          s"error: $url refused",
          ujson.write(ujson.Obj("u" -> url)),
          ujson.write(ujson.Obj("u" -> url)).replace("/", "\\/"),
          s"?next=$url&a=1",
          s"{'url': '$url'}",
          s"token=abc $url password=xyz"
        )
      )
      (doc, s"Zq$i")
    }
  }

  it should "replace all it replaces without the added readings, and the password too (#1816)" in {
    docs(1816, 2000).foreach { case (input, secret) =>
      val (out, added) = Redaction.redactedWithRanges(input, singleQuotedFromInput = true)
      val (base, kept) = Redaction.redactedWithRanges(input, singleQuotedFromInput = true, addedReadings = false)
      withClue(s"input $input, output $out, without $base: ") {
        Redaction.redact(input) shouldBe out
        kept.filterNot(added.contains) shouldBe empty
        (out should not).include(secret)
        if (parses(input)) parses(out) shouldBe true
      }
    }
  }

  it should "never throw, and read URLs in time linear in the input (#1816)" in {
    val rnd = new Random(1816)
    (1 to 2000).foreach { _ =>
      val input = Iterator
        .fill(1 + rnd.nextInt(40))(rnd.nextInt(8) match {
          case 0 => "://"
          case 1 => ":"
          case 2 => "@"
          case 3 => "\\"
          case 4 => "/"
          case 5 => "a"
          case 6 => "\""
          case _ => " "
        })
        .mkString
      withClue(s"input $input: ")(Try(Redaction.redact(input)).isSuccess shouldBe true)
    }
    val shapes: Seq[(String, Int => String)] = Seq(
      "schemes"          -> (n => "a://" * n),
      "userinfo"         -> (n => "x://u:p@" * n),
      "colons"           -> (n => "https://" + ":" * (4 * n) + "@"),
      "unclosed"         -> (n => "https://u:" + "p" * (4 * n)),
      "escaped slashes"  -> (n => "a:\\/\\/u:p@h " * n),
      "deep backslashes" -> (n => ("a:" + "\\" * 30 + "/") * n),
      "list"             -> (n => "redis://u:p@h," * n),
      "list items"       -> (n => "https://u:p@h1," + "a:b@c," * n),
      "nested schemes"   -> (n => "https://u:p@" + "a:" * n + "//x"),
      "commas"           -> (n => "https://u:p@h" + ",@" * n)
    )
    shapes.foreach { case (name, build) =>
      LinearTime.assertLinear(name, build(1000), build(4000))(Redaction.redact(_))
    }
  }
}
