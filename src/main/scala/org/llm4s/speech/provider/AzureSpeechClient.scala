package org.llm4s.speech.provider

import requests.Response
import requests.Session
import org.llm4s.speech._
import org.llm4s.speech.config.AzureSpeechConfig
import org.llm4s.speech.model._
import ujson._

import java.util.Base64

class AzureSpeechClient(config: AzureSpeechConfig) extends TTSClient with ASRClient {

  private val session = Session()

  override def synthesize(
    text: String,
    options: TTSSynthesisOptions
  ): Either[SpeechError, AudioResponse] =
    try {
      val requestBody = Obj(
        "text" -> text
      )

      val response = session.post(
        s"${config.baseUrl.format(config.region)}/synthesize",
        data = requestBody.render(),
        headers = Map(
          "Ocp-Apim-Subscription-Key" -> config.apiKey,
          "Content-Type"              -> "application/json",
          "X-Microsoft-OutputFormat"  -> "audio-16khz-128kbitrate-mono-mp3"
        )
      )

      if (response.statusCode == 200) {
        val audioData = response.bytes
        Right(
          AudioResponse(
            audioData = audioData,
            format = "mp3"
          )
        )
      } else {
        handleErrorResponse(response)
      }
    } catch {
      case e: Exception =>
        Left(SpeechUnknownError(e))
    }

  override def transcribe(
    audioData: Array[Byte],
    options: ASRTranscriptionOptions
  ): Either[SpeechError, TranscriptionResponse] =
    try {
      // For Azure Speech, we need to use the Speech SDK or REST API
      // This is a simplified implementation using REST API
      val base64Audio = Base64.getEncoder.encodeToString(audioData)

      val requestBody = Obj(
        "audio"    -> base64Audio,
        "language" -> Str(options.language.getOrElse("en-US")),
        "model"    -> options.model
      )

      val response = session.post(
        s"${config.baseUrl.format(config.region)}/speechtotext/v3.0/transcriptions",
        data = requestBody.render(),
        headers = Map(
          "Ocp-Apim-Subscription-Key" -> config.apiKey,
          "Content-Type"              -> "application/json"
        )
      )

      if (response.statusCode == 200) {
        val responseJson = ujson.read(response.text(), trace = false)
        val text         = responseJson("DisplayText").str
        val language     = responseJson.obj.get("Language").map(_.str)

        // Azure doesn't provide detailed segments in the same format as OpenAI
        // We'll create a simple segment with the full text
        val segments = Seq(
          TranscriptionSegment(
            id = 0,
            start = 0.0,
            end = 0.0, // Duration not provided in this simplified response
            text = text
          )
        )

        Right(
          TranscriptionResponse(
            text = text,
            language = language,
            segments = segments
          )
        )
      } else {
        handleErrorResponse(response)
      }
    } catch {
      case e: Exception =>
        Left(SpeechUnknownError(e))
    }

  private def handleErrorResponse(response: Response): Either[SpeechError, Nothing] = {
    val errorBody =
      try
        ujson.read(response.text(), trace = false)
      catch {
        case _: Exception => Obj("error" -> Obj("message" -> response.text()))
      }

    val errorMessage = errorBody.obj
      .get("error")
      .flatMap(_.obj.get("message"))
      .map(_.str)
      .getOrElse(s"HTTP ${response.statusCode}: ${response.text()}")

    response.statusCode match {
      case 401 => Left(SpeechAuthenticationError(errorMessage))
      case 429 => Left(SpeechRateLimitError(errorMessage))
      case 400 => Left(SpeechValidationError(errorMessage))
      case _   => Left(SpeechUnknownError(new Exception(errorMessage)))
    }
  }
}
