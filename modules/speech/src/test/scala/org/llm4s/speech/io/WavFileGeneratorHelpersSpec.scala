package org.llm4s.speech.io

import org.llm4s.speech.{ AudioFormat, AudioMeta }
import org.llm4s.speech.io.WavFileGenerator.WavGenerationFailed
import org.llm4s.speech.processing.AudioConsumers
import org.scalatest.{ BeforeAndAfterEach, EitherValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{ Files, Path }
import javax.sound.sampled.{ AudioFileFormat, AudioFormat => JavaAudioFormat, AudioSystem }
import scala.jdk.CollectionConverters.*

class WavFileGeneratorHelpersSpec extends AnyFlatSpec with Matchers with EitherValues with BeforeAndAfterEach {

  private val meta          = AudioMeta(16000, 1, 16)
  private val bytes         = Array.tabulate[Byte](3200)(i => (i % 128).toByte)
  private var tempDir: Path = _
  private var externalFiles = List.empty[Path]

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    tempDir = Files.createTempDirectory("wav-helpers")
    externalFiles = Nil
  }

  override protected def afterEach(): Unit =
    try {
      externalFiles.foreach(Files.deleteIfExists)
      if (tempDir != null) {
        val files = Files.list(tempDir)
        try files.iterator().asScala.foreach(Files.deleteIfExists)
        finally files.close()
        Files.deleteIfExists(tempDir)
      }
    } finally super.afterEach()

  "createJavaAudioFormat" should "describe signed little-endian mono PCM" in {
    val format = WavFileGenerator.createJavaAudioFormat(meta)
    format.getSampleRate shouldBe 16000f
    format.getFrameRate shouldBe 16000f
    format.getSampleSizeInBits shouldBe 16
    format.getChannels shouldBe 1
    format.getFrameSize shouldBe 2
    format.isBigEndian shouldBe false
    format.getEncoding shouldBe JavaAudioFormat.Encoding.PCM_SIGNED
  }

  "createWavFromBytes" should "retain the PCM data and metadata with the WAV format" in {
    val audio = WavFileGenerator.createWavFromBytes(bytes, meta).value
    audio.data shouldBe bytes
    audio.meta shouldBe meta
    audio.format shouldBe AudioFormat.WavPcm16
  }

  for (
    (invalidMeta, message) <- Seq(
      AudioMeta(0, 1, 16)     -> "Sample rate must be > 0, got: 0",
      AudioMeta(16000, 9, 16) -> "Number of channels must be between 1 and 8, got: 9",
      AudioMeta(16000, 1, 12) -> "Bit depth must be one of 8, 16, 24, 32, got: 12"
    )
  )
    it should s"reject invalid metadata: $invalidMeta" in {
      WavFileGenerator.createWavFromBytes(bytes, invalidMeta).left.value shouldBe WavGenerationFailed(message)
    }

  "saveRawPcmAsWav" should "write a readable WAVE file containing all PCM frames" in {
    val path = tempDir.resolve("raw.wav")
    WavFileGenerator.saveRawPcmAsWav(bytes, meta, path) shouldBe Right(path)
    assertReadableWav(path)
    val stream = AudioSystem.getAudioInputStream(path.toFile)
    try stream.readAllBytes() shouldBe bytes
    finally stream.close()
  }

  it should "reject invalid metadata before creating or overwriting a destination" in {
    val missing  = tempDir.resolve("missing.wav")
    val existing = tempDir.resolve("existing.wav")
    val original = Array[Byte](1, 2, 3)
    Files.write(existing, original)
    for (path <- Seq(missing, existing))
      WavFileGenerator.saveRawPcmAsWav(bytes, AudioMeta(16000, 1, 12), path).isLeft shouldBe true
    Files.exists(missing) shouldBe false
    Files.readAllBytes(existing) shouldBe original
  }

  "createTempWavFile" should "create an existing file with the requested prefix and WAV suffix" in {
    val path = WavFileGenerator.createTempWavFile("pfx").value
    externalFiles ::= path
    Files.exists(path) shouldBe true
    path.getFileName.toString should startWith("pfx")
    path.getFileName.toString should endWith(".wav")
  }

  "managedTempWavFile" should "remove the file after successful use" in {
    val result = WavFileGenerator.managedTempWavFile("pfx").use { path =>
      externalFiles ::= path
      Files.exists(path) shouldBe true
      Right(path)
    }
    Files.exists(result.value) shouldBe false
  }

  it should "remove the file when the computation returns an error" in {
    val error = WavGenerationFailed("test failure")
    val result = WavFileGenerator.managedTempWavFile("pfx").use { path =>
      externalFiles ::= path
      Files.exists(path) shouldBe true
      Left(error)
    }
    result shouldBe Left(error)
    externalFiles.foreach(path => Files.exists(path) shouldBe false)
  }

  "FileConsumer" should "write a readable WAV and identify its destination" in {
    val path     = tempDir.resolve("consumer.wav")
    val consumer = AudioConsumers.FileConsumer(path)
    consumer.name shouldBe s"file-consumer($path)"
    consumer.consume((bytes, meta)) shouldBe Right(())
    assertReadableWav(path)
  }

  it should "reject invalid metadata without creating a file" in {
    val path     = tempDir.resolve("invalid.wav")
    val consumer = AudioConsumers.FileConsumer(path)
    consumer.consume((bytes, AudioMeta(0, 1, 16))).isLeft shouldBe true
    Files.exists(path) shouldBe false
  }

  private def assertReadableWav(path: Path): Unit = {
    val fileFormat = AudioSystem.getAudioFileFormat(path.toFile)
    fileFormat.getType shouldBe AudioFileFormat.Type.WAVE
    fileFormat.getFrameLength shouldBe 1600
    fileFormat.getFormat.getSampleRate shouldBe 16000f
    fileFormat.getFormat.getChannels shouldBe 1
    fileFormat.getFormat.getSampleSizeInBits shouldBe 16
  }
}
