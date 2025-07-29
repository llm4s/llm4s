package org.llm4s.llmconnect.extractors

import org.jsoup.Jsoup

object WebExtractor {
  def extractText(url: String): String = {
    val doc = Jsoup.connect(url).get()
    doc.body().text()
  }
}
