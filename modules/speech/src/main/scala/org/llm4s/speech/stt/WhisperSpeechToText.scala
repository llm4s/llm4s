package org.llm4s.speech.stt

import org.llm4s.types.Result
import org.llm4s.speech.{ AudioInput, CloudSpeechSupport }
import org.llm4s.error.Safety
import org.llm4s.error.{ CancelledError, ProcessingError }
import org.llm4s.speech.util.CommandRuns
import cats.implicits._

import java.nio.file.{ Files, Path }
import java.io.IOException
import java.util.Comparator
import scala.jdk.CollectionConverters._
import scala.util.{ Try, Using }
import scala.sys.process._
import ujson.Value

/**
 * Speech-to-text through the openai-whisper command-line program (`pip install openai-whisper`); the
 * flags are openai-whisper's (`--output_dir`, `--output_format`, `--initial_prompt`, ...), so
 * whisper.cpp's CLI is not supported.
 *
 * Each call runs the program with `--output_dir` set to a fresh temporary directory and `--verbose
 * False`, reads the transcript from that directory and deletes it: nothing is written into the
 * working directory or beside the input file. `outputFormat` is `txt` or `json` (`json` whenever word
 * timestamps are requested); subtitle formats are refused. A language tag is reduced to its primary
 * language (`en-US` becomes `en`), which is what the program accepts. A failed run reports the exit code
 * and the end of the program's stderr.
 */
final class WhisperSpeechToText(
  command: Seq[String] = Seq("whisper"),
  model: String = "base",
  outputFormat: String = "txt"
) extends SpeechToText {
  override val name: String = "whisper-cli"

  override val supportedFormats: List[String] = List("audio/wav", "audio/mp3", "audio/m4a", "audio/flac", "audio/ogg")

  override def transcribe(input: AudioInput, options: STTOptions): Result[Transcription] = {
    val startTime = System.nanoTime()
    val format    = WhisperSpeechToText.effectiveOutputFormat(outputFormat, options)
    for {
      _       <- WhisperSpeechToText.checkOutputFormat(format)
      workDir <- WhisperSpeechToText.createWorkDir()
      // The temporary input and the program's output both live under workDir, which is always removed.
      transcription = {
        val outcome = transcribeIn(workDir, input, options, format, startTime)
        WhisperSpeechToText.deleteRecursively(workDir)
        outcome
      }
      result <- transcription
    } yield result
  }

  private def transcribeIn(
    workDir: Path,
    input: AudioInput,
    options: STTOptions,
    format: String,
    startTime: Long
  ): Result[Transcription] =
    for {
      inputPath <- inputToPath(workDir, input)
      outputDir <- Safety
        .fromTry(Try(Files.createDirectory(workDir.resolve("out"))))
        .left
        .map(_ => ProcessingError.audioValidation("IO error creating the Whisper output directory"))
      args = WhisperSpeechToText.buildArgs(command, model, outputFormat, inputPath, outputDir, options)
      completed <- CommandRuns
        .withStderrTail(args)
        .left
        .map {
          // An interrupt stops the program and is a cancellation, not a failed run (design section 4.4).
          case interrupted: InterruptedException => CancelledError("whisper.transcribe", Some(interrupted))
          case e: IOException =>
            ProcessingError.audioValidation(s"Whisper CLI not found or could not be started: ${e.getMessage}")
          case e => ProcessingError.audioValidation(s"Whisper CLI execution failed: ${e.getMessage}")
        }
      _ <-
        if (completed.exitCode == 0) Right(())
        else
          Left(
            ProcessingError.audioValidation(
              s"Whisper CLI exited with code ${completed.exitCode}" + WhisperSpeechToText.stderrSuffix(completed)
            )
          )
      output <- WhisperSpeechToText
        .readCliOutput(outputDir, inputPath, format)
        .toRight(
          ProcessingError.audioValidation(
            s"Whisper CLI wrote no .$format transcript to its --output_dir" + WhisperSpeechToText.stderrSuffix(
              completed
            )
          )
        )
      transcription <- {
        val processingTime = scala.concurrent.duration.Duration.fromNanos(System.nanoTime() - startTime)
        WhisperSpeechToText.toTranscription(output, options).map(_.copy(processingTime = Some(processingTime)))
      }
    } yield transcription

  private def inputToPath(workDir: Path, input: AudioInput): Result[Path] =
    input match {
      case AudioInput.FileAudio(path) => Right(path)
      case AudioInput.BytesAudio(bytes, _, _) =>
        writeInput(workDir, Files.write(workDir.resolve("input.wav"), bytes), "bytes")
      case AudioInput.StreamAudio(stream, _, _) =>
        writeInput(workDir, Files.write(workDir.resolve("input.wav"), stream.readAllBytes()), "stream")
    }

  private def writeInput(workDir: Path, write: => Path, what: String): Result[Path] =
    Safety
      .fromTry(Try(write))
      .left
      .map(_ => ProcessingError.audioValidation(s"IO error writing $what to temp WAV file in $workDir"))
}

object WhisperSpeechToText {
  import SttJsonSupport._

  /** The output formats whose content is a transcript: subtitle formats carry cue numbers and times. */
  private[stt] val SupportedOutputFormats: Set[String] = Set("txt", "json")

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

  private[stt] def checkOutputFormat(format: String): Result[Unit] =
    if (SupportedOutputFormats.contains(format)) Right(())
    else
      Left(
        STTError.InvalidInput(
          s"Whisper output format '$format' is not supported: use ${SupportedOutputFormats.toList.sorted.mkString(" or ")}"
        )
      )

  private[stt] def buildArgs(
    command: Seq[String],
    model: String,
    configuredOutputFormat: String,
    inputPath: Path,
    outputDir: Path,
    options: STTOptions
  ): Seq[String] = {
    val effectiveFormat = effectiveOutputFormat(configuredOutputFormat, options)
    val baseArgs = command ++ Seq(
      inputPath.toString,
      "--model",
      model,
      "--output_format",
      effectiveFormat,
      "--output_dir",
      outputDir.toString,
      // openai-whisper prints progress and every segment to stdout unless told not to.
      "--verbose",
      "False"
    )

    // Flags follow the openai-whisper CLI dialect (underscored), matching the default `whisper` command:
    // --output_format / --language / --initial_prompt / --word_timestamps. openai-whisper's
    // --word_timestamps takes an explicit boolean value, hence the trailing "True". Its --language
    // accepts a primary language only, so `en-US` is passed as `en`, as the OpenAI client does.
    val optFlags = List(
      options.language.map(l => Seq("--language", CloudSpeechSupport.primaryLanguage(l))),
      options.prompt.map(p => Seq("--initial_prompt", p)),
      if (options.enableTimestamps) Some(Seq("--word_timestamps", "True")) else None
    ).flatten

    baseArgs ++ optFlags.combineAll
  }

  /**
   * The transcript the program wrote into `outputDir`: `<input stem>.<format>`, which is how
   * openai-whisper names it, else the only `.<format>` file there. `outputDir` is private to one call,
   * so nothing else can have written into it.
   */
  private[stt] def readCliOutput(outputDir: Path, inputPath: Path, format: String): Option[String] = {
    val fileName = inputPath.getFileName.toString
    val stem     = if (fileName.contains(".")) fileName.substring(0, fileName.lastIndexOf('.')) else fileName
    val expected = outputDir.resolve(s"$stem.$format")
    def onlyFileOfFormat: Option[Path] =
      Try(Using.resource(Files.list(outputDir))(_.iterator().asScala.toList)).toOption
        .map(_.filter(p => Files.isRegularFile(p) && p.getFileName.toString.endsWith(s".$format")))
        .collect { case single :: Nil => single }
    Option
      .when(Files.isRegularFile(expected))(expected)
      .orElse(onlyFileOfFormat)
      .flatMap(p => Try(Files.readString(p)).toOption)
  }

  private[stt] def stderrSuffix(completed: CommandRuns.Completed): String =
    if (completed.stderrTail.trim.isEmpty) "" else s"; stderr: ${completed.stderrTail.trim}"

  private[stt] def createWorkDir(): Result[Path] =
    Safety
      .fromTry(Try(Files.createTempDirectory("llm4s-whisper-")))
      .left
      .map(_ => ProcessingError.audioValidation("IO error creating a temporary directory for Whisper"))

  /** Deletes `dir` and everything under it, best effort. */
  private[stt] def deleteRecursively(dir: Path): Unit =
    Try(Using.resource(Files.walk(dir))(_.sorted(Comparator.reverseOrder[Path]()).iterator().asScala.toList))
      .foreach(_.foreach(p => Try(Files.deleteIfExists(p))))

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
