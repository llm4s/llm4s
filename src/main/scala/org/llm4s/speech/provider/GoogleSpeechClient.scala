package org.llm4s.speech.provider

import requests.Response
import requests.Session
import org.llm4s.speech._
import org.llm4s.speech.config.GoogleSpeechConfig
import org.llm4s.speech.model._
import ujson._

import java.util.Base64

class GoogleSpeechClient(config: GoogleSpeechConfig) extends TTSClient with ASRClient {

  private val session = Session()

  override def synthesize(
    text: String,
    options: TTSSynthesisOptions
  ): Either[SpeechError, AudioResponse] =
    try {
      val requestBody = Obj(
        "input" -> Obj(
          "text" -> text
        ),
        "voice" -> Obj(
          "languageCode" -> "en-US",
          "name"         -> options.voice,
          "ssmlGender"   -> "NEUTRAL"
        ),
        "audioConfig" -> Obj(
          "audioEncoding" -> "MP3",
          "speakingRate"  -> options.speed,
          "pitch"         -> 0.0,
          "volumeGainDb"  -> 0.0
        )
      )

      val response = session.post(
        s"${config.baseUrl}/text:synthesize",
        data = requestBody.render(),
        headers = Map(
          "Authorization" -> s"Bearer ${config.apiKey}",
          "Content-Type"  -> "application/json"
        )
      )

      if (response.statusCode == 200) {
        val responseJson = ujson.read(response.text(), trace = false)
        val audioContent = responseJson("audioContent").str
        val audioData    = Base64.getDecoder.decode(audioContent)

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
      val base64Audio = Base64.getEncoder.encodeToString(audioData)

      val requestBody = Obj(
        "config" -> Obj(
          "encoding"                   -> "MP3",
          "sampleRateHertz"            -> 16000,
          "languageCode"               -> Str(options.language.getOrElse("en-US")),
          "model"                      -> options.model,
          "enableWordTimeOffsets"      -> true,
          "enableAutomaticPunctuation" -> true
        ),
        "audio" -> Obj(
          "content" -> base64Audio
        )
      )

      val response = session.post(
        s"${config.baseUrl}/speech:recognize",
        data = requestBody.render(),
        headers = Map(
          "Authorization" -> s"Bearer ${config.apiKey}",
          "Content-Type"  -> "application/json"
        )
      )

      if (response.statusCode == 200) {
        val responseJson = ujson.read(response.text(), trace = false)
        val results      = responseJson("results").arr

        if (results.nonEmpty) {
          val alternatives = results.head("alternatives").arr
          if (alternatives.nonEmpty) {
            val alternative = alternatives.head
            val text        = alternative("transcript").str

            // Extract word-level timing information
            val words = alternative.obj
              .get("words")
              .map { wordsJson =>
                wordsJson.arr.map { word =>
                  val startTime = word("startTime").str.replace("s", "").toDouble
                  val endTime   = word("endTime").str.replace("s", "").toDouble
                  val wordText  = word("word").str
                  (startTime, endTime, wordText)
                }.toSeq
              }
              .getOrElse(Seq.empty)

            // Create segments based on words
            val segments = if (words.nonEmpty) {
              Seq(
                TranscriptionSegment(
                  id = 0,
                  start = words.head._1,
                  end = words.last._2,
                  text = text
                )
              )
            } else {
              Seq(
                TranscriptionSegment(
                  id = 0,
                  start = 0.0,
                  end = 0.0,
                  text = text
                )
              )
            }

            Right(
              TranscriptionResponse(
                text = text,
                language = options.language,
                segments = segments
              )
            )
          } else {
            Left(SpeechValidationError("No transcription alternatives found"))
          }
        } else {
          Left(SpeechValidationError("No transcription results found"))
        }
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
