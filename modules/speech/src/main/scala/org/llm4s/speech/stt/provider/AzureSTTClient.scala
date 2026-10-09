package org.llm4s.speech.stt.provider

import org.llm4s.http.Llm4sHttpClient
import org.llm4s.speech.{ AudioInput, CloudSpeechSupport }
import org.llm4s.speech.config.STTConfig
import org.llm4s.speech.stt.{ STTError, STTOptions, SpeechToText, Transcription }
import org.llm4s.types.{ Result, TryOps }

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import scala.concurrent.duration._
import scala.util.Try

/**
 * Azure AI Speech speech-to-text (short-audio REST API, `POST /speech/recognition/conversation/cognitiveservices/v1`).
 *
 * The REST endpoint recognises at most about 60 seconds of audio and takes WAV (PCM) input:
 * `FileAudio`, `BytesAudio` and `StreamAudio` are all read as WAV data, and the sample rate is
 * read from the WAV header, falling back to the input's `sampleRate`, then 16 kHz.
 *
 * The recognition language is `options.language`, else the configured default (`model` of the
 * config, e.g. `azure/en-US`).
 *
 * @param config     configuration from [[org.llm4s.speech.config.SpeechConfigLoader.stt]]; `baseUrl` is the
 *                   regional `https://<region>.stt.speech.microsoft.com` endpoint
 * @param httpClient HTTP transport, replaceable in tests
 */
final class AzureSTTClient(config: STTConfig, httpClient: Llm4sHttpClient = Llm4sHttpClient.create())
    extends SpeechToText {

  override val name: String = "azure-stt"

  override val supportedFormats: List[String] = List("audio/wav")

  override def transcribe(input: AudioInput, options: STTOptions): Result[Transcription] =
    for {
      audio <- CloudSpeechSupport.readAudio(input)
      sampleRate = CloudSpeechSupport
        .wavSampleRate(audio)
        .orElse(AzureSTTClient.declaredSampleRate(input))
        .getOrElse(AzureSTTClient.DefaultSampleRate)
      language = options.language.getOrElse(config.model)
      response <- httpClient.postBytes(
        s"${config.baseUrl}/speech/recognition/conversation/cognitiveservices/v1" +
          s"?language=${URLEncoder.encode(language, StandardCharsets.UTF_8)}&format=simple",
        Map(
          "Ocp-Apim-Subscription-Key" -> config.apiKey,
          "Content-Type"              -> s"audio/wav; codecs=audio/pcm; samplerate=$sampleRate",
          "Accept"                    -> "application/json"
        ),
        audio,
        120.seconds
      )
      body          <- CloudSpeechSupport.textBody(name, response, config.apiKey)
      transcription <- AzureSTTClient.parse(body, language)
    } yield transcription
}

object AzureSTTClient {

  private[provider] val DefaultSampleRate = 16000

  private[provider] def declaredSampleRate(input: AudioInput): Option[Int] =
    input match {
      case AudioInput.BytesAudio(_, rate, _)  => Some(rate)
      case AudioInput.StreamAudio(_, rate, _) => Some(rate)
      case _                                  => None
    }

  private[provider] def parse(body: String, language: String): Result[Transcription] =
    Try {
      val json = ujson.read(body)
      (
        json.obj.get("RecognitionStatus").flatMap(_.strOpt).getOrElse("Unknown"),
        json.obj.get("DisplayText").flatMap(_.strOpt).map(_.trim).getOrElse("")
      )
    }.toResult.left
      .map(e => STTError.ProcessingFailed(s"Failed to parse Azure STT response: ${e.message}"))
      .flatMap {
        case ("Success", text) if text.nonEmpty => Right(Transcription(text, Some(language)))
        case ("Success", _) => Left(STTError.ProcessingFailed("Azure STT returned an empty transcription"))
        case ("NoMatch", _) =>
          Left(STTError.ProcessingFailed("Azure STT: no speech could be recognized in the audio"))
        case ("InitialSilenceTimeout", _) =>
          Left(STTError.ProcessingFailed("Azure STT: the audio starts with silence that exceeded the timeout"))
        case (other, _) => Left(STTError.ProcessingFailed(s"Azure STT recognition failed with status: $other"))
      }
}
