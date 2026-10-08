package org.llm4s.chunking

import org.scalacheck.Gen
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

/**
 * #1318, item 4: a chunker never sees an invalid configuration, because [[ChunkingConfig]] cannot be built with one
 * (`overlap >= targetSize` and the like throw at construction). So the other half of the guarantee is that no chunker
 * throws for any configuration that CAN be built, whatever the text.
 *
 * Only the chunkers that need no embedding client: `semantic` has its own spec.
 */
class ChunkersAcceptValidConfigsSpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks {

  implicit override val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(minSuccessful = 150)

  private val chunkers: Seq[(String, DocumentChunker)] = Seq(
    "simple"   -> ChunkerFactory.simple(),
    "sentence" -> ChunkerFactory.sentence(),
    "markdown" -> ChunkerFactory.create(ChunkerFactory.Strategy.Markdown)
  )

  /** Every combination `ChunkingConfig` accepts, including its boundaries (`overlap = targetSize - 1`, `minChunkSize = 0`). */
  private val genConfig: Gen[ChunkingConfig] =
    for {
      target    <- Gen.choose(1, 200)
      extra     <- Gen.choose(0, 200)
      overlap   <- Gen.frequency(1 -> Gen.const(target - 1), 1 -> Gen.const(0), 4 -> Gen.choose(0, target - 1))
      minChunk  <- Gen.choose(0, 300)
      codeBlock <- Gen.oneOf(true, false)
      headings  <- Gen.oneOf(true, false)
    } yield ChunkingConfig(
      targetSize = target,
      maxSize = target + extra,
      overlap = overlap,
      minChunkSize = minChunk,
      preserveCodeBlocks = codeBlock,
      preserveHeadings = headings
    )

  /** Prose, markdown-ish lines, code fences, blank lines, very long unbroken words, and the empty string. */
  private val genText: Gen[String] = {
    val piece: Gen[String] = Gen.frequency(
      6 -> Gen.listOfN(8, Gen.alphaChar).map(_.mkString + " "),
      2 -> Gen.const(". "),
      2 -> Gen.const("\n\n"),
      1 -> Gen.const("# Heading\n"),
      1 -> Gen.const("```scala\nval x = 1\n```\n"),
      1 -> Gen.listOfN(300, Gen.alphaChar).map(_.mkString)
    )
    Gen.choose(0, 60).flatMap(n => Gen.listOfN(n, piece)).map(_.mkString)
  }

  chunkers.foreach { case (name, chunker) =>
    s"the $name chunker" should "not throw for any valid ChunkingConfig, and number its chunks from 0 without gaps" in {
      forAll(genText, genConfig) { (text, config) =>
        val chunks = chunker.chunk(text, config)
        // Re-ingesting a document replaces its chunks by their ids (`<docId>-chunk-<index>`), so an index that
        // skipped or repeated would leave a stale chunk behind (#1318, item 1).
        chunks.map(_.index) shouldBe chunks.indices.toList
      }
    }
  }

  "SimpleChunker" should "handle integer-boundary window sizes without overflow" in {
    val config = ChunkingConfig(targetSize = Int.MaxValue, maxSize = Int.MaxValue, overlap = Int.MaxValue - 1)
    val chunks = SimpleChunker().chunk("abc", config)
    chunks.map(_.index) shouldBe chunks.indices.toList
    chunks.map(_.content) shouldBe List("abc", "bc", "c")
  }

  "ChunkingConfig" should "be impossible to build in a state a chunker could not handle" in {
    an[IllegalArgumentException] should be thrownBy ChunkingConfig(targetSize = 100, overlap = 100)
    an[IllegalArgumentException] should be thrownBy ChunkingConfig(targetSize = 0)
    // The boundary that is allowed: one character of progress per chunk.
    chunkers.foreach { case (_, chunker) =>
      noException should be thrownBy chunker.chunk(
        "a b c d e f g h i j " * 20,
        ChunkingConfig(targetSize = 10, overlap = 9)
      )
    }
  }
}
