package org.llm4s.llmconnect.model

import org.llm4s.annotation.Stable

/**
 * Represents the type of content that can be embedded or processed.
 *
 * Used by the embedding and extraction subsystems to select the appropriate
 * encoder or extractor for a given piece of content. Each modality maps to a
 * distinct processing pipeline (e.g. text embedding via an API provider,
 * image embedding via a multimodal one).
 */
@Stable
enum Modality {

  /** Plain text content, the primary modality for LLM embedding providers. */
  case Text

  /** Image content (e.g. PNG, JPEG). */
  case Image

  /** Audio content (e.g. WAV, MP3). */
  case Audio

  /** Video content (e.g. MP4). */
  case Video
}
