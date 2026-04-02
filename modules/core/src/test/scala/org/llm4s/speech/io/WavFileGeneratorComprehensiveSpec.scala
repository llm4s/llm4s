package org.llm4s.speech.io

import org.llm4s.speech.AudioMeta
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach
import java.nio.file.{ Files, Paths }

class WavFileGeneratorComprehensiveSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  // ===== Test Metadata Validation =====
  "WavFileGenerator.validateMetadata" should "accept valid metadata" in {
    val validMeta = AudioMeta(sampleRate = 44100, numChannels = 2, bitDepth = 16)
    WavFileGenerator.validateMetadata(validMeta) shouldBe Right(validMeta)
  }

  it should "reject sample rate below minimum (8000)" in {
    val meta   = AudioMeta(sampleRate = 4000, numChannels = 2, bitDepth = 16)
    val result = WavFileGenerator.validateMetadata(meta)
    result.isLeft shouldBe true
  }

  it should "reject sample rate above maximum (192000)" in {
    val meta   = AudioMeta(sampleRate = 200000, numChannels = 2, bitDepth = 16)
    val result = WavFileGenerator.validateMetadata(meta)
    result.isLeft shouldBe true
  }

  it should "accept sample rates at boundaries (8000, 192000)" in {
    val metaMin = AudioMeta(sampleRate = 8000, numChannels = 1, bitDepth = 16)
    val metaMax = AudioMeta(sampleRate = 192000, numChannels = 1, bitDepth = 16)
    WavFileGenerator.validateMetadata(metaMin) shouldBe Right(metaMin)
    WavFileGenerator.validateMetadata(metaMax) shouldBe Right(metaMax)
  }

  it should "reject invalid bit depths" in {
    val meta   = AudioMeta(sampleRate = 44100, numChannels = 2, bitDepth = 12)
    val result = WavFileGenerator.validateMetadata(meta)
    result.isLeft shouldBe true
  }

  it should "accept valid bit depths (8, 16, 24, 32)" in {
    val validBitDepths = List(8, 16, 24, 32)
    validBitDepths.foreach { bd =>
      val meta = AudioMeta(sampleRate = 44100, numChannels = 2, bitDepth = bd)
      WavFileGenerator.validateMetadata(meta) shouldBe Right(meta)
    }
  }

  it should "reject invalid bit depths like 20" in {
    val meta   = AudioMeta(sampleRate = 44100, numChannels = 2, bitDepth = 20)
    val result = WavFileGenerator.validateMetadata(meta)
    result.isLeft shouldBe true
  }

  it should "reject zero or negative channels" in {
    val meta0   = AudioMeta(sampleRate = 44100, numChannels = 0, bitDepth = 16)
    val metaNeg = AudioMeta(sampleRate = 44100, numChannels = -1, bitDepth = 16)
    WavFileGenerator.validateMetadata(meta0).isLeft shouldBe true
    WavFileGenerator.validateMetadata(metaNeg).isLeft shouldBe true
  }

  it should "reject channels above maximum (8)" in {
    val meta   = AudioMeta(sampleRate = 44100, numChannels = 10, bitDepth = 16)
    val result = WavFileGenerator.validateMetadata(meta)
    result.isLeft shouldBe true
  }

  it should "accept channels at boundaries (1, 8)" in {
    val meta1 = AudioMeta(sampleRate = 44100, numChannels = 1, bitDepth = 16)
    val meta8 = AudioMeta(sampleRate = 44100, numChannels = 8, bitDepth = 16)
    WavFileGenerator.validateMetadata(meta1) shouldBe Right(meta1)
    WavFileGenerator.validateMetadata(meta8) shouldBe Right(meta8)
  }

  // ===== Test Temporary File Creation =====
  "WavFileGenerator.createTempWavFile" should "create a temporary WAV file" in {
    val result = WavFileGenerator.createTempWavFile("test-prefix")
    result shouldBe a[Right[_, _]]
    val path = result.getOrElse(fail())
    Files.exists(path) shouldBe true
    // Cleanup
    Files.deleteIfExists(path)
  }

  it should "create files with .wav extension" in {
    val result = WavFileGenerator.createTempWavFile("myaudio")
    val path   = result.getOrElse(fail())
    path.toString.endsWith(".wav") shouldBe true
    Files.deleteIfExists(path)
  }

  it should "create unique files when called multiple times" in {
    val result1 = WavFileGenerator.createTempWavFile("prefix1")
    val result2 = WavFileGenerator.createTempWavFile("prefix1")
    val path1   = result1.getOrElse(fail())
    val path2   = result2.getOrElse(fail())
    path1.equals(path2) shouldBe false
    Files.deleteIfExists(path1)
    Files.deleteIfExists(path2)
  }

  // ===== Test Java Audio Format Creation =====
  "WavFileGenerator.createJavaAudioFormat" should "create format with correct sample rate" in {
    val meta   = AudioMeta(sampleRate = 44100, numChannels = 2, bitDepth = 16)
    val format = WavFileGenerator.createJavaAudioFormat(meta)
    format.getSampleRate shouldBe 44100.0f
  }

  it should "create format with correct channel count" in {
    val meta   = AudioMeta(sampleRate = 44100, numChannels = 2, bitDepth = 16)
    val format = WavFileGenerator.createJavaAudioFormat(meta)
    format.getChannels shouldBe 2
  }

  it should "create format with correct bit depth" in {
    val meta   = AudioMeta(sampleRate = 44100, numChannels = 2, bitDepth = 16)
    val format = WavFileGenerator.createJavaAudioFormat(meta)
    format.getSampleSizeInBits shouldBe 16
  }

  it should "create mono format correctly" in {
    val meta   = AudioMeta(sampleRate = 8000, numChannels = 1, bitDepth = 8)
    val format = WavFileGenerator.createJavaAudioFormat(meta)
    format.getChannels shouldBe 1
    format.getSampleRate shouldBe 8000.0f
  }

  it should "create surround sound format correctly" in {
    val meta   = AudioMeta(sampleRate = 48000, numChannels = 6, bitDepth = 24)
    val format = WavFileGenerator.createJavaAudioFormat(meta)
    format.getChannels shouldBe 6
    format.getSampleSizeInBits shouldBe 24
  }

  // ===== Test Create WAV from Bytes =====
  "WavFileGenerator.createWavFromBytes" should "create audio with valid metadata" in {
    val data   = Array[Byte](0, 1, 2, 3)
    val meta   = AudioMeta(sampleRate = 44100, numChannels = 2, bitDepth = 16)
    val result = WavFileGenerator.createWavFromBytes(data, meta)
    result shouldBe a[Right[_, _]]
    val audio = result.getOrElse(fail())
    audio.data shouldBe data
    audio.meta shouldBe meta
  }

  it should "fail with invalid metadata" in {
    val data   = Array[Byte](0, 1, 2, 3)
    val meta   = AudioMeta(sampleRate = 100000, numChannels = 10, bitDepth = 12)
    val result = WavFileGenerator.createWavFromBytes(data, meta)
    result.isLeft shouldBe true
  }

  it should "handle empty byte array" in {
    val data   = Array[Byte]()
    val meta   = AudioMeta(sampleRate = 44100, numChannels = 2, bitDepth = 16)
    val result = WavFileGenerator.createWavFromBytes(data, meta)
    result shouldBe a[Right[_, _]]
    val audio = result.getOrElse(fail())
    audio.data.length shouldBe 0
  }

  // ===== Test Write to Temp WAV =====
  "WavFileGenerator.writeToTempWav" should "create a temporary WAV file with data" in {
    val data   = Array[Byte](0, 1, 2, 3, 4, 5)
    val meta   = AudioMeta(sampleRate = 44100, numChannels = 2, bitDepth = 16)
    val result = WavFileGenerator.writeToTempWav(data, meta)
    result shouldBe a[Right[_, _]]
    val path = result.getOrElse(fail())
    Files.exists(path) shouldBe true
    Files.deleteIfExists(path)
  }

  it should "fail with invalid metadata" in {
    val data   = Array[Byte](0, 1, 2, 3)
    val meta   = AudioMeta(sampleRate = 5000, numChannels = 0, bitDepth = 16)
    val result = WavFileGenerator.writeToTempWav(data, meta)
    result.isLeft shouldBe true
  }

  it should "accept custom prefix" in {
    val data   = Array[Byte](0, 1, 2, 3)
    val meta   = AudioMeta(sampleRate = 44100, numChannels = 2, bitDepth = 16)
    val result = WavFileGenerator.writeToTempWav(data, meta, "custom-prefix")
    val path   = result.getOrElse(fail())
    // Verify the file was created
    Files.exists(path) shouldBe true
    Files.deleteIfExists(path)
  }

  // ===== Test Read WAV File =====
  "WavFileGenerator.readWavFile" should "fail for non-existent file" in {
    val path   = Paths.get("/nonexistent/file.wav")
    val result = WavFileGenerator.readWavFile(path)
    result.isLeft shouldBe true
  }

  // ===== Edge Cases =====
  "WavFileGenerator" should "handle real-world metadata (CD quality)" in {
    val cdMeta = AudioMeta(sampleRate = 44100, numChannels = 2, bitDepth = 16)
    WavFileGenerator.validateMetadata(cdMeta) shouldBe Right(cdMeta)
  }

  it should "handle telephony metadata (8kHz mono)" in {
    val telMeta = AudioMeta(sampleRate = 8000, numChannels = 1, bitDepth = 16)
    WavFileGenerator.validateMetadata(telMeta) shouldBe Right(telMeta)
  }

  it should "handle professional metadata (48kHz, 24-bit)" in {
    val proMeta = AudioMeta(sampleRate = 48000, numChannels = 2, bitDepth = 24)
    WavFileGenerator.validateMetadata(proMeta) shouldBe Right(proMeta)
  }

  it should "handle high-res audio metadata (192kHz, 32-bit)" in {
    val hiRes = AudioMeta(sampleRate = 192000, numChannels = 2, bitDepth = 32)
    WavFileGenerator.validateMetadata(hiRes) shouldBe Right(hiRes)
  }

  it should "handle multichannel Dolby Digital (5.1 surround)" in {
    val surround = AudioMeta(sampleRate = 48000, numChannels = 6, bitDepth = 16)
    WavFileGenerator.validateMetadata(surround) shouldBe Right(surround)
  }
}
