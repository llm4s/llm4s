package org.llm4s.speech.tts

import org.llm4s.error.{ CancelledError, ProcessingError, ValidationError }
import org.llm4s.speech.util.CommandRuns
import org.llm4s.types.Result
import org.llm4s.speech.{ AudioFormat, GeneratedAudio }
import org.llm4s.speech.io.WavFileGenerator
import cats.implicits._

import scala.sys.process._

/**
 * Tacotron2 integration via CLI or local server. This is a thin adapter;
 * actual model hosting is assumed external.
 */
final class Tacotron2TextToSpeech(
  command: Seq[String] = Seq("tacotron2-cli")
) extends TextToSpeech {
  override val name: String = "tacotron2-cli"

  override def synthesize(text: String, options: TTSOptions): Result[GeneratedAudio] =
    for {
      _ <- Either.cond(
        options.outputFormat != AudioFormat.Mp3,
        (),
        ValidationError("outputFormat", "Tacotron2 produces PCM; MP3 output is only offered by the cloud TTS clients")
      )
      tmpOut <- WavFileGenerator.createTempWavFile("llm4s-tts-")
      baseCommand = command ++ Seq("--text", text, "--out", tmpOut.toString)

      optFlags = List(
        options.voice.map(v => Seq("--voice", v)),
        options.language.map(l => Seq("--lang", l)),
        options.speakingRate.map(r => Seq("--rate", r.toString)),
        options.pitchSemitones.map(p => Seq("--pitch", p.toString)),
        options.volumeGainDb.map(v => Seq("--gain", v.toString))
      ).flatten

      args = baseCommand ++ optFlags.combineAll

      _ <- CommandRuns
        .exitCode(args)
        .left
        .map {
          // An interrupt stops the program and is a cancellation, not a failed run (design section 4.4).
          case interrupted: InterruptedException => CancelledError("tacotron2.synthesize", Some(interrupted))
          case _                                 => ProcessingError.audioValidation("Tacotron2 CLI execution failed")
        }

      audio <- WavFileGenerator.readWavFile(tmpOut)

    } yield audio.copy(format = options.outputFormat)
}
