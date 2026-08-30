package org.llm4s.speech.processing

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.speech.{ AudioInput, AudioMeta }

import java.io.ByteArrayInputStream
import java.nio.file.{ Files, Path }

class AudioExtractorsSpec extends AnyFlatSpec with Matchers {

  // ========== BytesExtractor ==========

  "BytesExtractor" should "extract bytes and metadata from BytesAudio" in {
    val data  = Array[Byte](1, 2, 3, 4)
    val input = AudioInput.BytesAudio(data, sampleRate = 16000, numChannels = 1)

    val result = AudioInputExtractors.BytesExtractor.process(input)
    result.isRight shouldBe true
    val (bytes, meta) = result.toOption.get
    bytes shouldBe data
    meta.sampleRate shouldBe 16000
    meta.numChannels shouldBe 1
    meta.bitDepth shouldBe 16
  }

  it should "extract bytes and metadata from StreamAudio" in {
    val data   = Array[Byte](10, 20, 30, 40)
    val stream = new ByteArrayInputStream(data)
    val input  = AudioInput.StreamAudio(stream, sampleRate = 44100, numChannels = 2)

    val result = AudioInputExtractors.BytesExtractor.process(input)
    result.isRight shouldBe true
    val (bytes, meta) = result.toOption.get
    bytes shouldBe data
    meta.sampleRate shouldBe 44100
    meta.numChannels shouldBe 2
  }

  it should "extract bytes from FileAudio" in {
    val tempFile = Files.createTempFile("audio-test-", ".raw")
    try {
      val data = Array[Byte](0, 1, 2, 3, 4, 5)
      Files.write(tempFile, data)
      val input = AudioInput.FileAudio(tempFile)

      val result = AudioInputExtractors.BytesExtractor.process(input)
      result.isRight shouldBe true
      val (bytes, meta) = result.toOption.get
      bytes shouldBe data
      meta.sampleRate shouldBe 44100
      meta.numChannels shouldBe 2
    } finally Files.deleteIfExists(tempFile)
  }

  it should "return error for non-existent FileAudio" in {
    val input  = AudioInput.FileAudio(Path.of("/nonexistent/audio.wav"))
    val result = AudioInputExtractors.BytesExtractor.process(input)
    result.isLeft shouldBe true
    result.left.toOption.get.message should include("Failed to read audio file")
  }

  it should "have correct name" in {
    AudioInputExtractors.BytesExtractor.name shouldBe "bytes-extractor"
  }

  // ========== MetadataExtractor ==========

  "MetadataExtractor" should "extract metadata from BytesAudio" in {
    val input  = AudioInput.BytesAudio(Array.emptyByteArray, sampleRate = 22050, numChannels = 2)
    val result = AudioInputExtractors.MetadataExtractor.process(input)
    result shouldBe Right(AudioMeta(22050, 2, 16))
  }

  it should "extract metadata from StreamAudio" in {
    val input =
      AudioInput.StreamAudio(new ByteArrayInputStream(Array.emptyByteArray), sampleRate = 8000, numChannels = 1)
    val result = AudioInputExtractors.MetadataExtractor.process(input)
    result shouldBe Right(AudioMeta(8000, 1, 16))
  }

  it should "extract default metadata from FileAudio" in {
    val input  = AudioInput.FileAudio(Path.of("/any/path.wav"))
    val result = AudioInputExtractors.MetadataExtractor.process(input)
    result shouldBe Right(AudioMeta(44100, 2, 16))
  }

  it should "have correct name" in {
    AudioInputExtractors.MetadataExtractor.name shouldBe "metadata-extractor"
  }

  // ========== PathExtractor ==========

  "PathExtractor" should "extract path from FileAudio" in {
    val path   = Path.of("/some/audio.wav")
    val input  = AudioInput.FileAudio(path)
    val result = AudioInputExtractors.PathExtractor.process(input)
    result shouldBe Right(Some(path))
  }

  it should "return None for BytesAudio" in {
    val input  = AudioInput.BytesAudio(Array.emptyByteArray, 16000)
    val result = AudioInputExtractors.PathExtractor.process(input)
    result shouldBe Right(None)
  }

  it should "return None for StreamAudio" in {
    val input  = AudioInput.StreamAudio(new ByteArrayInputStream(Array.emptyByteArray), 16000)
    val result = AudioInputExtractors.PathExtractor.process(input)
    result shouldBe Right(None)
  }

  it should "have correct name" in {
    AudioInputExtractors.PathExtractor.name shouldBe "path-extractor"
  }

  // ========== StreamExtractor ==========

  "StreamExtractor" should "extract stream from StreamAudio" in {
    val stream = new ByteArrayInputStream(Array[Byte](1, 2))
    val input  = AudioInput.StreamAudio(stream, 16000)
    val result = AudioInputExtractors.StreamExtractor.process(input)
    result.isRight shouldBe true
    result.toOption.get shouldBe Some(stream)
  }

  it should "return None for FileAudio" in {
    val input  = AudioInput.FileAudio(Path.of("/any.wav"))
    val result = AudioInputExtractors.StreamExtractor.process(input)
    result shouldBe Right(None)
  }

  it should "return None for BytesAudio" in {
    val input  = AudioInput.BytesAudio(Array.emptyByteArray, 16000)
    val result = AudioInputExtractors.StreamExtractor.process(input)
    result shouldBe Right(None)
  }

  it should "have correct name" in {
    AudioInputExtractors.StreamExtractor.name shouldBe "stream-extractor"
  }

  // ========== AudioConsumers ==========

  "NoOpConsumer" should "always succeed" in {
    AudioConsumers.NoOpConsumer.consume("anything") shouldBe Right(())
    AudioConsumers.NoOpConsumer.name shouldBe "noop-consumer"
  }

  "LoggingConsumer" should "handle tuple input without error" in {
    val meta  = AudioMeta(16000, 1, 16)
    val input = (Array[Byte](1, 2), meta)
    AudioConsumers.LoggingConsumer.consume(input) shouldBe Right(())
  }

  it should "handle AudioMeta input" in {
    AudioConsumers.LoggingConsumer.consume(AudioMeta(44100, 2, 16)) shouldBe Right(())
  }

  it should "handle other input types" in {
    AudioConsumers.LoggingConsumer.consume("some string") shouldBe Right(())
    AudioConsumers.LoggingConsumer.name shouldBe "logging-consumer"
  }

  "ValidationConsumer" should "succeed when validation passes" in {
    val validator = AudioValidator.NonEmptyAudioValidator()
    val consumer  = AudioConsumers.ValidationConsumer(validator)
    val meta      = AudioMeta(16000, 1, 16)
    val data      = (Array[Byte](1, 2), meta)

    consumer.consume(data) shouldBe Right(())
    consumer.name should include("validation-consumer")
  }

  it should "fail when validation fails" in {
    val validator = AudioValidator.NonEmptyAudioValidator()
    val consumer  = AudioConsumers.ValidationConsumer(validator)
    val meta      = AudioMeta(16000, 1, 16)
    val data      = (Array.emptyByteArray, meta)

    consumer.consume(data).isLeft shouldBe true
  }

  // ========== AudioProcessing utilities ==========

  "AudioProcessing.pipe" should "pipe processor output to consumer" in {
    val input    = AudioInput.BytesAudio(Array[Byte](1, 2), 16000)
    val pipeline = AudioProcessing.pipe(AudioInputExtractors.MetadataExtractor, AudioConsumers.NoOpConsumer)
    pipeline(input) shouldBe Right(())
  }

  "AudioProcessing.broadcast" should "succeed when all consumers succeed" in {
    val broadcast = AudioProcessing.broadcast(AudioConsumers.NoOpConsumer, AudioConsumers.LoggingConsumer)
    broadcast.consume("data") shouldBe Right(())
    broadcast.name should include("broadcast")
  }

  "AudioProcessing.conditional" should "use ifTrue when predicate is true" in {
    val processor = AudioProcessing.conditional[AudioMeta](
      _ => true,
      AudioInputExtractors.MetadataExtractor,
      AudioInputExtractors.MetadataExtractor
    )
    val input  = AudioInput.BytesAudio(Array.emptyByteArray, 8000, 1)
    val result = processor.process(input)
    result shouldBe Right(AudioMeta(8000, 1, 16))
    processor.name should include("conditional")
  }

  "AudioProcessing.withFallback" should "use primary when it succeeds" in {
    val processor = AudioProcessing.withFallback(
      AudioInputExtractors.MetadataExtractor,
      AudioInputExtractors.MetadataExtractor
    )
    val input  = AudioInput.BytesAudio(Array.emptyByteArray, 16000, 1)
    val result = processor.process(input)
    result shouldBe Right(AudioMeta(16000, 1, 16))
    processor.name should include("fallback")
  }
}
