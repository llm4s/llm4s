package org.llm4s.samples

import org.llm4s.speech.AudioInput
import org.llm4s.speech.stt.{ STTOptions, VoskSpeechToText, WhisperSpeechToText }
import org.llm4s.speech.tts.{ TTSOptions, Tacotron2TextToSpeech }
import org.llm4s.speech.util.PlatformCommands
import org.slf4j.LoggerFactory

import java.io.{ DataOutputStream, FileOutputStream }
import java.nio.file.{ Files, Path, Paths }
import scala.util.chaining.scalaUtilChainingOps
import scala.util.{ Try, Using }

object SpeechSamples {

  private val logger = LoggerFactory.getLogger(getClass)

  class RichDataOutputStream(val dos: DataOutputStream) {
    def writeString(s: String): Try[Unit] = Try(dos.writeBytes(s)).tap { x =>
      x.fold(
        ex => logger.error("Failed to write string to audio file: {}", ex.getMessage),
        _ => logger.debug("Successfully wrote string data to audio file")
      )
    }

    def writeInt(i: Int): Try[Unit] = Try(writeLittleEndianInt(dos, i)).tap { x =>
      x.fold(
        ex => logger.error("Failed to write integer data to audio file: {}", ex.getMessage),
        _ => logger.debug("Successfully wrote integer value {} to audio file", i)
      )
    }

    def writeShort(sh: Short): Try[Unit] = Try(writeLittleEndianShort(dos, sh)).tap { x =>
      x.fold(
        ex => logger.error("Failed to write short data to audio file: {}", ex.getMessage),
        _ => logger.debug("Successfully wrote short value {} to audio file", sh)
      )
    }

    def writeListOfValues(lazyList: LazyList[Int]): Try[Unit] = Try {
      lazyList.foreach(value => writeLittleEndianShort(dos, value))
    }.tap { x =>
      x.fold(
        ex => logger.error("Failed to write audio data samples: {}", ex.getMessage),
        _ => logger.debug("Successfully wrote audio data samples to file")
      )
    }
  }

  private def makePath(filename: String, fileExt: String): Try[Path] = Try {
    Files.createTempFile(filename, fileExt)
  }

  def createTestWavFile(): Try[java.nio.file.Path] = {
    val sampleRate     = 8000
    val channels       = 1
    val bitsPerSample  = 16
    val bytesPerSample = bitsPerSample / 8
    val blockAlign     = channels * bytesPerSample
    val byteRate       = sampleRate * blockAlign
    val dataSize       = sampleRate * channels * bytesPerSample
    val fileSize       = 36 + dataSize

    for {
      path <- makePath("whisper-test", ".wav")
      _ <- Using.Manager { use =>
        val fos     = use(new FileOutputStream(path.toFile))
        val dos     = use(new DataOutputStream(fos))
        val richDos = new RichDataOutputStream(dos)

        val writeFileTry = for {
          _ <- richDos.writeString("RIFF")
          _ <- richDos.writeInt(fileSize)
          _ <- richDos.writeString("WAVE")
          _ <- richDos.writeString("fmt ")
          _ <- richDos.writeInt(16)
          _ <- richDos.writeShort(1.toShort)
          _ <- richDos.writeShort(channels.toShort)
          _ <- richDos.writeInt(sampleRate)
          _ <- richDos.writeInt(byteRate)
          _ <- richDos.writeShort(blockAlign.toShort)
          _ <- richDos.writeShort(bitsPerSample.toShort)
          _ <- richDos.writeString("data")
          _ <- richDos.writeInt(dataSize)
          _ <- richDos.writeListOfValues(LazyList.continually(0).take(sampleRate))
        } yield ()
        writeFileTry.get // make this Try throw on failure, so the outer Try fails as well
      }
    } yield path
  }

  def signToneValuesGenerator(
    sampleRate: Int,
    duration: Int,
    frequency: Double = 440.0,
    amplitude: Double = 0.3
  ): LazyList[Int] = {
    require(sampleRate > 0, "Sample rate must be positive")
    require(duration >= 0, "Duration must be non-negative")
    require(amplitude >= 0.0 && amplitude <= 1.0, "Amplitude must be between 0.0 and 1.0")

    val totalSamples = sampleRate * duration
    val maxValue     = 32767.0 // 16-bit signed integer maximum

    LazyList.range(0, totalSamples).map { sampleIndex =>
      val timePosition = sampleIndex.toDouble / sampleRate
      val sineValue    = Math.sin(2 * Math.PI * frequency * timePosition)
      (sineValue * amplitude * maxValue).toInt
    }
  }

  def createToneWavFile(): Try[java.nio.file.Path] = {
    val sampleRate     = 16000 // Higher sample rate for better quality
    val channels       = 1
    val bitsPerSample  = 16
    val bytesPerSample = bitsPerSample / 8
    val blockAlign     = channels * bytesPerSample
    val byteRate       = sampleRate * blockAlign
    val duration       = 2     // 2 seconds
    val dataSize       = sampleRate * duration * channels * bytesPerSample
    val fileSize       = 36 + dataSize

    for {
      path <- makePath("whisper-test", ".wav")
      _ <- Using.Manager { use =>
        val fos     = use(new FileOutputStream(path.toFile))
        val dos     = use(new DataOutputStream(fos))
        val richDos = new RichDataOutputStream(dos)

        val writeFileTry = for {
          _ <- richDos.writeString("RIFF")
          _ <- richDos.writeInt(fileSize)
          _ <- richDos.writeString("WAVE")
          _ <- richDos.writeString("fmt ")
          _ <- richDos.writeInt(16)
          _ <- richDos.writeShort(1.toShort)
          _ <- richDos.writeShort(channels.toShort)
          _ <- richDos.writeInt(sampleRate)
          _ <- richDos.writeInt(byteRate)
          _ <- richDos.writeShort(blockAlign.toShort)
          _ <- richDos.writeShort(bitsPerSample.toShort)
          _ <- richDos.writeString("data")
          _ <- richDos.writeInt(dataSize)
          _ <- richDos.writeListOfValues(signToneValuesGenerator(sampleRate, duration))
        } yield ()
        writeFileTry.get // make this Try throw on failure, so the outer Try fails as well
      }
    } yield path
  }

  def speechLikeWavValuesGenerator(
    sampleRate: Int,
    duration: Int,
  ): LazyList[Int] = {
    require(sampleRate > 0, "Sample rate must be positive")
    require(duration >= 0, "Duration must be non-negative")

    val totalSamples = sampleRate * duration
    val baseFreq     = 200.0 // Lower frequency, more speech-like
    val amplitude    = 0.2

    LazyList.range(0, totalSamples).map { sampleIndex =>
      val timePosition = sampleIndex.toDouble / sampleRate
      // Create a modulated pattern with varying frequency
      val modFreq = baseFreq + 50 * Math.sin(2 * Math.PI * 2 * timePosition) // Frequency modulation
      (Math.sin(2 * Math.PI * modFreq * timePosition) * amplitude * 32767).toInt
    }
  }

  def createSpeechLikeWavFile(): Try[java.nio.file.Path] = {
    val sampleRate     = 16000 // Higher sample rate for better quality
    val channels       = 1
    val bitsPerSample  = 16
    val bytesPerSample = bitsPerSample / 8
    val blockAlign     = channels * bytesPerSample
    val byteRate       = sampleRate * blockAlign
    val duration       = 2     // 2 seconds
    val dataSize       = sampleRate * duration * channels * bytesPerSample
    val fileSize       = 36 + dataSize

    for {
      path <- makePath("whisper-speech", ".wav")
      _ <- Using.Manager { use =>
        val fos     = use(new FileOutputStream(path.toFile))
        val dos     = use(new DataOutputStream(fos))
        val richDos = new RichDataOutputStream(dos)

        val writeFileTry = for {
          // WAV header
          _ <- richDos.writeString("RIFF")
          _ <- richDos.writeInt(fileSize)
          _ <- richDos.writeString("WAVE")
          // fmt chunk
          _ <- richDos.writeString("fmt ")
          _ <- richDos.writeInt(16)
          _ <- richDos.writeShort(1.toShort)
          _ <- richDos.writeShort(channels.toShort)
          _ <- richDos.writeInt(sampleRate)
          _ <- richDos.writeInt(byteRate)
          _ <- richDos.writeShort(blockAlign.toShort)
          _ <- richDos.writeShort(bitsPerSample.toShort)
          // data chunk
          _ <- richDos.writeString("data")
          _ <- richDos.writeInt(dataSize)
          _ <- richDos.writeListOfValues(speechLikeWavValuesGenerator(sampleRate, duration))
        } yield ()
        writeFileTry.get // make this Try throw on failure, so the outer Try fails as well
      }
    } yield path
  }

  private def writeLittleEndianInt(dos: DataOutputStream, value: Int): Unit = {
    dos.writeByte(value & 0xff)
    dos.writeByte((value >> 8) & 0xff)
    dos.writeByte((value >> 16) & 0xff)
    dos.writeByte((value >> 24) & 0xff)
  }

  private def writeLittleEndianShort(dos: DataOutputStream, value: Int): Unit = {
    dos.writeByte(value & 0xff)
    dos.writeByte((value >> 8) & 0xff)
  }

  def demoWhisper(filePath: String): Unit = {
    logger.info("Testing Whisper STT on {}", PlatformCommands.platformName)
    val stt = new WhisperSpeechToText(PlatformCommands.cat) // Cross-platform file content
    val res = stt.transcribe(AudioInput.FileAudio(Paths.get(filePath)), STTOptions(language = Some("en")))
    logger.info("{}", res.fold(err => s"STT error: ${err.formatted}", t => s"Transcript: ${t.text}"))
  }

  def demoWhisperReal(filePath: String): Unit = {
    logger.info("Testing REAL Whisper STT on {}", PlatformCommands.platformName)
    // Use actual Whisper CLI if available
    val stt = new WhisperSpeechToText(Seq("whisper"), model = "base")
    val res = stt.transcribe(AudioInput.FileAudio(Paths.get(filePath)), STTOptions(language = Some("en")))
    logger.info("{}", res.fold(err => s"STT error: ${err.formatted}", t => s"Transcript: ${t.text}"))
  }

  def demoVosk(filePath: String): Unit = {
    logger.info("Testing Vosk STT on {}", PlatformCommands.platformName)
    val stt = new VoskSpeechToText()
    val res = stt.transcribe(AudioInput.FileAudio(Paths.get(filePath)), STTOptions(language = Some("en")))
    logger.info("{}", res.fold(err => s"STT error: ${err.formatted}", t => s"Transcript: ${t.text}"))
  }

  def demoTacotron2(text: String): Unit = {
    logger.info("Testing Tacotron2 TTS on {}", PlatformCommands.platformName)
    val tts = new Tacotron2TextToSpeech(PlatformCommands.echo) // Cross-platform echo
    val res = tts.synthesize(text, TTSOptions(voice = Some("default")))
    logger.info("{}", res.fold(err => s"TTS error: ${err.formatted}", _ => s"TTS synthesis completed."))
  }

  def main(args: Array[String]): Unit = {
    logger.info("=== LLM4S Speech Demo ===")
    logger.info("Platform: {}", PlatformCommands.platformName)

    logger.info("--- Creating Test Audio Files ---")
    // Create all test files
    val result: Try[Unit] = for {
      silenceWavFile    <- createTestWavFile()
      toneWavFile       <- createToneWavFile()
      speechLikeWavFile <- createSpeechLikeWavFile()
    } yield {
      logger.info(
        s"""
           |Created test audio files:
           |  Silence WAV: ${silenceWavFile} (${Files.size(silenceWavFile)} bytes)
           |  Tone WAV: ${toneWavFile} (${Files.size(toneWavFile)} bytes)
           |  Speech-like WAV: ${speechLikeWavFile} (${Files.size(speechLikeWavFile)} bytes)
           |""".stripMargin
      )

      logger.info("--- Testing STT (Mock) ---")
      demoWhisper(silenceWavFile.toString)
      demoVosk(silenceWavFile.toString)

      logger.info("--- Testing STT (Real Whisper - Silence) ---")
      logger.info("Note: This requires 'whisper' CLI to be installed (pip install openai-whisper)")
      demoWhisperReal(silenceWavFile.toString)

      logger.info("--- Testing STT (Real Whisper - Tone) ---")
      logger.info("Testing with a 440Hz tone (A note) - should be more interesting for Whisper")
      demoWhisperReal(toneWavFile.toString)

      logger.info("--- Testing STT (Real Whisper - Speech-like) ---")
      logger.info(
        "Testing with a speech-like modulated tone (200Hz base + 50Hz mod) - should be more interesting for Whisper"
      )
      demoWhisperReal(speechLikeWavFile.toString)

      logger.info("--- Testing TTS ---")
      demoTacotron2("Hello from LLM4S")
      // Cleanup
      Files.deleteIfExists(silenceWavFile)
      Files.deleteIfExists(toneWavFile)
      Files.deleteIfExists(speechLikeWavFile)
      logger.info("=== Demo Complete ===")
    }
    result.tap(x => x.fold(ex => logger.error("Error '{}'", ex.getMessage), _ => logger.trace("Ran successfully")))
  }
}
