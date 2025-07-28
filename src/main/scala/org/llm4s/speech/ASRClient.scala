package org.llm4s.speech

import org.llm4s.speech.model.{ ASRTranscriptionOptions, SpeechError, TranscriptionResponse }

/**
 * Client interface for automatic speech recognition
 */
trait ASRClient {

  /**
   * Transcribe audio to text
   *
   * @param audioData The audio data to transcribe
   * @param options Configuration options for transcription
   * @return Either a SpeechError or TranscriptionResponse
   */
  def transcribe(
    audioData: Array[Byte],
    options: ASRTranscriptionOptions = ASRTranscriptionOptions()
  ): Either[SpeechError, TranscriptionResponse]
}
