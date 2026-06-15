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
 * OpenAI Text-to-Speech client.
 *
 * Calls POST /v1/audio/speech with model (tts-1, tts-1-hd), voice (alloy, echo, fable, etc.),
 * and input text. Returns MP3 audio bytes.
 *
 * Auth: reuses OPENAI_API_KEY
 * Config: SPEECH_TTS_MODEL=openai/tts-1, SPEECH_TTS_VOICE=alloy
 */
object OpenAITTSClient {

  /**
   * Creates an OpenAITTSClient backed by the real JDK HTTP client.
   */
  def fromConfig(cfg: TTSConfig): TextToSpeech =
    create(cfg, Llm4sHttpClient.create())

  private[tts] def forTest(cfg: TTSConfig, httpClient: Llm4sHttpClient): TextToSpeech =
    create(cfg, httpClient)

  private def create(cfg: TTSConfig, httpClient: Llm4sHttpClient): TextToSpeech =
    new TextToSpeech {
      private val logger = LoggerFactory.getLogger(getClass)

      override val name: String = "openai-tts"

      override def synthesize(text: String, options: TTSOptions): Result[GeneratedAudio] = {
        val model = cfg.model
        val voice = options.voice.getOrElse(cfg.voice)
        val url   = s"${cfg.baseUrl}/v1/audio/speech"

        val payload = Obj(
          "model" -> model,
          "input" -> text,
          "voice" -> voice
        )

        logger.debug(s"[OpenAITTSClient] POST $url model=$model voice=$voice")

        val headers = Map(
          "Authorization" -> s"Bearer ${cfg.apiKey}",
          "Content-Type"  -> "application/json"
        )

        try {
          val response = httpClient.post(url, headers, payload.render(), timeout = 60000)
          response.statusCode match {
            case 200 =>
              val audioBytes = response.body.getBytes("ISO-8859-1")
              Right(
                GeneratedAudio(
                  data = audioBytes,
                  meta = AudioMeta(sampleRate = 24000, numChannels = 1, bitDepth = 16),
                  format = options.outputFormat
                )
              )
            case 401 =>
              Left(TTSError.EngineNotAvailable(s"OpenAI TTS authentication failed: ${response.body}"))
            case status =>
              Left(TTSError.SynthesisFailed(s"OpenAI TTS API returned HTTP $status: ${response.body}"))
          }
        } catch {
          case NonFatal(e) =>
            logger.error(s"[OpenAITTSClient] Request failed: ${e.getMessage}")
            Left(TTSError.SynthesisFailed(s"OpenAI TTS request failed: ${e.getMessage}"))
        }
      }
    }
}
