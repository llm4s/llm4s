package org.llm4s.samples.rag

import org.llm4s.chunking.{ ChunkerFactory, ChunkingConfig, DocumentChunk, DocumentChunker }
import org.llm4s.llmconnect.utils.ChunkingUtils

import scala.util.{ Failure, Success, Try }

/**
 * Chunking Strategies Example
 *
 * Runs the three chunkers that need nothing but text - `simple`, `sentence` and `markdown` - over one fixed
 * Markdown document and prints, for each, the number of chunks, every chunk's length and its first 40
 * characters. It then shows how `targetSize` and `overlap` change the result, and what happens to a
 * `ChunkingConfig` the chunkers cannot use.
 *
 * `SemanticChunker` is left out: it needs an embedding client. The sample reads no configuration, so it
 * needs no API key and no running service, and its output is the same on every run.
 *
 * Run this example:
 * {{{
 * sbt "samples/runMain org.llm4s.samples.rag.ChunkingStrategiesExample"
 * }}}
 */
object ChunkingStrategiesExample {

  private val text: String =
    """# Chunking guide
      |
      |Chunking splits a document into pieces small enough to embed. Each piece should carry one idea.
      |A chunk that is too large blurs several topics together. A chunk that is too small loses its context.
      |
      |## Choosing a size
      |
      |Start with a target of a few hundred characters. Measure retrieval quality, then adjust the size.
      |Overlap repeats the end of one chunk at the start of the next, so a sentence cut at a boundary survives.
      |
      |```scala
      |val chunker = ChunkerFactory.markdown()
      |val chunks  = chunker.chunk(text, ChunkingConfig(targetSize = 400, overlap = 50))
      |```
      |
      |## Things to remember
      |
      |- Smaller chunks give precise matches.
      |- Larger chunks give more context to the model.
      |- Overlap costs extra storage.
      |""".stripMargin

  def main(args: Array[String]): Unit = {
    println(s"Input: ${text.length} characters")
    println()

    val defaultConfig = ChunkingConfig.default
    println(s"=== Strategies with the default config (${describe(defaultConfig)}) ===")
    val strategies: Seq[(String, DocumentChunker)] = Seq(
      "simple"   -> ChunkerFactory.simple(),
      "sentence" -> ChunkerFactory.sentence(),
      "markdown" -> ChunkerFactory.markdown()
    )
    strategies.foreach { case (name, chunker) =>
      printChunks(name, chunker.chunk(text, defaultConfig))
    }

    println("=== Smaller targetSize, with and without overlap (sentence chunker) ===")
    val sentence = ChunkerFactory.sentence()
    val configs = Seq(
      "targetSize=300 overlap=0 " -> ChunkingConfig(targetSize = 300, maxSize = 450, overlap = 0, minChunkSize = 30),
      "targetSize=300 overlap=60" -> ChunkingConfig(targetSize = 300, maxSize = 450, overlap = 60, minChunkSize = 30)
    )
    configs.foreach { case (label, config) =>
      val chunks = sentence.chunk(text, config)
      println(s"$label -> ${chunks.size} chunks, total ${chunks.map(_.length).sum} characters")
    }
    println("Overlap repeats text at chunk boundaries, so the overlapping run holds more characters in total.")
    println()

    println("=== A ChunkingConfig the chunkers cannot use ===")
    Try(ChunkingConfig(targetSize = 100, maxSize = 200, overlap = 100)) match {
      case Success(config) => println(s"Accepted: ${describe(config)}")
      case Failure(error) =>
        println(s"ChunkingConfig rejects it with ${error.getClass.getSimpleName}: ${error.getMessage}")
    }
    println("Plain sizes can be checked as a Result instead of an exception:")
    println(s"  chunkTextValidated(text, 100, 100) -> ${summary(ChunkingUtils.chunkTextValidated(text, 100, 100))}")
    println(s"  chunkTextValidated(text, 100, 20)  -> ${summary(ChunkingUtils.chunkTextValidated(text, 100, 20))}")
  }

  private def describe(config: ChunkingConfig): String =
    s"targetSize=${config.targetSize}, maxSize=${config.maxSize}, overlap=${config.overlap}, " +
      s"minChunkSize=${config.minChunkSize}"

  private def printChunks(name: String, chunks: Seq[DocumentChunk]): Unit = {
    println(s"[$name] ${chunks.size} chunks")
    chunks.foreach { chunk =>
      val preview = chunk.content.take(40).replace('\n', ' ')
      println(f"  #${chunk.index}%-2d length=${chunk.length}%-4d $preview")
    }
    println()
  }

  private def summary(result: org.llm4s.types.Result[Seq[String]]): String =
    result.fold(error => s"Left(${error.message})", pieces => s"Right(${pieces.size} chunks)")
}
