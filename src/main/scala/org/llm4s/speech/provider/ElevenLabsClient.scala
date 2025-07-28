package org.llm4s.speech.provider

import requests.Response
import requests.Session
import org.llm4s.speech._
import org.llm4s.speech.config.ElevenLabsConfig
import org.llm4s.speech.model._
import ujson._

class ElevenLabsClient(config: ElevenLabsConfig) extends TTSClient {

  private val session = Session()

  override def synthesize(
    text: String,
    options: TTSSynthesisOptions
  ): Either[SpeechError, AudioResponse] =
    try {
      val requestBody = Obj(
        "text"     -> text,
        "model_id" -> options.model,
        "voice_settings" -> Obj(
          "stability"         -> 0.5,
          "similarity_boost"  -> 0.5,
          "style"             -> 0.0,
          "use_speaker_boost" -> true
        )
      )

      val response = session.post(
        s"${config.baseUrl}/text-to-speech/${options.voice}",
        data = requestBody.render(),
        headers = Map(
          "xi-api-key"   -> config.apiKey,
          "Content-Type" -> "application/json"
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
