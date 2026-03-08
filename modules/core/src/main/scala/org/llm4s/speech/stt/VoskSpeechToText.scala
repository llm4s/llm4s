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

/**
 * Vosk-based speech-to-text implementation.
 * Replaces Sphinx4 as it's more actively maintained and has better performance.
 */
final class VoskSpeechToText(
  modelPath: Option[String] = None
) extends SpeechToText {

  override val name: String = "vosk"

  override def transcribe(input: AudioInput, options: STTOptions): Result[Transcription] =
    for {
      audioBytes <- prepareAudioForVosk(input)
      transcription <- {
        val r = Using.Manager { use =>
          val model      = use(new Model(modelPath.getOrElse("models/vosk-model-small-en-us-0.15")))
          val recognizer = use(new Recognizer(model, 16000.0f))
          val audio      = use(new ByteArrayInputStream(audioBytes))

          val buffer    = new Array[Byte](4096)
          val sb        = new StringBuilder
          var bytesRead = audio.read(buffer)
          while (bytesRead > 0) {
            if (recognizer.acceptWaveForm(buffer, bytesRead)) sb.append(recognizer.getResult())
            bytesRead = audio.read(buffer)
          }
          sb.append(recognizer.getFinalResult())

          Transcription(
            text = sb.toString().trim,
            language = options.language.orElse(Some("en")),
            confidence = None,
            timestamps = Nil,
            meta = None
          )
        }
        r.toEither.left.map(e => ProcessingError.audioValidation("Vosk processing failed", Some(e)))
      }
    } yield transcription

  private def prepareAudioForVosk(input: AudioInput): Result[Array[Byte]] =
    input match {
      case AudioInput.FileAudio(path) =>
        Safety
          .fromTry(Try(Files.readAllBytes(path)))
          .left
          .map(_ => ProcessingError.audioValidation("Failed to read audio file"))
      case AudioInput.BytesAudio(bytes, sampleRate, channels) =>
        val meta = AudioMeta(sampleRate = sampleRate, numChannels = channels, bitDepth = 16)
        AudioPreprocessing.standardizeForSTT(bytes, meta, targetRate = 16000).map { case (b, _) => b }
      case AudioInput.StreamAudio(stream, sampleRate, channels) =>
        Safety
          .fromTry(Try(stream.readAllBytes()))
          .left
          .map(_ => ProcessingError.audioValidation("Failed to read audio stream"))
          .flatMap { bytes =>
            val meta = AudioMeta(sampleRate = sampleRate, numChannels = channels, bitDepth = 16)
            AudioPreprocessing.standardizeForSTT(bytes, meta, targetRate = 16000).map { case (b, _) => b }
          }
    }
}
