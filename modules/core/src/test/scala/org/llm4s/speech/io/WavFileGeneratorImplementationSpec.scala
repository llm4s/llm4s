package org.llm4s.speech.io

import org.llm4s.speech.AudioMeta
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.nio.file.{ Files, Paths }

/**
 * Tests targeting uncovered code paths in WavFileGenerator implementation.
 * Focuses on: validation logic, file I/O, format creation, and error handling.
 */
class WavFileGeneratorImplementationSpec extends AnyFlatSpec with Matchers {

  // ===== Validation Execute Paths =====
  "validateMetadata" should "accept valid range values" in {
    val validCases = List(
      (8000, 1, 8),
      (16000, 1, 16),
      (44100, 2, 16),
      (48000, 2, 24),
      (96000, 2, 32),
      (192000, 1, 32)
    )

    validCases.foreach { case (sr, ch, bd) =>
      val meta   = AudioMeta(sampleRate = sr, numChannels = ch, bitDepth = bd)
      val result = WavFileGenerator.validateMetadata(meta)
      result shouldBe Right(meta)
    }
  }

  it should "reject sample rates below minimum" in {
    val meta   = AudioMeta(sampleRate = 7999, numChannels = 1, bitDepth = 16)
    val result = WavFileGenerator.validateMetadata(meta)
    result.isLeft shouldBe true
  }

  it should "reject sample rates above maximum" in {
    val meta   = AudioMeta(sampleRate = 192001, numChannels = 1, bitDepth = 16)
    val result = WavFileGenerator.validateMetadata(meta)
    result.isLeft shouldBe true
  }

  it should "reject unsupported bit depths" in {
    val unsupportedBitDepths = List(1, 4, 7, 12, 20, 28, 31, 33, 48)
    unsupportedBitDepths.foreach { bd =>
      val meta   = AudioMeta(sampleRate = 44100, numChannels = 2, bitDepth = bd)
      val result = WavFileGenerator.validateMetadata(meta)
      result.isLeft shouldBe true
    }
  }

  it should "accept all valid bit depths (8, 16, 24, 32)" in {
    List(8, 16, 24, 32).foreach { bd =>
      val meta   = AudioMeta(sampleRate = 44100, numChannels = 1, bitDepth = bd)
      val result = WavFileGenerator.validateMetadata(meta)
      result shouldBe Right(meta)
    }
  }

  it should "reject zero channels" in {
    val meta   = AudioMeta(sampleRate = 44100, numChannels = 0, bitDepth = 16)
    val result = WavFileGenerator.validateMetadata(meta)
    result.isLeft shouldBe true
  }

  it should "reject negative channels" in {
    val meta   = AudioMeta(sampleRate = 44100, numChannels = -1, bitDepth = 16)
    val result = WavFileGenerator.validateMetadata(meta)
    result.isLeft shouldBe true
  }

  it should "reject channels above maximum (8)" in {
    val meta   = AudioMeta(sampleRate = 44100, numChannels = 9, bitDepth = 16)
    val result = WavFileGenerator.validateMetadata(meta)
    result.isLeft shouldBe true
  }

  // ===== Boundary Testing =====
  "Boundary values" should "be accepted at lower limits" in {
    val meta   = AudioMeta(sampleRate = 8000, numChannels = 1, bitDepth = 8)
    val result = WavFileGenerator.validateMetadata(meta)
    result shouldBe Right(meta)
  }

  it should "be accepted at upper limits" in {
    val meta   = AudioMeta(sampleRate = 192000, numChannels = 8, bitDepth = 32)
    val result = WavFileGenerator.validateMetadata(meta)
    result shouldBe Right(meta)
  }

  // ===== Temp File Creation =====
  "createTempWavFile" should "create unique files on sequential calls" in {
    val result1 = WavFileGenerator.createTempWavFile("test1")
    val result2 = WavFileGenerator.createTempWavFile("test2")

    val path1 = result1.getOrElse(fail())
    val path2 = result2.getOrElse(fail())

    (path1 should not).equal(path2)
    Files.deleteIfExists(path1)
    Files.deleteIfExists(path2)
  }

  it should "create files with .wav extension" in {
    val result = WavFileGenerator.createTempWavFile("audio-test")
    val path   = result.getOrElse(fail())

    path.toString.endsWith(".wav") shouldBe true
    Files.deleteIfExists(path)
  }

  it should "handle various prefix strings" in {
    List("wav", "audio", "tmp", "test-prefix-123", "").foreach { prefix =>
      val result = WavFileGenerator.createTempWavFile(prefix)
      result.isRight shouldBe true
      val path = result.getOrElse(fail())
      path.toString.endsWith(".wav") shouldBe true
      Files.deleteIfExists(path)
    }
  }

  // ===== Java Audio Format Creation =====
  "createJavaAudioFormat" should "create format with correct sample rate" in {
    val testRates = List(8000, 16000, 22050, 44100, 48000, 96000)
    testRates.foreach { rate =>
      val meta   = AudioMeta(sampleRate = rate, numChannels = 1, bitDepth = 16)
      val format = WavFileGenerator.createJavaAudioFormat(meta)
      format.getSampleRate shouldBe rate.toFloat
    }
  }

  it should "create format with correct channel count" in {
    val testChannels = List(1, 2, 4, 6, 8)
    testChannels.foreach { channels =>
      val meta   = AudioMeta(sampleRate = 44100, numChannels = channels, bitDepth = 16)
      val format = WavFileGenerator.createJavaAudioFormat(meta)
      format.getChannels shouldBe channels
    }
  }

  it should "create format with correct bit depth" in {
    val testBitDepths = List(8, 16, 24, 32)
    testBitDepths.foreach { bd =>
      val meta   = AudioMeta(sampleRate = 44100, numChannels = 1, bitDepth = bd)
      val format = WavFileGenerator.createJavaAudioFormat(meta)
      format.getSampleSizeInBits shouldBe bd
    }
  }

  // ===== Create WAV from Bytes =====
  "createWavFromBytes" should "accept valid metadata and data" in {
    val data = Array[Byte](0, 1, 2, 3, 4, 5)
    val meta = AudioMeta(sampleRate = 44100, numChannels = 2, bitDepth = 16)

    val result = WavFileGenerator.createWavFromBytes(data, meta)
    result.isRight shouldBe true
    val audio = result.getOrElse(fail())
    audio.data shouldBe data
    audio.meta shouldBe meta
  }

  it should "fail with invalid metadata" in {
    val data        = Array[Byte](0, 1, 2, 3)
    val invalidMeta = AudioMeta(sampleRate = 5000, numChannels = 10, bitDepth = 12)

    val result = WavFileGenerator.createWavFromBytes(data, invalidMeta)
    result.isLeft shouldBe true
  }

  it should "accept empty byte array" in {
    val data = Array[Byte]()
    val meta = AudioMeta(sampleRate = 44100, numChannels = 1, bitDepth = 16)

    val result = WavFileGenerator.createWavFromBytes(data, meta)
    result.isRight shouldBe true
  }

  it should "accept large byte array" in {
    val data = Array.fill[Byte](100000)(0)
    val meta = AudioMeta(sampleRate = 44100, numChannels = 1, bitDepth = 16)

    val result = WavFileGenerator.createWavFromBytes(data, meta)
    result.isRight shouldBe true
  }

  // ===== Write to Temp WAV =====
  "writeToTempWav" should "create file and return path" in {
    val data = Array[Byte](0, 1, 2, 3, 4, 5)
    val meta = AudioMeta(sampleRate = 44100, numChannels = 2, bitDepth = 16)

    val result = WavFileGenerator.writeToTempWav(data, meta)
    result.isRight shouldBe true
    val path = result.getOrElse(fail())
    Files.exists(path) shouldBe true
    Files.deleteIfExists(path)
  }

  it should "fail with invalid metadata" in {
    val data        = Array[Byte](0, 1, 2, 3)
    val invalidMeta = AudioMeta(sampleRate = 5000, numChannels = 0, bitDepth = 16)

    val result = WavFileGenerator.writeToTempWav(data, invalidMeta)
    result.isLeft shouldBe true
  }

  it should "use custom prefix when provided" in {
    val data = Array[Byte](0, 1, 2, 3)
    val meta = AudioMeta(sampleRate = 44100, numChannels = 1, bitDepth = 16)

    val result = WavFileGenerator.writeToTempWav(data, meta, "custom-prefix-xyz")
    result.isRight shouldBe true
    val path = result.getOrElse(fail())
    // Prefix gets embedded in temp file name
    Files.deleteIfExists(path)
  }

  it should "handle large audio data" in {
    val largeData = Array.fill[Byte](50000)(1)
    val meta      = AudioMeta(sampleRate = 48000, numChannels = 2, bitDepth = 24)

    val result = WavFileGenerator.writeToTempWav(largeData, meta)
    result.isRight shouldBe true
    val path = result.getOrElse(fail())
    Files.deleteIfExists(path)
  }

  // ===== Read WAV File =====
  "readWavFile" should "fail for non-existent file" in {
    val nonExistentPath = Paths.get("/nonexistent/dir/file.wav")
    val result          = WavFileGenerator.readWavFile(nonExistentPath)
    result.isLeft shouldBe true
  }

  // ===== Error Message Content =====
  "Error messages" should "be descriptive for validation failures" in {
    val invalidMeta = AudioMeta(sampleRate = 100, numChannels = 1, bitDepth = 16)
    val result      = WavFileGenerator.validateMetadata(invalidMeta)
    result.isLeft shouldBe true
    // Error should be present and informative
    result.left.getOrElse(fail()).message should not be empty
  }

  // ===== Metadata Field Combinations =====
  "Different metadata combinations" should "work correctly" in {
    val combinations = List(
      (8000, 1, 8),
      (16000, 1, 16),
      (16000, 2, 16),
      (44100, 1, 16),
      (44100, 2, 16),
      (48000, 2, 24),
      (192000, 8, 32)
    )

    combinations.foreach { case (sr, ch, bd) =>
      val meta      = AudioMeta(sampleRate = sr, numChannels = ch, bitDepth = bd)
      val validated = WavFileGenerator.validateMetadata(meta)
      validated.isRight shouldBe true

      val format = WavFileGenerator.createJavaAudioFormat(meta)
      format.getSampleRate shouldBe sr.toFloat
      format.getChannels shouldBe ch
      format.getSampleSizeInBits shouldBe bd
    }
  }

  // ===== Real-World Scenarios =====
  "Real-world audio scenarios" should "be handled for telephony (8k mono)" in {
    val meta = AudioMeta(sampleRate = 8000, numChannels = 1, bitDepth = 16)
    WavFileGenerator.validateMetadata(meta) shouldBe Right(meta)
  }

  it should "be handled for CD quality (44.1k stereo)" in {
    val meta = AudioMeta(sampleRate = 44100, numChannels = 2, bitDepth = 16)
    WavFileGenerator.validateMetadata(meta) shouldBe Right(meta)
  }

  it should "be handled for professional (48k stereo 24-bit)" in {
    val meta = AudioMeta(sampleRate = 48000, numChannels = 2, bitDepth = 24)
    WavFileGenerator.validateMetadata(meta) shouldBe Right(meta)
  }

  it should "be handled for high-res (192k stereo 32-bit)" in {
    val meta = AudioMeta(sampleRate = 192000, numChannels = 2, bitDepth = 32)
    WavFileGenerator.validateMetadata(meta) shouldBe Right(meta)
  }

  // ===== State Immutability =====
  "WavFileGenerator state" should "be immutable across calls" in {
    val meta1 = AudioMeta(sampleRate = 44100, numChannels = 1, bitDepth = 16)
    val meta2 = AudioMeta(sampleRate = 48000, numChannels = 2, bitDepth = 24)

    val result1 = WavFileGenerator.validateMetadata(meta1)
    val result2 = WavFileGenerator.validateMetadata(meta2)

    result1 shouldBe Right(meta1)
    result2 shouldBe Right(meta2)
  }
}
