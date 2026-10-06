package org.llm4s.speech.processing

import org.llm4s.speech.AudioMeta
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class AudioValidationsSpec extends AnyWordSpec with Matchers with OptionValues {

  "validateInputNotEmpty" should {
    "accept non-empty audio payloads" in {
      val bytes  = Array[Byte](1, 2, 3)
      val meta   = AudioMeta(sampleRate = 16_000, numChannels = 1, bitDepth = 16)
      val result = AudioValidations.validateInputNotEmpty(bytes -> meta)

      result.isValid shouldBe true
      result.toEither shouldBe Right(bytes -> meta)
    }

    "reject empty audio payloads" in {
      val meta   = AudioMeta(sampleRate = 16_000, numChannels = 1, bitDepth = 16)
      val result = AudioValidations.validateInputNotEmpty(Array.emptyByteArray -> meta)

      result.isInvalid shouldBe true
      val errors = result.swap.toOption.value.toList

      errors should have size 1
      errors.head.operation shouldBe "audio-validation"
      errors.head.message should include("Audio data is empty")
    }
  }

  "validateFrameSize" should {
    "accept audio payloads aligned to frame size" in {
      val meta   = AudioMeta(sampleRate = 16_000, numChannels = 2, bitDepth = 16)
      val bytes  = Array.fill[Byte](8)(0)
      val result = AudioValidations.validateFrameSize(bytes -> meta)

      result.isValid shouldBe true
      result.toEither shouldBe Right(bytes -> meta)
    }

    "reject payloads with partial frames" in {
      val meta   = AudioMeta(sampleRate = 16_000, numChannels = 2, bitDepth = 16)
      val bytes  = Array.fill[Byte](6)(0)
      val result = AudioValidations.validateFrameSize(bytes -> meta)

      result.isInvalid shouldBe true
      val errors = result.swap.toOption.value.toList

      errors should have size 1
      errors.head.message should include("Audio data length")
      errors.head.message should include("frame size")
    }

    "reject metadata with no PCM frame size instead of dividing by zero" in {
      val bytes = Array.fill[Byte](417)(0)
      Seq(
        AudioMeta(sampleRate = 24_000, numChannels = 1, bitDepth = 0), // MP3: no sample width
        AudioMeta(sampleRate = 16_000, numChannels = 1, bitDepth = 4), // sub-byte sample
        AudioMeta(sampleRate = 16_000, numChannels = 0, bitDepth = 16),
        AudioMeta(sampleRate = 16_000, numChannels = -2, bitDepth = 16)
      ).foreach { meta =>
        val errors = AudioValidations.validateFrameSize(bytes -> meta).swap.toOption.value.toList
        errors should have size 1
        errors.head.message should include("no PCM frame size")
      }
    }
  }

  "pcmFrameSize" should {
    "be channels times whole bytes per sample for PCM metadata" in {
      AudioValidations.pcmFrameSize(AudioMeta(16_000, 2, 16)) shouldBe Some(4)
      AudioValidations.pcmFrameSize(AudioMeta(16_000, 1, 24)) shouldBe Some(3)
    }

    "be None for compressed or malformed metadata" in {
      AudioValidations.pcmFrameSize(AudioMeta(24_000, 1, 0)) shouldBe None
      AudioValidations.pcmFrameSize(AudioMeta(16_000, 0, 16)) shouldBe None
      AudioValidations.pcmFrameSize(AudioMeta(16_000, -1, -16)) shouldBe None
    }
  }

  "validateSampleRate" should {
    "accept positive sample rates" in {
      AudioValidations.validateSampleRate(AudioMeta(16_000, 1, 16)).toEither shouldBe Right(())
    }

    "reject non-positive sample rates" in {
      val errors = AudioValidations.validateSampleRate(AudioMeta(0, 1, 16)).swap.toOption.value.toList

      errors should have size 1
      errors.head.message should include("Sample rate must be positive")
    }
  }

  "validateNumChannels" should {
    "accept positive channel counts" in {
      AudioValidations.validateNumChannels(AudioMeta(16_000, 1, 16)).toEither shouldBe Right(())
    }

    "reject non-positive channel counts" in {
      val errors = AudioValidations.validateNumChannels(AudioMeta(16_000, 0, 16)).swap.toOption.value.toList

      errors should have size 1
      errors.head.message should include("Number of channels must be positive")
    }
  }

  "validateBitDepth" should {
    "accept 16-bit audio" in {
      AudioValidations.validateBitDepth(AudioMeta(16_000, 1, 16)).toEither shouldBe Right(())
    }

    "reject unsupported bit depths" in {
      val errors = AudioValidations.validateBitDepth(AudioMeta(16_000, 1, 8)).swap.toOption.value.toList

      errors should have size 1
      errors.head.message should include("Only 16-bit audio is supported")
    }
  }

  "validateSampleRateIsNotTooHigh" should {
    "accept sample rates at or below 48kHz" in {
      AudioValidations.validateSampleRateIsNotTooHigh(AudioMeta(48_000, 1, 16)).toEither shouldBe Right(())
    }

    "reject overly high sample rates" in {
      val errors = AudioValidations.validateSampleRateIsNotTooHigh(AudioMeta(96_000, 1, 16)).swap.toOption.value.toList

      errors should have size 1
      errors.head.message should include("Sample rate too high for STT")
    }
  }
}
