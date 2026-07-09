package org.llm4s.speech.stt

import org.llm4s.types.Result
import org.llm4s.speech.AudioInput
import org.llm4s.speech.io.WavFileGenerator
import org.llm4s.error.ProcessingError
import cats.implicits._

import java.nio.file.{ Files, Path }
import java.io.IOException
import org.llm4s.core.safety.Safety
import scala.util.Try
import scala.sys.process._
import ujson.Value

/**
 * Enhanced Whisper integration via CLI (whisper.cpp or openai-whisper).
 * Supports various Whisper models and output formats.
 */
final class WhisperSpeechToText(
  command: Seq[String] = Seq("whisper"),
  model: String = "base",
  outputFormat: String = "txt"
) extends SpeechToText {
  override val name: String = "whisper-cli"

  override val supportedFormats: List[String] = List("audio/wav", "audio/mp3", "audio/m4a", "audio/flac", "audio/ogg")

  override def transcribe(input: AudioInput, options: STTOptions): Result[Transcription] = {
    val startTime             = System.currentTimeMillis()
    val wavResult             = inputToWavPath(input)
    val effectiveOutputFormat = WhisperSpeechToText.effectiveOutputFormat(outputFormat, options)

    // Snapshot which sibling output paths already exist, so cleanup below only removes transcript files
    // this invocation actually created and never touches a user's pre-existing files.
    val preExistingOutputs: Set[Path] = wavResult.toOption
      .map { case (path, _) => WhisperSpeechToText.existingGeneratedOutputs(path, effectiveOutputFormat) }
      .getOrElse(Set.empty)

    val result = for {
      wavAndTemp <- wavResult
      args = buildWhisperArgs(wavAndTemp._1, options)
      stdout <- Safety
        .fromTry(Try(args.!!))
        .left
        .map {
          case _: IOException => ProcessingError.audioValidation("Whisper CLI not found or IO error")
          case _: RuntimeException =>
            ProcessingError.audioValidation("Whisper CLI execution failed with non-zero exit code")
          case _ => ProcessingError.audioValidation("Whisper CLI execution failed")
        }
      output = WhisperSpeechToText.resolveCliOutput(wavAndTemp._1, effectiveOutputFormat, stdout)
      transcription <- {
        val processingTimeMs = System.currentTimeMillis() - startTime
        parseWhisperOutput(output, options).map(_.copy(processingTimeMs = Some(processingTimeMs)))
      }
    } yield transcription

    // Clean up regardless of transcription success or failure: the temp WAV (if we created one) and any
    // sibling transcript file Whisper newly wrote next to the input. The latter matters for FileAudio inputs,
    // where the input is a real user file and the generated <stem>.<format> would otherwise be left behind.
    // Files that already existed before the run (see preExistingOutputs) are preserved.
    wavResult.foreach { case (path, isTemp) =>
      if (isTemp) Try(Files.deleteIfExists(path))
      WhisperSpeechToText.deleteGeneratedOutputs(path, effectiveOutputFormat, preserve = preExistingOutputs)
    }

    result
  }

  private def inputToWavPath(input: AudioInput): Result[(Path, Boolean)] =
    input match {
      case AudioInput.FileAudio(path) => Right((path, false))
      case AudioInput.BytesAudio(bytes, _, _) =>
        WavFileGenerator.createTempWavFile("llm4s-whisper-").flatMap { tmp =>
          Safety
            .fromTry(Try(Files.write(tmp, bytes)))
            .map(_ => (tmp, true))
            .left
            .map(_ => ProcessingError.audioValidation("IO error writing bytes to temp WAV file"))
        }
      case AudioInput.StreamAudio(stream, _, _) =>
        WavFileGenerator.createTempWavFile("llm4s-whisper-").flatMap { tmp =>
          Safety
            .fromTry(Try(Files.write(tmp, stream.readAllBytes())))
            .map(_ => (tmp, true))
            .left
            .map(_ => ProcessingError.audioValidation("IO error writing stream to temp WAV file"))
        }
    }

  private def buildWhisperArgs(inputPath: Path, options: STTOptions): Seq[String] =
    WhisperSpeechToText.buildArgs(command, model, outputFormat, inputPath, options)

  private def parseWhisperOutput(
    output: String,
    options: STTOptions
  ): Result[Transcription] =
    WhisperSpeechToText.toTranscription(output, options)
}

object WhisperSpeechToText {
  import SttJsonSupport._

  final private[stt] case class ParsedOutput(
    text: String,
    language: Option[String],
    confidence: Option[Double],
    timestamps: List[WordTimestamp]
  )

  private[stt] def effectiveOutputFormat(
    configuredOutputFormat: String,
    options: STTOptions
  ): String =
    if (options.enableTimestamps) "json" else configuredOutputFormat

  private[stt] def buildArgs(
    command: Seq[String],
    model: String,
    configuredOutputFormat: String,
    inputPath: Path,
    options: STTOptions
  ): Seq[String] = {
    val effectiveFormat = effectiveOutputFormat(configuredOutputFormat, options)
    val baseArgs = command ++ Seq(
      inputPath.toString,
      "--model",
      model,
      "--output_format",
      effectiveFormat
    )

    // Flags follow the openai-whisper CLI dialect (underscored), matching the default `whisper` command:
    // --output_format / --language / --initial_prompt / --word_timestamps. openai-whisper's
    // --word_timestamps takes an explicit boolean value, hence the trailing "True".
    val optFlags = List(
      options.language.map(l => Seq("--language", l)),
      options.prompt.map(p => Seq("--initial_prompt", p)),
      if (options.enableTimestamps) Some(Seq("--word_timestamps", "True")) else None
    ).flatten

    baseArgs ++ optFlags.combineAll
  }

  private[stt] def parseOutput(
    output: String,
    options: STTOptions
  ): ParsedOutput =
    Try(ujson.read(output)).toOption match {
      case Some(json) =>
        val parsedWords   = extractWordTimestamps(json)
        val filteredWords = applyConfidenceThreshold(parsedWords, options.confidenceThreshold)
        val retainedWords = if (filteredWords.nonEmpty) filteredWords else parsedWords
        val text =
          if (filteredWords.nonEmpty) renderWords(filteredWords)
          else stringField(json, "text").getOrElse(renderWords(parsedWords)).trim

        ParsedOutput(
          text = text,
          language = stringField(json, "language"),
          confidence = extractConfidence(json, retainedWords),
          timestamps = retainedWords
        )
      case None =>
        ParsedOutput(
          text = output.trim,
          language = None,
          confidence = None,
          timestamps = Nil
        )
    }

  private[stt] def toTranscription(
    output: String,
    options: STTOptions
  ): Result[Transcription] = {
    val parsed = parseOutput(output, options)
    val text   = parsed.text.trim
    if (text.isEmpty) {
      Left(ProcessingError.audioValidation("Transcription produced empty text"))
    } else {
      Right(
        Transcription(
          text = text,
          language = parsed.language.orElse(options.language),
          confidence = parsed.confidence,
          timestamps = if (options.enableTimestamps) parsed.timestamps else Nil,
          meta = None
        )
      )
    }
  }

  private[stt] def resolveCliOutput(
    inputPath: Path,
    outputFormat: String,
    stdout: String
  ): String = {
    val candidates = outputPathCandidates(inputPath, outputFormat)
    candidates.collectFirst(Function.unlift(readIfExists)).getOrElse(stdout)
  }

  /** Candidate sibling output paths that already exist on disk before a run (so they can be preserved). */
  private[stt] def existingGeneratedOutputs(inputPath: Path, outputFormat: String): Set[Path] =
    outputPathCandidates(inputPath, outputFormat).filter(p => Files.exists(p)).toSet

  /**
   * Delete transcript files Whisper generated next to the input, so they don't litter the user's directory.
   * Paths in `preserve` (typically those that already existed before the run) are left untouched, so a user's
   * pre-existing transcript/metadata sidecar is never removed.
   */
  private[stt] def deleteGeneratedOutputs(
    inputPath: Path,
    outputFormat: String,
    preserve: Set[Path] = Set.empty
  ): Unit =
    outputPathCandidates(inputPath, outputFormat)
      .filterNot(preserve.contains)
      .foreach(p => Try(Files.deleteIfExists(p)))

  private def outputPathCandidates(inputPath: Path, outputFormat: String): List[Path] = {
    val fileName = inputPath.getFileName.toString
    val stem =
      if (fileName.contains(".")) fileName.substring(0, fileName.lastIndexOf('.'))
      else fileName

    List(
      inputPath.resolveSibling(s"$fileName.$outputFormat"),
      inputPath.resolveSibling(s"$stem.$outputFormat")
    ).distinct
  }

  private def readIfExists(path: Path): Option[String] =
    if (Files.exists(path)) Try(Files.readString(path)).toOption else None

  private def extractWordTimestamps(json: Value): List[WordTimestamp] = {
    val rootWords = arrayField(json, "words").flatMap(parseWord)
    if (rootWords.nonEmpty) {
      rootWords.toList
    } else {
      arrayField(json, "segments").flatMap(segment => arrayField(segment, "words").flatMap(parseWord)).toList
    }
  }

  private def parseWord(value: Value): Option[WordTimestamp] =
    for {
      word  <- stringField(value, "word").map(_.trim).filter(_.nonEmpty)
      start <- extractTimestamp(value, List("start", "start_sec", "from"))
      end   <- extractTimestamp(value, List("end", "end_sec", "to"))
      timestamp <- WordTimestamp
        .validate(
          word = word,
          startSec = start,
          endSec = end,
          confidence = extractConfidenceValue(value)
        )
        .toOption
    } yield timestamp

  private def extractTimestamp(value: Value, keys: List[String]): Option[Double] =
    keys.iterator.map(doubleField(value, _)).collectFirst { case Some(v) => v }

  private def extractConfidenceValue(value: Value): Option[Double] =
    List("confidence", "probability", "prob", "score").iterator
      .map(doubleField(value, _))
      .collectFirst { case Some(v) => v }

  private def extractConfidence(json: Value, words: Seq[WordTimestamp]): Option[Double] = {
    val wordConfidence = averageConfidence(words)
    wordConfidence.orElse {
      val segmentConfidence = arrayField(json, "segments").flatMap { segment =>
        List("confidence", "probability", "prob", "score").iterator
          .map(doubleField(segment, _))
          .collectFirst { case Some(v) => v }
      }
      if (segmentConfidence.nonEmpty) Some(segmentConfidence.sum / segmentConfidence.size) else None
    }
  }

  private def applyConfidenceThreshold(
    words: Seq[WordTimestamp],
    threshold: Double
  ): List[WordTimestamp] =
    if (threshold <= 0.0) words.toList else words.filter(_.meetsConfidence(threshold)).toList

}
