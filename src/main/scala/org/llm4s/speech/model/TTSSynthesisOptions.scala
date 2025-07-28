package org.llm4s.speech.model

/**
 * Options for text-to-speech synthesis
 */
case class TTSSynthesisOptions(
  voice: String = "alloy",
  model: String = "tts-1",
  responseFormat: String = "mp3",
  speed: Double = 1.0,
  temperature: Double = 1.0
)

/**
 * Response from text-to-speech synthesis
 */
case class AudioResponse(
  audioData: Array[Byte],
  format: String,
  duration: Option[Double] = None,
  wordCount: Option[Int] = None
)
