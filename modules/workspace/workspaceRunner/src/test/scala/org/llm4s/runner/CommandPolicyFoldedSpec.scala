package org.llm4s.runner

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.text.Normalizer
import java.util.Locale

/**
 * `CommandPolicy.folded` decides whether two names a `cp` or `mv` writes are one name on a case- and
 * normalisation-insensitive file system (macOS APFS, Windows NTFS). Two names that fold apart there let a
 * link-preserving `cp` write through a link it has just copied (#1775 review), so the folding must be a fixpoint:
 * folding a name again, or folding any case or normalisation variant of it, must give the same key.
 */
class CommandPolicyFoldedSpec extends AnyFlatSpec with Matchers {

  private def folded(s: String): String =
    withClue(s"${hex(s)} does not settle: ")(CommandPolicy.folded(s).getOrElse(fail("not folded")))

  private def hex(s: String): String = s.codePoints().toArray.map(c => f"U+$c%04X").mkString(" ")

  private def variants(s: String): Seq[String] = {
    val forms = Seq(
      s.toUpperCase(Locale.ROOT),
      s.toLowerCase(Locale.ROOT),
      s.toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT),
      s.toLowerCase(Locale.ROOT).toUpperCase(Locale.ROOT),
      Normalizer.normalize(s, Normalizer.Form.NFC),
      Normalizer.normalize(s, Normalizer.Form.NFD),
      Normalizer.normalize(s, Normalizer.Form.NFKC),
      Normalizer.normalize(s, Normalizer.Form.NFKD)
    )
    (forms ++ forms.map(Normalizer.normalize(_, Normalizer.Form.NFD))).distinct
  }

  "CommandPolicy.folded" should "give one key to the names APFS takes for one that a single pass kept apart" in {
    // Found by creating a file per code point on APFS and looking each variant up (#1775 review)
    val same = Seq(
      "\u1E9E"    -> "\u00DF",
      "\u1E9E"    -> "ss",
      "\u1E9E"    -> "SS",
      "\u0390"    -> "\u0399\u0308\u0301",
      "\u03B0"    -> "\u03A5\u0308\u0301",
      "\u1FD2"    -> "\u0399\u0308\u0300",
      "\u1FD3"    -> "\u0399\u0308\u0301",
      "\u1FD7"    -> "\u0399\u0308\u0342",
      "\u1FE2"    -> "\u03A5\u0308\u0300",
      "\u1FE3"    -> "\u03A5\u0308\u0301",
      "\u1FE7"    -> "\u03A5\u0308\u0342",
      "x"         -> "X",
      "caf\u00E9" -> "cafe\u0301"
    )
    same.foreach { case (a, b) =>
      withClue(s"${hex(a)} vs ${hex(b)}: ")(folded(s"q$a") shouldBe folded(s"q$b"))
    }
  }

  it should "keep distinct names apart" in {
    folded("x") should not be folded("y")
    folded("\u00DF") should not be folded("s")
    folded("\u0390") should not be folded("\u0399")
  }

  it should "settle, be idempotent and give each code point's case and normalisation variants the same key" in {
    val problems = Iterator
      .range(0, Character.MAX_CODE_POINT + 1)
      .filter(c => Character.isDefined(c) && Character.getType(c) != Character.SURROGATE)
      .flatMap { c =>
        val s = new String(Character.toChars(c))
        CommandPolicy.folded(s) match {
          case None => Iterator.single(s"${hex(s)} does not settle")
          case Some(key) =>
            (key +: variants(s)).iterator.flatMap { v =>
              val again = CommandPolicy.folded(v)
              Option.when(!again.contains(key))(s"${hex(s)} -> ${hex(key)}, but ${hex(v)} -> ${again.map(hex)}")
            }
        }
      }
      .take(20)
      .toList
    problems shouldBe empty
  }
}
