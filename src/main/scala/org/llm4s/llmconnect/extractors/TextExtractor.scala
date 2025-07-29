package org.llm4s.llmconnect.extractors

import scala.io.Source

object TextExtractor {
  def extractText(path: String): String = {
    val src = Source.fromFile(path)
    try src.getLines().mkString("\n")
    finally src.close()
  }
}
