package org.llm4s.chunking

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.Random

/**
 * `SentenceChunker` must keep every character of its input (#1718): sentence boundaries are found
 * without consuming the punctuation or the next sentence's first letter, and sentences within a
 * chunk are joined by the whitespace that separated them in the input.
 */
class SentenceChunkerFidelitySpec extends AnyFlatSpec with Matchers {

  private val chunker   = new SentenceChunker()
  private val noOverlap = ChunkingConfig(targetSize = 100, maxSize = 200, overlap = 0)

  private def sentences(text: String): Seq[String] = chunker.splitIntoSentences(text).map(_.text)

  private def nonWhitespace(s: String): String = s.filterNot(_.isWhitespace)

  /** The input rebuilt from the split: each sentence after the whitespace that preceded it. */
  private def rebuilt(text: String): String =
    chunker.splitIntoSentences(text).map(s => s.separator + s.text).mkString

  private val words = Seq(
    "alpha",
    "river",
    "stone",
    "quiet",
    "maple",
    "orbit",
    "lantern",
    "copper",
    "meadow",
    "signal",
    "harbor",
    "violet"
  )
  private val terminators = Seq(".", "!", "?")
  private val separators  = Seq(" ", "  ", "\n", "\n\n", " \t ", "\r\n")
  private val inserts     = Seq("Dr. Smith", "e.g. this", "3.14", "Mr. Jones", "vs. that")

  /** A random text of `n` sentences; abbreviations and decimals inside never form a boundary. */
  private def randomText(rnd: Random, n: Int): String = {
    val out = (0 until n).map { _ =>
      val rest = (0 until rnd.nextInt(12)).map { _ =>
        if (rnd.nextInt(8) == 0) inserts(rnd.nextInt(inserts.size)) else words(rnd.nextInt(words.size))
      }
      (words(rnd.nextInt(words.size)).capitalize +: rest).mkString(" ") + terminators(rnd.nextInt(terminators.size))
    }
    out.zipWithIndex.map { case (s, i) => if (i == 0) s else separators(rnd.nextInt(separators.size)) + s }.mkString
  }

  "SentenceChunker" should "keep the punctuation and the next sentence's first letter (#1718)" in {
    val text = "Hello world. Next one. Third."
    chunker.chunk(text, noOverlap).map(_.content) shouldBe Seq(text)
    sentences(text) shouldBe Seq("Hello world.", "Next one.", "Third.")
  }

  it should "split after '.', '!' and '?'" in {
    sentences("It works. Does it? Yes! Good.") shouldBe Seq("It works.", "Does it?", "Yes!", "Good.")
  }

  it should "keep runs of spaces and newlines between sentences as the separator" in {
    val text = "First one.   Second one.\nThird one.\n\n\tFourth one."
    sentences(text) shouldBe Seq("First one.", "Second one.", "Third one.", "Fourth one.")
    rebuilt(text) shouldBe text
    chunker.chunk(text, noOverlap).map(_.content) shouldBe Seq(text)
  }

  it should "put sentences of separate chunks in separate chunks, each a slice of the input" in {
    val text   = "Alpha beta gamma.\n\nDelta epsilon zeta. Eta theta iota."
    val chunks = chunker.chunk(text, ChunkingConfig(targetSize = 20, maxSize = 40, minChunkSize = 5, overlap = 0))
    chunks.map(_.content) shouldBe Seq("Alpha beta gamma.", "Delta epsilon zeta.", "Eta theta iota.")
  }

  it should "not split after abbreviations" in {
    sentences("Dr. Smith met Mr. Jones. They talked, e.g. About work.") shouldBe
      Seq("Dr. Smith met Mr. Jones.", "They talked, e.g. About work.")
  }

  it should "treat an abbreviation only as a whole word" in {
    // Without a word boundary `(?i)(mr)\.` matched the end of `summr.`, and `st.` the end of `first.`
    sentences("It was summr. Then it rained.") shouldBe Seq("It was summr.", "Then it rained.")
    sentences("We came first. Then we left.") shouldBe Seq("We came first.", "Then we left.")
  }

  it should "not split inside decimal numbers" in {
    sentences("Pi is about 3.14 here. Next sentence.") shouldBe Seq("Pi is about 3.14 here.", "Next sentence.")
  }

  it should "return text with no boundary as one sentence" in {
    sentences("no boundary here at all") shouldBe Seq("no boundary here at all")
    sentences("Ends without punctuation") shouldBe Seq("Ends without punctuation")
  }

  it should "not split when a lowercase letter follows the period" in {
    sentences("The file ends in .txt. not here. But Here.") shouldBe
      Seq("The file ends in .txt. not here.", "But Here.")
  }

  it should "keep closing quotes and brackets with the sentence they close" in {
    sentences("He said \"Hi.\" Then he left.") shouldBe Seq("He said \"Hi.\"", "Then he left.")
    sentences("It ended (finally.) Next one.") shouldBe Seq("It ended (finally.)", "Next one.")
    sentences("She asked. \"Why?\" He shrugged.") shouldBe Seq("She asked.", "\"Why?\"", "He shrugged.")
  }

  it should "drop leading and trailing whitespace of the whole text only" in {
    sentences("  \n Hello there. Bye now.  \n") shouldBe Seq("Hello there.", "Bye now.")
    sentences("   \n\t ") shouldBe empty
    chunker.chunk("   \n\t ", noOverlap) shouldBe empty
  }

  it should "keep a NUL character of the input as it is" in {
    sentences("A\u0000b. Next.") shouldBe Seq("A\u0000b.", "Next.")
  }

  it should "keep every non-whitespace character in order across chunks (seeded property)" in {
    val rnd = new Random(1718L)
    (1 to 300).foreach { _ =>
      val n      = 1 + rnd.nextInt(25)
      val text   = randomText(rnd, n)
      val target = 20 + rnd.nextInt(300)
      val config = ChunkingConfig(
        targetSize = target,
        maxSize = target + rnd.nextInt(200),
        minChunkSize = rnd.nextInt(target),
        overlap = 0
      )
      val chunks = chunker.chunk(text, config)

      withClue(s"text=${text.take(200)} config=$config") {
        nonWhitespace(chunks.map(_.content).mkString) shouldBe nonWhitespace(text)
        sentences(text) should have size n.toLong
        rebuilt(text) shouldBe text
      }
    }
  }

  it should "keep every chunk an exact slice of the input when no sentence is force-split" in {
    val rnd = new Random(42L)
    (1 to 200).foreach { _ =>
      val text   = randomText(rnd, 1 + rnd.nextInt(20))
      val chunks = chunker.chunk(text, ChunkingConfig(targetSize = 400, maxSize = 2000, overlap = 0))
      chunks.foreach(c => text should include(c.content))
    }
  }
}
