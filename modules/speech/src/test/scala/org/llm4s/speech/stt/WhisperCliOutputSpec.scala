package org.llm4s.speech.stt

import org.llm4s.error.ProcessingError
import org.llm4s.speech.AudioInput
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path, Paths }
import scala.jdk.CollectionConverters._
import scala.util.Using

/**
 * The Whisper engine against [[FakeWhisper]] (#3138): the transcript comes from a private `--output_dir`,
 * never from stdout, the working directory or the input's directory; a BCP 47 tag is passed as its
 * primary language; a failed run says why; and subtitle formats are refused.
 */
class WhisperCliOutputSpec extends AnyFlatSpec with Matchers {

  private def withTempDir[A](f: Path => A): A = {
    val dir = Files.createTempDirectory("whisper-cli-spec-")
    try f(dir)
    finally
      Using.resource(Files.walk(dir))(_.iterator().asScala.toList.reverse.foreach(p => Files.deleteIfExists(p)))
  }

  private def listing(dir: Path): Set[String] =
    Using.resource(Files.list(dir))(_.iterator().asScala.map(_.getFileName.toString).toSet)

  private def recordedArgs(record: Path): List[String] =
    Files.readAllLines(record, StandardCharsets.UTF_8).asScala.toList

  private def flag(args: List[String], name: String): Option[String] =
    args.sliding(2).collectFirst { case List(`name`, value) => value }

  private val bytes = AudioInput.BytesAudio(Array.fill[Byte](64)(1), 16000, 1)

  "WhisperSpeechToText" should "read the transcript from a private output directory, not stdout" in withTempDir { dir =>
    val record = dir.resolve("args.txt")
    val result = new WhisperSpeechToText(FakeWhisper.command(record = Some(record))).transcribe(bytes, STTOptions())

    result.map(_.text) shouldBe Right("Hello from the fake")
    val args      = recordedArgs(record)
    val outputDir = Paths.get(flag(args, "--output_dir").get)
    flag(args, "--verbose") shouldBe Some("False")
    outputDir.toAbsolutePath should not be Paths.get("").toAbsolutePath
    Files.exists(outputDir) shouldBe false // removed with the temporary input after the call
  }

  it should "leave the input's directory and a same-named user file untouched for a FileAudio input" in withTempDir {
    dir =>
      val audio    = Files.write(dir.resolve("talk.wav"), Array.fill[Byte](64)(1))
      val userFile = Files.writeString(dir.resolve("talk.txt"), "the user's own notes")
      val before   = listing(dir)

      val result = new WhisperSpeechToText(FakeWhisper.command()).transcribe(AudioInput.FileAudio(audio), STTOptions())

      result.map(_.text) shouldBe Right("Hello from the fake")
      listing(dir) shouldBe before
      Files.readString(userFile) shouldBe "the user's own notes"
  }

  it should "leave no transcript in the working directory for bytes and stream inputs" in {
    val cwd    = Paths.get("").toAbsolutePath
    val before = listing(cwd)
    val engine = new WhisperSpeechToText(FakeWhisper.command())

    engine.transcribe(bytes, STTOptions()).isRight shouldBe true
    engine
      .transcribe(AudioInput.StreamAudio(new ByteArrayInputStream(Array.fill[Byte](64)(1)), 16000, 1), STTOptions())
      .isRight shouldBe true
    listing(cwd) shouldBe before
  }

  it should "pass a BCP 47 tag as its primary language and report the tag back" in withTempDir { dir =>
    val record = dir.resolve("args.txt")
    val result = new WhisperSpeechToText(FakeWhisper.command(record = Some(record)))
      .transcribe(bytes, STTOptions(language = Some("en-US")))

    result.map(_.text) shouldBe Right("Hello from the fake")
    result.map(_.language) shouldBe Right(Some("en-US"))
    flag(recordedArgs(record), "--language") shouldBe Some("en")
  }

  it should "read word timestamps from the JSON file when timestamps are requested" in {
    val result = new WhisperSpeechToText(FakeWhisper.command()).transcribe(bytes, STTOptions(enableTimestamps = true))

    val transcription = result.toOption.get
    transcription.text shouldBe "Hello world"
    transcription.language shouldBe Some("en")
    transcription.timestamps.map(_.word) shouldBe List("Hello", "world")
  }

  it should "report the exit code and the end of stderr when the program fails" in {
    val result = new WhisperSpeechToText(FakeWhisper.command(mode = "fail")).transcribe(bytes, STTOptions())

    result.isLeft shouldBe true
    val error = result.swap.toOption.get
    error shouldBe a[ProcessingError]
    error.message should include("exited with code 3")
    error.message should include("the fake model exploded")
  }

  it should "fail, not return stdout, when the program writes no transcript" in {
    val result = new WhisperSpeechToText(FakeWhisper.command(mode = "no-output")).transcribe(bytes, STTOptions())

    result.isLeft shouldBe true
    result.swap.toOption.get.message should include("wrote no .txt transcript")
  }

  it should "refuse a subtitle output format before running the program" in withTempDir { dir =>
    val record = dir.resolve("args.txt")
    List("srt", "vtt", "tsv", "all").foreach { format =>
      val result = new WhisperSpeechToText(FakeWhisper.command(record = Some(record)), outputFormat = format)
        .transcribe(bytes, STTOptions())
      result.swap.toOption.get shouldBe a[STTError.InvalidInput]
    }
    Files.exists(record) shouldBe false
  }

  it should "say what failed when the program cannot be started" in {
    val result = new WhisperSpeechToText(Seq("llm4s-no-such-whisper-program")).transcribe(bytes, STTOptions())

    result.swap.toOption.get.message should include("Whisper CLI not found")
  }
}
