package org.llm4s.util

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import scala.util.Random

class RedactionScrubSpec extends AnyFlatSpec with Matchers {

  private val P = Redaction.RedactionPlaceholder

  /** The implementation `scrub` replaced: one whole-input `replace` per form, longest first. */
  private def referenceScrub(input: String, secrets: Iterable[String]): String = {
    def jsonEscaped(value: String): String = {
      val rendered = ujson.Str(value).render()
      rendered.substring(1, rendered.length - 1)
    }
    val forms = secrets.iterator
      .filter(_ != null)
      .map(_.trim)
      .filter(_.nonEmpty)
      .flatMap(secret => Iterator(secret, URLEncoder.encode(secret, StandardCharsets.UTF_8), jsonEscaped(secret)))
      .toSeq
      .distinct
      .sortBy(-_.length)
    forms.foldLeft(input)((acc, secret) => acc.replace(secret, P))
  }

  private def forms(secret: String): Seq[String] =
    Seq(secret, URLEncoder.encode(secret, StandardCharsets.UTF_8), ujson.Str(secret).render().drop(1).dropRight(1))

  "Redaction.scrub" should "match the previous implementation on hand-written bodies" in {
    val cases: Seq[(String, Seq[String])] = Seq(
      ("""{"error":"bad token opaque-bearer-99"}""", Seq("opaque-bearer-99")),
      (
        """{"detail":"s3cr+t/\"value\"-1234"} raw=s3cr+t/"value"-1234 form=s3cr%2Bt%2F%22value%22-1234""",
        Seq("s3cr+t/\"value\"-1234")
      ),
      ("a secret-long-value and secret-long and secret-long-value-x", Seq("secret-long", "secret-long-value")),
      ("prefix abcdefgh suffix abcdefgh abcdefghij", Seq("abcdefgh", "abcdefghij")),
      ("no secret here", Seq("absent-value")),
      ("", Seq("x")),
      ("blank secrets are ignored", Seq("", "  ", null)),
      ("  padded  ", Seq("  padded  ")),
      ("k9x k9x", Seq("k9x")),
      ("multi\nline\tsecret-a\r\nsecret-b", Seq("secret-a", "secret-b", "secret-a")),
      ("unicode é-secret-ü and %C3%A9-secret-%C3%BC", Seq("é-secret-ü"))
    )
    cases.foreach { case (body, secrets) =>
      withClue(s"body=$body secrets=$secrets: ") {
        Redaction.scrub(body, secrets) shouldBe referenceScrub(body, secrets)
      }
    }
  }

  it should "match the previous implementation on random bodies whose secrets do not overlap" in {
    val rnd = new Random(1357L)
    // No upper case and no brackets, so no secret can match inside the placeholder the old code had already written.
    val secretAlphabet                 = "abcdefghijklmnopqrstuvwxyz0123456789+/=\"-_.~é"
    val fillerAlphabet                 = " {}:,\n\t!?<>()"
    def from(alphabet: String, n: Int) = Seq.fill(n)(alphabet(rnd.nextInt(alphabet.length))).mkString
    (1 to 500).foreach { _ =>
      val secrets = Seq.fill(1 + rnd.nextInt(6))(from(secretAlphabet, 1 + rnd.nextInt(30)))
      // Some secrets inside others, as a token contains its own prefix.
      val nested = secrets.filter(_.length > 4).take(1).map(s => s.substring(1, s.length - 1))
      val all    = secrets ++ nested
      val body = Seq
        .fill(rnd.nextInt(20)) {
          val filler = from(fillerAlphabet, 1 + rnd.nextInt(5))
          if (rnd.nextBoolean()) filler else filler + forms(all(rnd.nextInt(all.length)))(rnd.nextInt(3))
        }
        .mkString + from(fillerAlphabet, 1)
      withClue(s"body=$body secrets=$all: ") {
        Redaction.scrub(body, all) shouldBe referenceScrub(body, all)
      }
    }
  }

  it should "leave no secret behind when occurrences overlap, masking the run they cover once" in {
    Redaction.scrub("x abcdefghijkl y", Seq("abcdefgh", "efghijkl")) shouldBe s"x $P y"
    Redaction.scrub("x aaaaaaaaaa y", Seq("aaaa")) shouldBe s"x $P y"
    Redaction.scrub("abcd-wxyz", Seq("abcd", "wxyz")) shouldBe s"$P-$P"
    Redaction.scrub("abcdwxyz", Seq("abcd", "wxyz")) shouldBe s"$P$P"
    val rnd = new Random(42L)
    (1 to 500).foreach { _ =>
      val body    = Seq.fill(rnd.nextInt(60))("ab" (rnd.nextInt(2))).mkString
      val secrets = Seq.fill(1 + rnd.nextInt(3))(Seq.fill(1 + rnd.nextInt(5))("ab" (rnd.nextInt(2))).mkString)
      val out     = Redaction.scrub(body, secrets)
      secrets.foreach(s => withClue(s"body=$body secrets=$secrets out=$out: ")((out should not).include(s)))
    }
  }

  "Redaction.scrubRemote" should "scrub a body echoing 8,000 access tokens in linear time" in {
    val tokens = (1 to 8000).map(i => f"tok-$i%06d-${i * 7919}%08d")
    val body   = tokens.map(t => s"""{"access_token":"$t","token_type":"Bearer"}""").mkString("[", ",", "]")
    body.length should be > 350000
    val started   = System.nanoTime()
    val out       = Redaction.scrubRemote(body, Seq("configured-secret"))
    val elapsedMs = (System.nanoTime() - started) / 1000000
    tokens.foreach(t => (out should not).include(t))
    out should include(s""""access_token":"$P"""")
    info(s"scrubbed ${body.length} chars in $elapsedMs ms")
    elapsedMs should be < 3000L
  }
}
