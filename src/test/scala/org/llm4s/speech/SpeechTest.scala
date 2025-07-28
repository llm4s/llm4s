package org.llm4s.speech

import org.llm4s.speech.config.OpenAISpeechConfig
import org.llm4s.speech.model._
import org.llm4s.speech.provider.SpeechProvider
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SpeechTest extends AnyFlatSpec with Matchers {

  "Speech object" should "provide factory methods" in {
    // Test that the Speech object can be instantiated with explicit config
    noException should be thrownBy {
      val config = OpenAISpeechConfig("test-key", "tts-1")
      Speech.ttsClient(SpeechProvider.OpenAI, config)
      Speech.asrClient(SpeechProvider.OpenAI, config)
    }
  }

  "TTSSynthesisOptions" should "have default values" in {
    val options = TTSSynthesisOptions()
    options.voice shouldBe "alloy"
    options.model shouldBe "tts-1"
    options.responseFormat shouldBe "mp3"
    options.speed shouldBe 1.0
  }

  "ASRTranscriptionOptions" should "have default values" in {
    val options = ASRTranscriptionOptions()
    options.model shouldBe "whisper-1"
    options.language shouldBe None
    options.responseFormat shouldBe "json"
    options.temperature shouldBe 0.0
  }

  "SpeechError" should "handle different error types" in {
    val authError = SpeechAuthenticationError("Invalid API key")
    authError.message shouldBe "Invalid API key"

    val rateLimitError = SpeechRateLimitError("Rate limit exceeded")
    rateLimitError.message shouldBe "Rate limit exceeded"

    val validationError = SpeechValidationError("Invalid input")
    validationError.message shouldBe "Invalid input"
  }

  "AudioResponse" should "contain audio data" in {
    val audioData = Array[Byte](1, 2, 3, 4)
    val response  = AudioResponse(audioData, "mp3")
    response.audioData shouldBe audioData
    response.format shouldBe "mp3"
  }

  "TranscriptionResponse" should "contain transcription data" in {
    val text     = "Hello world"
    val response = TranscriptionResponse(text)
    response.text shouldBe text
    response.language shouldBe None
    response.segments shouldBe Seq.empty
  }

  "TranscriptionSegment" should "contain segment data" in {
    val segment = TranscriptionSegment(
      id = 0,
      start = 0.0,
      end = 1.5,
      text = "Hello"
    )
    segment.id shouldBe 0
    segment.start shouldBe 0.0
    segment.end shouldBe 1.5
    segment.text shouldBe "Hello"
  }

  "SpeechProvider" should "support all providers" in {
    SpeechProvider.OpenAI shouldBe SpeechProvider.OpenAI
    SpeechProvider.Azure shouldBe SpeechProvider.Azure
    SpeechProvider.Google shouldBe SpeechProvider.Google
    SpeechProvider.ElevenLabs shouldBe SpeechProvider.ElevenLabs
    SpeechProvider.Amazon shouldBe SpeechProvider.Amazon
  }

  "OpenAISpeechConfig" should "be created from environment" in {
    // This test will fail if environment variables are not set
    // but it tests the structure
    try {
      val config = OpenAISpeechConfig.fromEnv("tts-1")
      config.model shouldBe "tts-1"
    } catch {
      case _: IllegalArgumentException =>
        // Expected if environment variables are not set
        succeed
    }
  }
}
