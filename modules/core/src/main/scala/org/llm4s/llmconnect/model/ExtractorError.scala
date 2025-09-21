package org.llm4s.llmconnect.model

/**
 * ExtractorError represents failures during file/media extraction.
 *
 * @param message Human-readable explanation of the failure.
 * @param type    Coarse category (e.g., "FileNotFound", "UnsupportedType", "PDF",
 *                "DOCX", "PlainText", "ImageReadError", "AudioUnsupported",
 *                "AudioError", "VideoUnsupported").
 * @param path    Optional path to the problematic file for debugging.
 */
final case class ExtractorError(
  message: String,
  `type`: String,
  path: Option[String] = None
)
