package org.llm4s.speech.tts.provider

import org.llm4s.http.Llm4sHttpClient
import org.llm4s.speech.{ AudioMeta, CloudSpeechSupport, GeneratedAudio }
import org.llm4s.speech.config.TTSConfig
import org.llm4s.speech.tts.{ TTSError, TTSOptions, TextToSpeech }
import org.llm4s.types.Result

import scala.concurrent.duration._

/**
 * OpenAI text-to-speech (`POST /v1/audio/speech`).
 *
 * Asks for `response_format = pcm`, which OpenAI documents as raw 24 kHz, 16-bit, mono
 * little-endian samples, so [[GeneratedAudio.data]] is headerless PCM described by an honest
 * [[AudioMeta]] - the same shape [[org.llm4s.speech.tts.Tacotron2TextToSpeech]] produces. Write it out
 * with [[org.llm4s.speech.io.WavFileGenerator.saveAsWav]].
 *
 * `options.voice` overrides the configured voice; `options.speakingRate` is sent as `speed`
 * (OpenAI accepts 0.25 to 4.0).
 *
 * @param config     configuration from [[org.llm4s.speech.config.SpeechConfigLoader.tts]]
 * @param httpClient HTTP transport, replaceable in tests
 */
final class OpenAITTSClient(config: TTSConfig, httpClient: Llm4sHttpClient = Llm4sHttpClient.create())
    extends TextToSpeech {

  override val name: String = "openai-tts"

  override def synthesize(text: String, options: TTSOptions): Result[GeneratedAudio] =
    for {
      input <- CloudSpeechSupport.requireText(text)
      payload = ujson.Obj(
        "model"           -> config.model,
        "input"           -> input,
        "voice"           -> options.voice.getOrElse(config.voice),
        "response_format" -> OpenAITTSClient.ResponseFormat
      )
      _ = options.speakingRate.foreach(rate => payload("speed") = rate)
      response <- httpClient.postRaw(
        s"${config.baseUrl}/v1/audio/speech",
        Map("Authorization" -> s"Bearer ${config.apiKey}", "Content-Type" -> "application/json"),
        ujson.write(payload),
        60.seconds
      )
      audio <- CloudSpeechSupport.rawBody(name, response)
      _     <- Either.cond(audio.nonEmpty, (), TTSError.SynthesisFailed("OpenAI TTS returned an empty audio body"))
    } yield GeneratedAudio(audio, OpenAITTSClient.PcmMeta, options.outputFormat)
}

object OpenAITTSClient {

  /** OpenAI's raw PCM output: 24 kHz, 16-bit, mono. */
  val PcmMeta: AudioMeta = AudioMeta(sampleRate = 24000, numChannels = 1, bitDepth = 16)

  private[tts] val ResponseFormat = "pcm"
}
