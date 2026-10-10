package org.llm4s.runner

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.text.Normalizer
import java.util.Locale
import scala.io.{ Codec, Source }
import scala.util.Using

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

  private def str(codePoints: Int*): String = new String(codePoints.toArray, 0, codePoints.size)

  private def nfd(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD)

  private val Ypogegrammeni = 0x0345

  /** The marks a name may add after a letter: Combining Diacritical Marks, their Supplement, and those for Symbols. */
  private val combiningMarks: Seq[Int] =
    (Seq(0x0300 to 0x036f, 0x1dc0 to 0x1dff, 0x20d0 to 0x20ff).flatten: Seq[Int]).filter(Character.isDefined)

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
    folded("\u1FB3") should not be folded("\u03B1")                         // ᾳ, α
    folded("\u03B1\u0303\u03B9") should not be folded("\u03B1\u03B9\u0303") // the tilde on the alpha, or on the iota
  }

  it should "give one key to a Greek letter with an iota subscript and a further mark, however it is spelt" in {
    // NFKC composes U+0345 into its letter, and Java upper-cases `ᾳ` to `ΑΙ`, putting the iota before any mark that
    // follows; APFS decomposes first, which moves the subscript (combining class 240) after the mark, then case-folds
    // it to `ι`. So `ᾼ͂` and `ᾷ`, and `ᾳ̃` and `α̃ι`, are one name on macOS and folded apart: `cp -P a/b/zᾼ͂ c/zᾷ d`
    // wrote `c/zᾷ` through the link it had just made (#1775 review).
    folded("q\u1FBC\u0342") shouldBe folded("q\u1FB7")
    folded("q\u1FB3\u0303") shouldBe folded("q\u03B1\u0303\u03B9")
    // Each family: alpha, eta, omega with the subscript, lower and upper case, and each mark the brute force found
    val withSubscript = Seq(
      (0x1fb3, 0x03b1, 0x03b9), // ᾳ = α + ι
      (0x1fbc, 0x0391, 0x0399), // ᾼ = Α + Ι
      (0x1fc3, 0x03b7, 0x03b9), // ῃ
      (0x1fcc, 0x0397, 0x0399), // ῌ
      (0x1ff3, 0x03c9, 0x03b9), // ῳ
      (0x1ffc, 0x03a9, 0x0399)  // ῼ
    )
    val marks = Seq(0x0300, 0x0301, 0x0303, 0x0307, 0x0308, 0x030c, 0x0313, 0x0314, 0x0342)
    for {
      (letter, base, iota) <- withSubscript
      mark                 <- marks
    } {
      val key = folded(str(0x71, letter, mark))
      Seq(str(0x71, base, mark, iota), str(0x71, base, Ypogegrammeni, mark), str(0x71, base, mark, Ypogegrammeni))
        .foreach(other => withClue(s"${hex(str(letter, mark))} vs ${hex(other)}: ")(folded(other) shouldBe key))
    }
  }

  it should "give one key to every pair of names APFS took for one that folding with NFKC kept apart" in {
    // The 105 pairs a brute force over 7.7 million names created on APFS found (#1775 review)
    val pairs = Using.resource(Source.fromResource("apfs-iota-subscript-pairs.txt")(Codec.UTF8)) { source =>
      source
        .getLines()
        .map(_.trim)
        .filter(line => line.nonEmpty && !line.startsWith("#"))
        .map { line =>
          val names =
            line.split('\t').toSeq.map(name => str((0x71 +: name.trim.split(' ').toSeq.map(Integer.parseInt(_, 16)))*))
          (names should have).length(2)
          (names(0), names(1))
        }
        .toList
    }
    pairs should have size 105
    pairs.foreach { case (a, b) => withClue(s"${hex(a)} vs ${hex(b)}: ")(folded(a) shouldBe folded(b)) }
  }

  it should "give a letter with an iota subscript and any one combining mark the key of its decomposed spellings" in {
    // Every code point whose canonical decomposition holds U+0345 (`ᾳ`, `ᾷ`, `ᾄ`, ... `ῼ`), and each base letter with
    // U+0345 spelt out, followed by each mark; NFD reorders the subscript after a mark of lower combining class, and
    // APFS then folds it to `ι`, so the name written with `ι` or `Ι` in its place is the same name.
    val letters =
      Iterator
        .range(0, Character.MAX_CODE_POINT + 1)
        .filter(c => Character.isDefined(c) && Character.getType(c) != Character.SURROGATE)
        .map(c => str(c))
        .filter(s => s != str(Ypogegrammeni) && nfd(s).contains(str(Ypogegrammeni)))
        .toList ++ Seq(0x03b1, 0x0391, 0x03b7, 0x0397, 0x03c9, 0x03a9).map(str(_, Ypogegrammeni))
    letters.size should be > 60
    val problems = (for {
      letter <- letters.iterator
      mark   <- combiningMarks.iterator
      name = s"q$letter${str(mark)}"
      key  = folded(name)
      spelling <- Iterator(
        nfd(name),
        nfd(name).replace(str(Ypogegrammeni), "\u03B9"),
        nfd(name).replace(str(Ypogegrammeni), "\u0399")
      )
      if CommandPolicy.folded(spelling) != Some(key)
    } yield s"${hex(name)} -> ${hex(key)}, but ${hex(spelling)} -> ${CommandPolicy.folded(spelling).map(hex)}")
      .take(20)
      .toList
    problems shouldBe empty
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
