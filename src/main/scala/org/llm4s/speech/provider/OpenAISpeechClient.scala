package org.llm4s.speech.provider

import requests.Response
import requests.Session
import org.llm4s.speech._
import org.llm4s.speech.config.OpenAISpeechConfig
import org.llm4s.speech.model._
import ujson._

import java.util.Base64

class OpenAISpeechClient(config: OpenAISpeechConfig) extends TTSClient with ASRClient {

  private val session = Session()

  override def synthesize(
    text: String,
    options: TTSSynthesisOptions
  ): Either[SpeechError, AudioResponse] =
    try {
      val requestBody = Obj(
        "model"           -> options.model,
        "input"           -> text,
        "voice"           -> options.voice,
        "response_format" -> options.responseFormat,
        "speed"           -> options.speed
      )

      val response = session.post(
        s"${config.baseUrl}/audio/speech",
        data = requestBody.render(),
        headers = Map(
          "Authorization" -> s"Bearer ${config.apiKey}",
          "Content-Type"  -> "application/json"
        )
      )

      if (response.statusCode == 200) {
        val audioData = response.bytes
        Right(
          AudioResponse(
            audioData = audioData,
            format = options.responseFormat
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
      // Convert audio data to base64
      val base64Audio = Base64.getEncoder.encodeToString(audioData)

      val baseObj = Obj(
        "model"           -> options.model,
        "file"            -> base64Audio,
        "response_format" -> options.responseFormat,
        "temperature"     -> options.temperature
      )

      val withLanguage =
        options.language.map(lang => baseObj.obj ++ Map("language" -> Str(lang))).getOrElse(baseObj.obj)
      val requestBody =
        options.prompt.map(prompt => withLanguage ++ Map("prompt" -> Str(prompt))).getOrElse(withLanguage)

      val response = session.post(
        s"${config.baseUrl}/audio/transcriptions",
        data = requestBody.render(),
        headers = Map(
          "Authorization" -> s"Bearer ${config.apiKey}",
          "Content-Type"  -> "application/json"
        )
      )

      if (response.statusCode == 200) {
        val responseJson = ujson.read(response.text(), trace = false)
        val text         = responseJson("text").str
        val language     = responseJson.obj.get("language").map(_.str)

        val segments = responseJson.obj
          .get("segments")
          .map { segmentsJson =>
            segmentsJson.arr.map { segment =>
              TranscriptionSegment(
                id = segment("id").num.toInt,
                start = segment("start").num,
                end = segment("end").num,
                text = segment("text").str,
                tokens = segment.obj.get("tokens").map(_.arr.map(_.num.toInt).toSeq).getOrElse(Seq.empty),
                temperature = segment.obj.get("temperature").map(_.num),
                avgLogprob = segment.obj.get("avg_logprob").map(_.num),
                compressionRatio = segment.obj.get("compression_ratio").map(_.num),
                noSpeechProb = segment.obj.get("no_speech_prob").map(_.num)
              )
            }.toSeq
          }
          .getOrElse(Seq.empty)

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
