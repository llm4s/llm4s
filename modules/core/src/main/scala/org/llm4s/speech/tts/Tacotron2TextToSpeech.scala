package org.llm4s.speech.tts

import org.llm4s.error.ProcessingError
import org.llm4s.types.Result
import org.llm4s.speech.GeneratedAudio
import org.llm4s.speech.io.WavFileGenerator
import cats.implicits._

import scala.sys.process._
import scala.util.Try
import org.llm4s.types.TryOps

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

      _ <- Try(args.!).toResult.left.map(_ => ProcessingError.audioValidation("Tacotron2 CLI execution failed"))

      audio <- WavFileGenerator.readWavFile(tmpOut)

    } yield audio.copy(format = options.outputFormat)
}
