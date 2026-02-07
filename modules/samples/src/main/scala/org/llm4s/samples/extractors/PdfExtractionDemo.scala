package org.llm4s.samples.extractors

import org.llm4s.llmconnect.extractors.UniversalExtractor
import java.io.File

/**
 * Demo utility to manually test UniversalExtractor.
 *
 * HOW TO USE:
 * 1. Place a text-based PDF at: modules/samples/docs/sample.pdf
 *    (Scanned PDFs are NOT supported – OCR is not implemented.)
 *
 * 2. Run from project root:
 *    sbt "samples/runMain org.llm4s.samples.extractors.PdfExtractionDemo"
 *
 * 3. To test another file, pass the path as an argument:
 *    sbt "samples/runMain org.llm4s.samples.extractors.PdfExtractionDemo docs/another.pdf"
 *
 * NOTE:
 * - The extractor returns the FULL text.
 * - Only the console preview is limited for readability.
 */
object PdfExtractionDemo {

  def main(args: Array[String]): Unit = {

    // Default file path used when no argument is provided
    val defaultRelativePath = "docs/sample.pdf"

    // Read optional CLI argument (file path)
    val rawPath = Option(args).flatMap(_.headOption).getOrElse(defaultRelativePath)

    // Resolve relative paths against current working directory
    val file = new File(rawPath)
    val path =
      if (file.isAbsolute) file.getPath
      else new File(System.getProperty("user.dir"), rawPath).getPath

    // Run UniversalExtractor on the given file
    UniversalExtractor.extract(path) match {

      case Left(err) =>
        // Extraction failed (file not found, unsupported type, etc.)
        println(
          s"Extraction failed: type=${err.`type`}, message=${err.message}, path=${err.path.getOrElse(path)}"
        )

      case Right(text) =>
        // Preview limit is ONLY for console output
        val previewChars = 4000
        val preview      = text.take(previewChars)

        println(s"Input file       : $path")
        println(s"Extracted chars  : ${text.length}")
        println(s"Extracted lines  : ${text.linesIterator.length}")
        println("=" * 80)

        // Print first N characters for visual inspection
        println(preview)

        // Indicate that output is truncated only for display
        if (text.length > previewChars) {
          println("=" * 80)
          println(s"[Preview truncated: showing first $previewChars characters only]")
        }
    }
  }
}
