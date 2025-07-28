package org.llm4s.speech.model

/**
 * Options for automatic speech recognition
 */
case class ASRTranscriptionOptions(
  model: String = "whisper-1",
  language: Option[String] = None,
  prompt: Option[String] = None,
  responseFormat: String = "json",
  temperature: Double = 0.0,
  timestampGranularities: Seq[String] = Seq("word", "segment")
)

/**
 * Response from automatic speech recognition
 */
case class TranscriptionResponse(
  text: String,
  language: Option[String] = None,
  duration: Option[Double] = None,
  segments: Seq[TranscriptionSegment] = Seq.empty
)

/**
 * Represents a segment of transcribed audio
 */
case class TranscriptionSegment(
  id: Int,
  start: Double,
  end: Double,
  text: String,
  tokens: Seq[Int] = Seq.empty,
  temperature: Option[Double] = None,
  avgLogprob: Option[Double] = None,
  compressionRatio: Option[Double] = None,
  noSpeechProb: Option[Double] = None
)
