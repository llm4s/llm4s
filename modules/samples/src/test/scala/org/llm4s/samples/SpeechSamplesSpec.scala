package org.llm4s.samples

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SpeechSamplesSpec extends AnyFlatSpec with Matchers {

  "signToneValues" should "generate correct number of samples for given duration and sample rate" in {
    val sampleRate = 1000
    val duration = 2
    val result = SpeechSamples.signToneValuesGenerator(sampleRate, duration)

    result.length shouldBe sampleRate * duration
  }

  it should "generate a sine wave with 440Hz frequency" in {
    val sampleRate = 8000
    val duration = 1
    val result = SpeechSamples.signToneValuesGenerator(sampleRate, duration).take(10).toList

    // Check that we get non-zero values (actual sine wave)
    result should not be empty
    result.exists(_ != 0) shouldBe true
  }

  it should "generate values within expected amplitude range" in {
    val sampleRate = 1000
    val duration = 1
    val result = SpeechSamples.signToneValuesGenerator(sampleRate, duration)

    // With amplitude 0.3 and max value 32767, max expected value is around 9830
    val maxExpected = (0.3 * 32767).toInt

    result.foreach { sample =>
      sample should be >= -maxExpected
      sample should be <= maxExpected
    }
  }

  it should "generate empty LazyList for zero duration" in {
    val sampleRate = 1000
    val duration = 0
    val result = SpeechSamples.signToneValuesGenerator(sampleRate, duration)

    result shouldBe empty
  }

  it should "handle small sample rates correctly" in {
    val sampleRate = 1
    val duration = 1
    val result = SpeechSamples.signToneValuesGenerator(sampleRate, duration)

    result.length shouldBe 1
    // First sample should be 0 (sine of 0)
    result.head shouldBe 0
  }

  it should "generate periodic sine wave pattern" in {
    val sampleRate = 440 // Sample rate equal to frequency for easy verification
    val duration = 2
    val result = SpeechSamples.signToneValuesGenerator(sampleRate, duration).take(sampleRate * 2).toList

    // For 440Hz at 440 samples per second, we should get one complete cycle per second
    // The pattern should approximately repeat every 'sampleRate' samples
    val firstCycle = result.take(sampleRate)
    val secondCycle = result.slice(sampleRate, sampleRate + sampleRate)

    // Values should be approximately the same (allowing for floating point precision)
    firstCycle.zip(secondCycle).foreach { case (first, second) =>
      math.abs(first - second) should be <= 1 // Allow small differences due to int conversion
    }
  }

  it should "generate symmetric wave around zero" in {
    val sampleRate = 8000
    val duration = 1
    val result = SpeechSamples.signToneValuesGenerator(sampleRate, duration).take(sampleRate).toList

    // For a complete sine wave, the sum should be close to zero
    val sum = result.sum.toDouble
    val average = sum / result.length

    // Average should be close to zero (within 1% of max amplitude)
    val maxAmplitude = 0.3 * 32767
    math.abs(average) should be <= maxAmplitude * 0.01
  }

  it should "handle large durations without stack overflow" in {
    val sampleRate = 1000
    val duration = 100 // Large duration

    // This should not cause stack overflow due to LazyList's lazy evaluation
    val result = SpeechSamples.signToneValuesGenerator(sampleRate, duration)
    result.length shouldBe sampleRate * duration

    // Verify we can access elements throughout the sequence
    result.take(10).toList should not be empty
    result.slice(50000, 50010).toList should not be empty
  }

  it should "generate mathematically correct sine wave values" in {
    val sampleRate = 1000
    val duration = 1
    val result = SpeechSamples.signToneValuesGenerator(sampleRate, duration).take(4).toList

    val frequency = 440.0
    val amplitude = 0.3

    // Calculate expected values for first few samples
    val expected = (0 until 4).map { i =>
      (Math.sin(2 * Math.PI * frequency * i / sampleRate) * amplitude * 32767).toInt
    }.toList

    result shouldBe expected
  }

  it should "be lazy and not compute all values immediately" in {
    val sampleRate = 1000000 // Very large sample rate
    val duration = 1000      // Very large duration

    // Creating the LazyList should be fast
    val start = System.currentTimeMillis()
    val result = SpeechSamples.signToneValuesGenerator(sampleRate, duration)
    val creationTime = System.currentTimeMillis() - start

    // Creation should be very fast (less than 100ms) due to lazy evaluation
    creationTime should be < 100L

    // But we can still access the first few elements
    result.take(5).toList should have length 5
  }
}