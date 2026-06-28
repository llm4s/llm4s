package org.llm4s.llmconnect.model

/**
 * Represents the type of content that can be embedded or processed.
 *
 * Used by the embedding and extraction subsystems to select the appropriate
 * encoder or extractor for a given piece of content. Each modality maps to a
 * distinct processing pipeline (e.g. text embedding via an API provider,
 * image embedding via a local CLIP model).
 */
sealed trait Modality

/** Plain text content, the primary modality for LLM embedding providers. */
case object Text extends Modality

/** Image content (e.g. PNG, JPEG), processed via local image encoders. */
case object Image extends Modality

/** Audio content (e.g. WAV, MP3), processed via local audio encoders. */
case object Audio extends Modality

/** Video content (e.g. MP4), processed via local video encoders. */
case object Video extends Modality
