package org.llm4s.speech.processing

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.speech.AudioMeta

class AudioValidatorSpec extends AnyFlatSpec with Matchers {

  // ========== STTMetadataValidator ==========

  "STTMetadataValidator" should "accept valid STT metadata" in {
    val validator = AudioValidator.STTMetadataValidator()
    val meta      = AudioMeta(sampleRate = 16000, numChannels = 1, bitDepth = 16)
    validator.validate(meta) shouldBe Right(meta)
  }

  it should "reject invalid sample rate" in {
    val validator = AudioValidator.STTMetadataValidator()
    val meta      = AudioMeta(sampleRate = -1, numChannels = 1, bitDepth = 16)
    val result    = validator.validate(meta)
    result.isLeft shouldBe true
    result.left.toOption.get.message should include("Validation failed")
  }

  it should "reject non-16-bit depth" in {
    val validator = AudioValidator.STTMetadataValidator()
    val meta      = AudioMeta(sampleRate = 16000, numChannels = 1, bitDepth = 8)
    val result    = validator.validate(meta)
    result.isLeft shouldBe true
  }

  it should "reject sample rate too high" in {
    val validator = AudioValidator.STTMetadataValidator()
    val meta      = AudioMeta(sampleRate = 96000, numChannels = 1, bitDepth = 16)
    val result    = validator.validate(meta)
    result.isLeft shouldBe true
  }

  it should "reject non-AudioMeta input" in {
    val validator = AudioValidator.STTMetadataValidator()
    val result    = validator.validate("not audio meta")
    result.isLeft shouldBe true
    result.left.toOption.get.message should include("Input must be AudioMeta")
  }

  it should "have correct name" in {
    AudioValidator.STTMetadataValidator().name shouldBe "stt-metadata-validator"
  }

  // ========== AudioDataValidator ==========

  "AudioDataValidator" should "accept properly aligned audio data" in {
    val validator = AudioValidator.AudioDataValidator()
    val meta      = AudioMeta(16000, 1, 16)
    val data      = new Array[Byte](100) // 100 bytes / 2 bytes per frame = 50 frames, aligned
    validator.validate((data, meta)) shouldBe Right((data, meta))
  }

  it should "reject misaligned audio data" in {
    val validator = AudioValidator.AudioDataValidator()
    val meta      = AudioMeta(16000, 2, 16) // 4 bytes per frame
    val data      = new Array[Byte](5)      // 5 bytes, not divisible by 4
    val result    = validator.validate((data, meta))
    result.isLeft shouldBe true
  }

  it should "reject non-tuple input" in {
    val validator = AudioValidator.AudioDataValidator()
    val result    = validator.validate("not a tuple")
    result.isLeft shouldBe true
    result.left.toOption.get.message should include("Input must be")
  }

  it should "have correct name" in {
    AudioValidator.AudioDataValidator().name shouldBe "audio-data-validator"
  }

  // ========== NonEmptyAudioValidator ==========

  "NonEmptyAudioValidator" should "accept non-empty audio" in {
    val validator = AudioValidator.NonEmptyAudioValidator()
    val meta      = AudioMeta(16000, 1, 16)
    val data      = Array[Byte](1, 2)
    validator.validate((data, meta)) shouldBe Right((data, meta))
  }

  it should "reject empty audio" in {
    val validator = AudioValidator.NonEmptyAudioValidator()
    val meta      = AudioMeta(16000, 1, 16)
    val result    = validator.validate((Array.emptyByteArray, meta))
    result.isLeft shouldBe true
  }

  it should "reject non-tuple input" in {
    val validator = AudioValidator.NonEmptyAudioValidator()
    val result    = validator.validate(42)
    result.isLeft shouldBe true
  }

  it should "have correct name" in {
    AudioValidator.NonEmptyAudioValidator().name shouldBe "non-empty-audio-validator"
  }

  // ========== CompositeValidator ==========

  "CompositeValidator" should "pass when all validators pass" in {
    val composite = AudioValidator.sttValidator
    val meta      = AudioMeta(16000, 1, 16)
    val data      = new Array[Byte](100)
    composite.validate((data, meta)) shouldBe Right((data, meta))
  }

  it should "fail on first failing validator" in {
    val composite = AudioValidator.sttValidator
    val meta      = AudioMeta(16000, 1, 16)
    val result    = composite.validate((Array.emptyByteArray, meta))
    result.isLeft shouldBe true
  }

  it should "combine validator names" in {
    val composite = AudioValidator.sttValidator
    composite.name should include("+")
    composite.name should include("non-empty")
    composite.name should include("audio-data")
  }

  // ========== ValidatedSTTMetadataValidator ==========

  "ValidatedSTTMetadataValidator" should "accept valid metadata" in {
    val validator = AudioValidator.ValidatedSTTMetadataValidator()
    val meta      = AudioMeta(16000, 1, 16)
    validator.validate(meta).isValid shouldBe true
  }

  it should "accumulate multiple errors" in {
    val validator = AudioValidator.ValidatedSTTMetadataValidator()
    val meta      = AudioMeta(sampleRate = -1, numChannels = 0, bitDepth = 8)
    val result    = validator.validate(meta)
    result.isInvalid shouldBe true
  }

  it should "have correct name" in {
    AudioValidator.ValidatedSTTMetadataValidator().name shouldBe "validated-stt-metadata-validator"
  }

  // ========== validatedSttValidatorAsResult ==========

  "validatedSttValidatorAsResult" should "return Right for valid input" in {
    val meta  = AudioMeta(16000, 1, 16)
    val data  = new Array[Byte](100)
    val input = (data, meta)
    AudioValidator.validatedSttValidatorAsResult(input) shouldBe Right(input)
  }

  it should "return Left for empty input" in {
    val meta   = AudioMeta(16000, 1, 16)
    val result = AudioValidator.validatedSttValidatorAsResult((Array.emptyByteArray, meta))
    result.isLeft shouldBe true
  }
}
