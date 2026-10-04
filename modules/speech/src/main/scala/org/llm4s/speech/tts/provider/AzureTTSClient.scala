package org.llm4s.speech.tts.provider

import org.llm4s.http.Llm4sHttpClient
import org.llm4s.speech.{ AudioMeta, CloudSpeechSupport, GeneratedAudio }
import org.llm4s.speech.config.TTSConfig
import org.llm4s.speech.tts.{ TTSError, TTSOptions, TextToSpeech }
import org.llm4s.types.Result

import scala.concurrent.duration._

/**
 * Azure AI Speech text-to-speech (REST `POST /cognitiveservices/v1`, SSML body).
 *
 * Requests the `raw-24khz-16bit-mono-pcm` output format, so [[GeneratedAudio.data]] is headerless
 * 24 kHz, 16-bit, mono PCM described by an honest [[AudioMeta]]. Write it out with
 * [[org.llm4s.speech.io.WavFileGenerator.saveAsWav]].
 *
 * `options.voice` overrides the configured voice name; `options.language` sets `xml:lang` (default:
 * the locale prefix of the voice name, else `en-US`); `options.speakingRate` becomes a `prosody` rate.
 *
 * @param config     configuration from [[org.llm4s.speech.config.SpeechConfigLoader.tts]]; `baseUrl` is the
 *                   regional `https://<region>.tts.speech.microsoft.com` endpoint
 * @param httpClient HTTP transport, replaceable in tests
 */
final class AzureTTSClient(config: TTSConfig, httpClient: Llm4sHttpClient = Llm4sHttpClient.create())
    extends TextToSpeech {

  override val name: String = "azure-tts"

  override def synthesize(text: String, options: TTSOptions): Result[GeneratedAudio] =
    for {
      input <- CloudSpeechSupport.requireText(text)
      voice = options.voice.getOrElse(config.voice)
      response <- httpClient.postRaw(
        s"${config.baseUrl}/cognitiveservices/v1",
        Map(
          "Ocp-Apim-Subscription-Key" -> config.apiKey,
          "Content-Type"              -> "application/ssml+xml",
          "X-Microsoft-OutputFormat"  -> AzureTTSClient.OutputFormat,
          "User-Agent"                -> "llm4s"
        ),
        AzureTTSClient.ssml(input, voice, options),
        60.seconds
      )
      audio <- CloudSpeechSupport.rawBody(name, response, config.apiKey)
      _     <- Either.cond(audio.nonEmpty, (), TTSError.SynthesisFailed("Azure TTS returned an empty audio body"))
    } yield GeneratedAudio(audio, AzureTTSClient.PcmMeta, options.outputFormat)
}

object AzureTTSClient {

  /** Azure's `raw-24khz-16bit-mono-pcm` output: 24 kHz, 16-bit, mono. */
  val PcmMeta: AudioMeta = AudioMeta(sampleRate = 24000, numChannels = 1, bitDepth = 16)

  private[tts] val OutputFormat = "raw-24khz-16bit-mono-pcm"

  private[tts] def ssml(text: String, voice: String, options: TTSOptions): String = {
    val lang = options.language.getOrElse(localeOf(voice))
    val body = options.speakingRate match {
      case Some(rate) => s"<prosody rate='$rate'>${escapeXml(text)}</prosody>"
      case None       => escapeXml(text)
    }
    s"<speak version='1.0' xml:lang='${escapeXml(lang)}'><voice name='${escapeXml(voice)}'>$body</voice></speak>"
  }

  /** `en-US-JennyNeural` is `en-US`; anything without a locale prefix defaults to `en-US`. */
  private def localeOf(voice: String): String =
    voice.split("-").toList match {
      case language :: region :: _ if language.length == 2 && region.length == 2 => s"$language-$region"
      case _                                                                     => "en-US"
    }

  private def escapeXml(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
}
