package org.llm4s.llmconnect.extractors

import scala.io.Source

/**
 * TextExtractor reads plain .txt files and returns the content as a single string.
 */
object TextExtractor {

  /**
   * Reads the entire .txt file and returns its contents as plain text.
   *
   * @param path Absolute or relative path to a .txt file
   * @return File content as a single string
   * @throws Exception if the file cannot be read
   */
  def extractText(path: String): String = {
    val src = Source.fromFile(path, "UTF-8")
    try
      src.getLines().mkString("\n")
    finally
      src.close()
  }
}
