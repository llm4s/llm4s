package org.llm4s.chunking

import org.llm4s.llmconnect.EmbeddingClient
import org.llm4s.llmconnect.config.EmbeddingModelConfig
import org.llm4s.llmconnect.model.{ EmbeddingRequest, EmbeddingResponse }
import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.llm4s.llmconnect.utils.{ ChunkingUtils, CodePointBoundary }
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable.ListBuffer
import scala.util.Random

/**
 * #1711: no chunker cuts between the two halves of a UTF-16 surrogate pair, and text without astral characters is
 * chunked exactly as before.
 *
 * Every generator is driven by a fixed seed, so a failure reproduces; the seed and the input are in the clue.
 */
class SurrogatePairChunkingSpec extends AnyFlatSpec with Matchers {
  import SurrogatePairChunkingSpec._

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private val chunkers: Seq[(String, DocumentChunker)] = Seq(
    "simple"   -> SimpleChunker(),
    "sentence" -> SentenceChunker(),
    "markdown" -> MarkdownChunker(),
    "semantic" -> SemanticChunker(
      EmbeddingClient(new HashEmbeddingProvider),
      EmbeddingModelConfig("hash-model", 4)
    )
  )

  // ---- the issue's example

  "SimpleChunker" should "keep an emoji whole at a chunk boundary (the #1711 example)" in {
    val chunks = SimpleChunker().chunk("ab\uD83D\uDE00cd", ChunkingConfig(targetSize = 3, maxSize = 3, overlap = 0))
    chunks.map(_.content) shouldBe Seq("ab", "\uD83D\uDE00c", "d")
    chunks.map(_.index) shouldBe Seq(0, 1, 2)
  }

  it should "keep an emoji whole at an overlap start" in {
    // Main started window 2 at index 3, between the halves of the emoji.
    val chunks = ChunkingUtils.chunkText("abc\uD83D\uDE00def", size = 5, overlap = 3)
    chunks.foreach(c => withClue(c)(wellFormed(c) shouldBe true))
    chunks.head shouldBe "abc\uD83D\uDE00"
  }

  // ---- differential: no astral characters, no change

  "ChunkingUtils.chunkText" should "chunk text without astral characters exactly as main did" in {
    forSeeds(5000) { (r, seed) =>
      val text    = bmpText(r, 300)
      val size    = 1 + r.nextInt(if (r.nextBoolean()) 8 else 120)
      val overlap = if (r.nextInt(3) == 0) size - 1 else r.nextInt(size)
      withClue(s"seed=$seed size=$size overlap=$overlap text=${text.length} units: ") {
        ChunkingUtils.chunkText(text, size, overlap) shouldBe mainChunkText(text, size, overlap)
      }
    }
  }

  "SimpleChunker" should "produce the same chunks as main for text without astral characters" in {
    forSeeds(2000) { (r, seed) =>
      val text = bmpText(r, 300)
      val cfg  = config(r)
      withClue(s"seed=$seed $cfg: ") {
        SimpleChunker().chunk(text, cfg).map(_.content) shouldBe
          (if (text.isEmpty) Seq.empty else mainChunkText(text, cfg.targetSize, cfg.overlap))
      }
    }
  }

  "CodePointBoundary" should "leave every index of text without astral characters where it is" in {
    forSeeds(2000) { (r, seed) =>
      val text = bmpText(r, 100)
      withClue(s"seed=$seed: ") {
        (0 to text.length).foreach { i =>
          CodePointBoundary.floor(text, i) shouldBe i
          CodePointBoundary.ceil(text, i) shouldBe i
          CodePointBoundary.splitsPair(text, i) shouldBe false
        }
        (-1 to text.length + 1).foreach(n => CodePointBoundary.take(text, n) shouldBe text.take(n))
      }
    }
  }

  // ---- properties over text with astral characters

  "ChunkingUtils.chunkText" should "never start or end a chunk with a lone surrogate" in {
    forSeeds(3000) { (r, seed) =>
      val text    = astralText(r, 120)
      val size    = 1 + r.nextInt(if (r.nextBoolean()) 4 else 40)
      val overlap = if (r.nextInt(3) == 0) size - 1 else r.nextInt(size)
      val chunks  = ChunkingUtils.chunkText(text, size, overlap)
      withClue(s"seed=$seed size=$size overlap=$overlap: ") {
        chunks.foreach { c =>
          c should not be empty
          startsOrEndsLone(c) shouldBe false
          wellFormed(c) shouldBe true
        }
        if (text.nonEmpty) {
          text should startWith(chunks.head)
          text should endWith(chunks.last)
        }
      }
    }
  }

  it should "concatenate back to the input with overlap 0" in {
    forSeeds(3000) { (r, seed) =>
      val text = astralText(r, 120)
      val size = 1 + r.nextInt(if (r.nextBoolean()) 4 else 40)
      withClue(s"seed=$seed size=$size: ") {
        ChunkingUtils.chunkText(text, size, 0).mkString shouldBe text
      }
    }
  }

  it should "keep every chunk within size, except a lone astral character at size 1" in {
    forSeeds(3000) { (r, seed) =>
      val text    = astralText(r, 120)
      val size    = 1 + r.nextInt(if (r.nextBoolean()) 4 else 40)
      val overlap = r.nextInt(size)
      withClue(s"seed=$seed size=$size overlap=$overlap: ") {
        ChunkingUtils.chunkText(text, size, overlap).foreach { c =>
          if (c.length > size) {
            // Moving back would have left the chunk empty, so it is the whole character.
            size shouldBe 1
            c.codePointCount(0, c.length) shouldBe 1
          }
        }
      }
    }
  }

  it should "still accept the largest window ChunkingConfig allows" in {
    val text = "a\uD83D\uDE00b"
    ChunkingUtils.chunkText(text, Int.MaxValue, Int.MaxValue - 1).foreach(c => wellFormed(c) shouldBe true)
    ChunkingUtils.chunkText(text, Int.MaxValue, 0) shouldBe Seq(text)
  }

  "CodePointBoundary.take" should "never end on the high half of a pair, nor exceed n" in {
    forSeeds(2000) { (r, seed) =>
      val text = astralText(r, 60)
      withClue(s"seed=$seed: ") {
        (0 to text.length + 1).foreach { n =>
          val taken = CodePointBoundary.take(text, n)
          text should startWith(taken)
          taken.length should be <= n
          taken.length should be >= (math.min(n, text.length) - 1)
          startsOrEndsLone(taken) shouldBe false
        }
      }
    }
  }

  // ---- every chunker, text with astral characters

  chunkers.foreach { case (name, chunker) =>
    s"the $name chunker" should "never produce a chunk containing a lone surrogate" in {
      forSeeds(1500) { (r, seed) =>
        val text = astralText(r, 150)
        val cfg  = config(r)
        withClue(s"seed=$seed $cfg: ") {
          chunker.chunk(text, cfg).foreach(c => withClue(c.content)(wellFormed(c.content) shouldBe true))
        }
      }
    }
  }

  "SentenceChunker" should "not start an overlap on the low half of a pair" in {
    // A sentence longer than maxSize is force-split at whitespace, and each later piece gets the end of the previous
    // one as overlap, taken from its last (overlap + 50) units. Here that window began on the low half of the emoji
    // (index 7 of the first piece's 62), and main prepended a lone U+DE00 to the second chunk.
    val first  = "Start \uD83D\uDE00 " + "y" * 53
    val second = "Next sentence here"
    val chunks = SentenceChunker().chunk(
      first + " " + second,
      ChunkingConfig(targetSize = 62, maxSize = 62, overlap = 5, minChunkSize = 0)
    )
    chunks.map(_.content) shouldBe Seq(first, "\uD83D\uDE00 " + second)
  }

  it should "never start an overlap inside a pair when it force-splits long runs of astral text" in {
    // The overlap path needs a force-split piece longer than overlap + 50, which the general property above reaches
    // only now and then: here every text is one long "sentence" of short astral words.
    forSeeds(1500) { (r, seed) =>
      val words  = Seq.fill(20 + r.nextInt(80))(Seq.fill(1 + r.nextInt(4))(astralChar(r)).mkString)
      val text   = words.mkString(" ")
      val target = 1 + r.nextInt(120)
      val cfg = ChunkingConfig(
        targetSize = target,
        maxSize = target + r.nextInt(150),
        overlap = r.nextInt(target),
        minChunkSize = 0
      )
      withClue(s"seed=$seed $cfg: ") {
        SentenceChunker().chunk(text, cfg).foreach(c => withClue(c.content)(wellFormed(c.content) shouldBe true))
      }
    }
  }

  it should "keep a single word longer than maxSize whole, cutting no surrogate pair (documented)" in {
    // #1711 asked whether this is intended: it is. The chunker never cuts inside a word, so such a chunk exceeds
    // maxSize; SimpleChunker is the one that cuts at a fixed size.
    val word   = "xxxxx" + "\uD83D\uDE00" * 5
    val chunks = SentenceChunker().chunk(word, ChunkingConfig(targetSize = 6, maxSize = 6, overlap = 0))
    chunks.map(_.content) shouldBe Seq(word)
    chunks.head.content.length shouldBe 15
    wellFormed(chunks.head.content) shouldBe true
  }
}

object SurrogatePairChunkingSpec {

  // ---- the implementation on main before #1711, kept verbatim as the oracle for the differential test

  def mainChunkText(text: String, size: Int, overlap: Int): Seq[String] = {
    require(size > 0, "Chunk size must be greater than 0")
    require(overlap >= 0 && overlap < size, "Overlap must be non-negative and less than chunk size")

    val chunks = ListBuffer[String]()
    var start  = 0

    while (start < text.length) {
      val end = math.min(start + size, text.length)
      chunks += text.substring(start, end)
      start = start + size - overlap
    }

    chunks.toSeq
  }

  // ---- generators

  val Emoji     = 0x1f600 to 0x1f64f
  val CjkExtB   = 0x20000 to 0x2a6df
  val CjkBmp    = 0x4e00 to 0x9fff
  val Surrogate = 0xd800 to 0xdfff

  def pick(r: Random, range: Range): Int = range.start + r.nextInt(range.length)

  /** A BMP code point that is not a surrogate: what "text without astral characters" consists of. */
  def bmpChar(r: Random): String =
    r.nextInt(10) match {
      case 0 | 1 | 2 => ('a' + r.nextInt(26)).toChar.toString
      case 3         => " "
      case 4         => ". "
      case 5         => "\n"
      case 6         => new String(Character.toChars(pick(r, CjkBmp)))
      case _ =>
        var cp = r.nextInt(0x10000)
        while (Surrogate.contains(cp)) cp = r.nextInt(0x10000)
        new String(Character.toChars(cp))
    }

  def astralChar(r: Random): String =
    new String(Character.toChars(if (r.nextBoolean()) pick(r, Emoji) else pick(r, CjkExtB)))

  def bmpText(r: Random, maxLen: Int): String =
    Seq.fill(r.nextInt(maxLen + 1))(bmpChar(r)).mkString

  /** Mostly astral, with spaces, sentence ends and words, so every chunker's splitting paths are reached. */
  def astralText(r: Random, maxLen: Int): String =
    Seq
      .fill(r.nextInt(maxLen + 1)) {
        r.nextInt(12) match {
          case 0 => " "
          case 1 => ". " + ('A' + r.nextInt(26)).toChar
          case 2 => "\n\n"
          case 3 => "# Heading " + astralChar(r) + "\n"
          case 4 => ('a' + r.nextInt(26)).toChar.toString
          case _ => astralChar(r)
        }
      }
      .mkString

  /** A valid `ChunkingConfig`, including the boundaries: size 1, overlap 0 and overlap `targetSize - 1`. */
  def config(r: Random): ChunkingConfig = {
    val target = 1 + r.nextInt(if (r.nextInt(4) == 0) 4 else 60)
    val overlap = r.nextInt(3) match {
      case 0 => 0
      case 1 => target - 1
      case _ => r.nextInt(target)
    }
    ChunkingConfig(
      targetSize = target,
      maxSize = target + r.nextInt(40),
      overlap = overlap,
      minChunkSize = r.nextInt(30)
    )
  }

  // ---- what a well-formed chunk is

  def isLoneSurrogateAt(s: String, i: Int): Boolean = {
    val c = s.charAt(i)
    if (Character.isHighSurrogate(c)) i + 1 >= s.length || !Character.isLowSurrogate(s.charAt(i + 1))
    else if (Character.isLowSurrogate(c)) i == 0 || !Character.isHighSurrogate(s.charAt(i - 1))
    else false
  }

  def wellFormed(s: String): Boolean = s.indices.forall(i => !isLoneSurrogateAt(s, i))

  def startsOrEndsLone(s: String): Boolean =
    s.nonEmpty && (Character.isLowSurrogate(s.head) || Character.isHighSurrogate(s.last))

  def forSeeds(n: Int)(body: (Random, Long) => Unit): Unit =
    (1L to n.toLong).foreach(seed => body(new Random(seed * 7919L), seed))

  class HashEmbeddingProvider extends EmbeddingProvider {
    override def embed(request: EmbeddingRequest): Result[EmbeddingResponse] =
      Right(EmbeddingResponse(embeddings = request.input.map { t =>
        val h = t.hashCode
        Seq.tabulate(4)(i => ((h >>> (i * 8)) & 0xff).toDouble + 1.0)
      }))
  }
}
