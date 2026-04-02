package org.llm4s.speech.stt

import org.llm4s.speech.{ AudioInput, AudioMeta }
import org.llm4s.types.Result
import org.llm4s.error.ProcessingError
import org.vosk.Model
import org.vosk.Recognizer
import java.io.ByteArrayInputStream
import scala.util.{ Try, Using }
import java.nio.file.Files
import org.llm4s.core.safety.Safety
import org.llm4s.speech.processing.AudioPreprocessing
import org.slf4j.LoggerFactory

/**
 * Vosk-based speech-to-text implementation.
 * Replaces Sphinx4 as it's more actively maintained and has better performance.
 *
 * @param modelPath Path to the Vosk model directory. Defaults to standard Vosk model location.
 * @param targetSampleRate Target sample rate for audio preprocessing (Hz). Vosk standard is 16000.
 * @param bufferSize Buffer size for audio processing (bytes). Larger sizes may improve throughput.
 */
final class VoskSpeechToText(
  modelPath: Option[String] = None,
  targetSampleRate: Int = VoskSpeechToText.DEFAULT_SAMPLE_RATE,
  bufferSize: Int = VoskSpeechToText.DEFAULT_BUFFER_SIZE
) extends SpeechToText {

  private val logger = LoggerFactory.getLogger(getClass)

  override val name: String = "vosk"

  override val supportedFormats: List[String] = List("audio/wav", "audio/pcm")

  /**
   * Cached Vosk model to avoid reloading on each transcription.
   *  Models are large; caching improves performance significantly.
   */
  private lazy val model: Model = {
    val path = modelPath.getOrElse(VoskSpeechToText.DEFAULT_MODEL_PATH)
    logger.info(s"Loading Vosk model from $path")
    new Model(path)
  }

  override def transcribe(input: AudioInput, options: STTOptions): Result[Transcription] =
    for {
      audioBytes <- prepareAudioForVosk(input)
      transcription <- Safety
        .fromTry(Try(Using.resource(new ByteArrayInputStream(audioBytes)) { audio =>
          val recognizer = new Recognizer(model, targetSampleRate.toFloat)
          transcribeAudio(audio, recognizer, bufferSize, options)
        }))
        .left
        .map { case e: Throwable =>
          logger.error("Vosk transcription failed", e)
          ProcessingError.audioValidation("Vosk transcription failed", Some(e))
        }
    } yield transcription

  /**
   * Transcribe audio stream using Vosk recognizer.
   *
   * @param audio Input audio stream (ByteArrayInputStream)
   * @param recognizer Configured Vosk recognizer
   * @param bufferSize Size of read buffer per iteration
   * @param options STT options (language, timestamps, etc.)
   * @return Transcription result
   */
  private def transcribeAudio(
    audio: ByteArrayInputStream,
    recognizer: Recognizer,
    bufferSize: Int,
    options: STTOptions
  ): Transcription = {

    val buffer   = new Array[Byte](bufferSize)
    val segments = List.newBuilder[String]

    var bytesRead = audio.read(buffer)

    while (bytesRead > 0) {
      if (recognizer.acceptWaveForm(buffer, bytesRead)) {
        segments += extractText(recognizer.getResult)
      }
      bytesRead = audio.read(buffer)
    }

    segments += extractText(recognizer.getFinalResult)

    val finalText = segments.result().mkString(" ").trim

    Transcription(
      text = finalText,
      language = options.language.orElse(Some("en")),
      confidence = None,
      timestamps = Nil,
      meta = None
    )
  }

  /**
   * Extract "text" field from Vosk JSON response.
   *  Vosk returns JSON with format: {"text": "transcribed words"}
   */
  private def extractText(json: String): String =
    "\"text\"\\s*:\\s*\"([^\"]*)\"".r
      .findFirstMatchIn(json)
      .map(_.group(1))
      .getOrElse("")

  /**
   * Prepare audio input by converting to raw bytes and standardizing format.
   *
   * @param input Audio input (file, bytes, or stream)
   * @return Result containing raw audio bytes or ProcessingError
   */
  private def prepareAudioForVosk(input: AudioInput): Result[Array[Byte]] =
    input match {
      case AudioInput.FileAudio(path) =>
        Safety
          .fromTry(Try(Files.readAllBytes(path)))
          .left
          .map(_ => ProcessingError.audioValidation("Failed to read audio file"))
      case AudioInput.BytesAudio(bytes, sampleRate, channels) =>
        val meta = AudioMeta(sampleRate = sampleRate, numChannels = channels, bitDepth = 16)
        AudioPreprocessing.standardizeForSTT(bytes, meta, targetRate = targetSampleRate).map { case (b, _) => b }
      case AudioInput.StreamAudio(stream, sampleRate, channels) =>
        Safety
          .fromTry(Try(stream.readAllBytes()))
          .left
          .map(_ => ProcessingError.audioValidation("Failed to read audio stream"))
          .flatMap { bytes =>
            val meta = AudioMeta(sampleRate = sampleRate, numChannels = channels, bitDepth = 16)
            AudioPreprocessing.standardizeForSTT(bytes, meta, targetRate = targetSampleRate).map { case (b, _) => b }
          }
    }
}

object VoskSpeechToText {

  /** Default Vosk model path for small English model */
  val DEFAULT_MODEL_PATH: String = "models/vosk-model-small-en-us-0.15"

  /** Standard sample rate expected by Vosk (Hz) */
  val DEFAULT_SAMPLE_RATE: Int = 16000

  /** Default buffer size for audio processing (bytes) */
  val DEFAULT_BUFFER_SIZE: Int = 4096
}
