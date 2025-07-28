package org.llm4s.speech

import org.llm4s.speech.model.{ AudioResponse, SpeechError, TTSSynthesisOptions }

/**
 * Client interface for text-to-speech synthesis
 */
trait TTSClient {

  /**
   * Synthesize text to speech audio
   *
   * @param text The text to synthesize
   * @param options Configuration options for synthesis
   * @return Either a SpeechError or AudioResponse
   */
  def synthesize(
    text: String,
    options: TTSSynthesisOptions = TTSSynthesisOptions()
  ): Either[SpeechError, AudioResponse]
}
