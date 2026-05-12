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
    val startTime = System.currentTimeMillis()
    val wavResult = inputToWavPath(input)

    val result = for {
      wavAndTemp <- wavResult
      effectiveOutputFormat = WhisperSpeechToText.effectiveOutputFormat(outputFormat, options)
      args                  = buildWhisperArgs(wavAndTemp._1, options)
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

    // Clean up any temp file that was created, regardless of transcription success or failure
    wavResult.foreach { case (path, isTemp) => if (isTemp) Try(Files.deleteIfExists(path)) }

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

    val optFlags = List(
      options.language.map(l => Seq("--language", l)),
      options.prompt.map(p => Seq("--initial_prompt", p)),
      if (options.enableTimestamps) Some(Seq("--word-timestamps")) else None
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

  private def averageConfidence(words: Seq[WordTimestamp]): Option[Double] = {
    val confidences = words.flatMap(_.confidence)
    if (confidences.nonEmpty) Some(confidences.sum / confidences.size) else None
  }

  private def renderWords(words: Seq[WordTimestamp]): String =
    words.map(_.word.trim).filter(_.nonEmpty).mkString(" ").trim

  private def field(value: Value, key: String): Option[Value] =
    Try(value(key)).toOption

  private def stringField(value: Value, key: String): Option[String] =
    field(value, key).flatMap(v => Try(v.str).toOption)

  private def doubleField(value: Value, key: String): Option[Double] =
    field(value, key).flatMap(v => Try(v.num).toOption)

  private def arrayField(value: Value, key: String): Seq[Value] =
    field(value, key).flatMap(v => Try(v.arr.toSeq).toOption).getOrElse(Seq.empty)
}
