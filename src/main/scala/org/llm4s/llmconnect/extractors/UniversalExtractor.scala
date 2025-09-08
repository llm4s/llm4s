package org.llm4s.llmconnect.extractors

import org.slf4j.LoggerFactory

object UniversalExtractor {
  private val logger = LoggerFactory.getLogger(getClass)

  def extract(pathOrUrl: String): ExtractResult = {
    if (isUrl(pathOrUrl)) {
      logger.debug(s"[UniversalExtractor] URL: $pathOrUrl → TextDataExtractor.fromUrl")
      return TextDataExtractor.fromUrl(pathOrUrl)
    }

    val ext = extension(pathOrUrl)
    logger.debug(s"[UniversalExtractor] File: $pathOrUrl (ext=$ext)")

    if (TextDataExtractor.supports(ext)) {
      logger.debug(s"[UniversalExtractor] → TextDataExtractor")
      TextDataExtractor.fromFile(pathOrUrl)
    } else {
      val msg = s"Unsupported text file type: .$ext ($pathOrUrl)"
      logger.error(msg)
      throw new RuntimeException(msg)
    }
  }

  def extractMany(pathsOrUrls: Seq[String]): Seq[ExtractResult] =
    pathsOrUrls.map(extract)

  private def isUrl(s: String): Boolean =
    s.startsWith("http://") || s.startsWith("https://")

  private def extension(path: String): String =
    path.split("\\.").lastOption.map(_.toLowerCase).getOrElse("")
}
