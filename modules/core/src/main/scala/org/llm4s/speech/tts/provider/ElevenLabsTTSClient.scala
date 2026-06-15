// scalafix:off DisableSyntax.NoKeywordTry, DisableSyntax.NoKeywordCatch
package org.llm4s.speech.tts.provider

import org.llm4s.http.Llm4sHttpClient
import org.llm4s.speech.{ AudioMeta, GeneratedAudio }
import org.llm4s.speech.config.TTSConfig
import org.llm4s.speech.tts.{ TTSError, TTSOptions, TextToSpeech }
import org.llm4s.types.Result
import org.slf4j.LoggerFactory
import ujson.Obj

import scala.util.control.NonFatal

/**
 * ElevenLabs Text-to-Speech client.
 *
 * Calls ElevenLabs /v1/text-to-speech/{voice_id} endpoint.
 * Returns MP3 audio bytes.
 *
 * Auth: ELEVENLABS_API_KEY
 * Config: SPEECH_TTS_MODEL=elevenlabs/<voice-id>
 */
object ElevenLabsTTSClient {

  /**
   * Creates an ElevenLabsTTSClient backed by the real JDK HTTP client.
   */
  def fromConfig(cfg: TTSConfig): TextToSpeech =
    create(cfg, Llm4sHttpClient.create())

  private[provider] def forTest(cfg: TTSConfig, httpClient: Llm4sHttpClient): TextToSpeech =
    create(cfg, httpClient)

  private def create(cfg: TTSConfig, httpClient: Llm4sHttpClient): TextToSpeech =
    new TextToSpeech {
      private val logger = LoggerFactory.getLogger(getClass)

      override val name: String = "elevenlabs-tts"

      override def synthesize(text: String, options: TTSOptions): Result[GeneratedAudio] = {
        // For ElevenLabs, the voice ID is used as the path segment
        val voiceId = options.voice.getOrElse(cfg.voice)
        val url     = s"${cfg.baseUrl}/v1/text-to-speech/$voiceId"

        val payload = Obj(
          "text"     -> text,
          "model_id" -> cfg.model
        )

        logger.debug(s"[ElevenLabsTTSClient] POST $url voiceId=$voiceId")

        val headers = Map(
          "xi-api-key"   -> cfg.apiKey,
          "Content-Type" -> "application/json",
          "Accept"       -> "audio/mpeg"
        )

        try {
          val response = httpClient.post(url, headers, payload.render(), timeout = 60000)
          response.statusCode match {
            case 200 =>
              val audioBytes = response.body.getBytes("ISO-8859-1")
              Right(
                GeneratedAudio(
                  data = audioBytes,
                  meta = AudioMeta(sampleRate = 44100, numChannels = 1, bitDepth = 16),
                  format = options.outputFormat
                )
              )
            case 401 =>
              Left(TTSError.EngineNotAvailable(s"ElevenLabs authentication failed: ${response.body}"))
            case 422 =>
              Left(TTSError.SynthesisFailed(s"ElevenLabs invalid request: ${response.body}"))
            case status =>
              Left(TTSError.SynthesisFailed(s"ElevenLabs API returned HTTP $status: ${response.body}"))
          }
        } catch {
          case NonFatal(e) =>
            logger.error(s"[ElevenLabsTTSClient] Request failed: ${e.getMessage}")
            Left(TTSError.SynthesisFailed(s"ElevenLabs TTS request failed: ${e.getMessage}"))
        }
      }
    }
}
