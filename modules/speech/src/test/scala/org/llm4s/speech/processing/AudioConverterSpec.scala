package org.llm4s.speech.processing

import org.llm4s.speech.AudioMeta
import org.llm4s.error.ProcessingError
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class AudioConverterSpec extends AnyFlatSpec with Matchers {
  import AudioConverter._

  type AudioData = (Array[Byte], AudioMeta)

  val testMeta             = AudioMeta(sampleRate = 16000, numChannels = 1, bitDepth = 16)
  val testBytes            = Array.fill[Byte](320)(0)
  val testInput: AudioData = (testBytes, testMeta)

  private def failingConverter(msg: String): AudioConverter[AudioData, AudioData] =
    new AudioConverter[AudioData, AudioData] {
      def convert(input: AudioData): Result[AudioData] = Left(ProcessingError.audioValidation(msg))
      def name: String                                 = "failing"
    }

  "IdentityConverter" should "return input unchanged" in {
    val result = IdentityConverter[AudioData]().convert(testInput)
    result.isRight shouldBe true
    val (bytes, meta) = result.toOption.get
    bytes shouldBe testBytes
    meta shouldBe testMeta
  }

  "MappedConverter" should "apply function to success result" in {
    val mapped = IdentityConverter[AudioData]().map { case (b, m) => (b.length, m.sampleRate) }
    val result = mapped.convert(testInput)
    result shouldBe Right((testBytes.length, testMeta.sampleRate))
  }

  "FlatMappedConverter" should "chain to a successful result" in {
    val flatMapped = IdentityConverter[AudioData]().flatMap(data => Right(data._1.length))
    val result     = flatMapped.convert(testInput)
    result shouldBe Right(testBytes.length)
  }

  it should "short-circuit on upstream failure" in {
    val flatMapped = failingConverter("upstream error").flatMap(data => Right(data._1.length))
    val result     = flatMapped.convert(testInput)
    result.isLeft shouldBe true
  }

  "FilteredConverter" should "pass when predicate is true" in {
    val filtered = IdentityConverter[AudioData]().filter(_ => true, "should not fail")
    val result   = filtered.convert(testInput)
    result.isRight shouldBe true
  }

  it should "return error when predicate is false" in {
    val filtered = IdentityConverter[AudioData]().filter(_ => false, "predicate failed")
    val result   = filtered.convert(testInput)
    result.isLeft shouldBe true
  }

  "CompositeConverter" should "sequence two converters" in {
    val composite = CompositeConverter(IdentityConverter[AudioData](), IdentityConverter[AudioData]())
    val result    = composite.convert(testInput)
    result.isRight shouldBe true
  }

  it should "short-circuit when first fails" in {
    var secondCalled = false
    val second: AudioConverter[AudioData, AudioData] = new AudioConverter[AudioData, AudioData] {
      def convert(input: AudioData): Result[AudioData] = { secondCalled = true; Right(input) }
      def name: String                                 = "second"
    }
    val composite = CompositeConverter(failingConverter("first failed"), second)
    composite.convert(testInput)
    secondCalled shouldBe false
  }

  "sttPreprocessor" should "build a named pipeline of 3 stages" in {
    val preprocessor = sttPreprocessor()
    preprocessor.name should include("mono-converter")
    preprocessor.name should include("resample-converter")
    preprocessor.name should include("silence-trimmer")
  }

  "customSttPreprocessor with monoConversion=false" should "use identity for the mono step" in {
    val preprocessor = customSttPreprocessor(monoConversion = false)
    preprocessor.name should include("identity")
  }

  "customSttPreprocessor with targetRate=None" should "use identity for the resample step" in {
    val preprocessor = customSttPreprocessor(targetRate = None)
    preprocessor.name should include("identity")
  }
}
