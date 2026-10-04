package org.llm4s.speech.tts.provider

import org.llm4s.http.Llm4sHttpClient
import org.llm4s.speech.{ AudioMeta, CloudSpeechSupport, GeneratedAudio }
import org.llm4s.speech.config.TTSConfig
import org.llm4s.speech.tts.{ TTSError, TTSOptions, TextToSpeech }
import org.llm4s.types.Result

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import scala.concurrent.duration._

/**
 * ElevenLabs text-to-speech (`POST /v1/text-to-speech/{voice_id}`).
 *
 * Requests `output_format=pcm_24000` (raw 24 kHz, 16-bit, mono little-endian samples), so
 * [[GeneratedAudio.data]] is headerless PCM described by an honest [[AudioMeta]]. Write it out with
 * [[org.llm4s.speech.io.WavFileGenerator.saveAsWav]]. `options.voice` overrides the configured voice id.
 *
 * @param config     configuration from [[org.llm4s.speech.config.SpeechConfigLoader.tts]]; `model` is the
 *                   ElevenLabs `model_id` and `voice` the voice id
 * @param httpClient HTTP transport, replaceable in tests
 */
final class ElevenLabsTTSClient(config: TTSConfig, httpClient: Llm4sHttpClient = Llm4sHttpClient.create())
    extends TextToSpeech {

  override val name: String = "elevenlabs-tts"

  override def synthesize(text: String, options: TTSOptions): Result[GeneratedAudio] =
    for {
      input <- CloudSpeechSupport.requireText(text)
      voiceId = URLEncoder.encode(options.voice.getOrElse(config.voice), StandardCharsets.UTF_8).replace("+", "%20")
      response <- httpClient.postRaw(
        s"${config.baseUrl}/v1/text-to-speech/$voiceId?output_format=${ElevenLabsTTSClient.OutputFormat}",
        Map("xi-api-key" -> config.apiKey, "Content-Type" -> "application/json"),
        ujson.write(ujson.Obj("text" -> input, "model_id" -> config.model)),
        60.seconds
      )
      audio <- CloudSpeechSupport.rawBody(name, response)
      _     <- Either.cond(audio.nonEmpty, (), TTSError.SynthesisFailed("ElevenLabs returned an empty audio body"))
    } yield GeneratedAudio(audio, ElevenLabsTTSClient.PcmMeta, options.outputFormat)
}

object ElevenLabsTTSClient {

  /** ElevenLabs' `pcm_24000` output: 24 kHz, 16-bit, mono. */
  val PcmMeta: AudioMeta = AudioMeta(sampleRate = 24000, numChannels = 1, bitDepth = 16)

  private[tts] val OutputFormat = "pcm_24000"
}
