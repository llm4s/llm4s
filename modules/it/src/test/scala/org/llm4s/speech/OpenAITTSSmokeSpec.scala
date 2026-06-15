package org.llm4s.speech

import org.llm4s.speech.cloud.OpenAITTSClient
import org.llm4s.speech.tts.TTSOptions
import org.scalatest.Tag
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Tag for tests that require cloud provider API keys. */
object CloudSmoke extends Tag("org.llm4s.tags.CloudSmoke")

/**
 * Smoke tests for the OpenAI TTS cloud provider.
 *
 * Requires: `OPENAI_API_KEY` environment variable.
 * Run with: `sbt testSmoke` or `sbt "it/testOnly org.llm4s.speech.OpenAITTSSmokeSpec"`
 *
 * These tests make real HTTP calls and incur API usage costs.
 */
class OpenAITTSSmokeSpec extends AnyFlatSpec with Matchers {

  private val apiKey: Option[String] = Option(System.getenv("OPENAI_API_KEY")).filter(_.nonEmpty)

  "OpenAI TTS" should "synthesize a short phrase and return non-empty audio bytes" taggedAs CloudSmoke in {
    assume(apiKey.isDefined, "OPENAI_API_KEY not set — skipping OpenAI TTS smoke test")

    val client = OpenAITTSClient(apiKey.get)
    val result = client.synthesize("Hello from llm4s.", TTSOptions(voice = Some("alloy")))

    withClue(s"TTS synthesis failed: ${result.swap.toOption}") {
      result.isRight shouldBe true
    }

    val audio = result.toOption.get
    audio.data should not be empty
  }

  it should "return audio bytes that start with valid MP3 magic bytes (0xFF 0xFB or ID3)" taggedAs CloudSmoke in {
    assume(apiKey.isDefined, "OPENAI_API_KEY not set — skipping OpenAI TTS smoke test")

    val client = OpenAITTSClient(apiKey.get)
    val result = client.synthesize("Testing audio magic bytes.", TTSOptions(voice = Some("nova")))

    withClue(s"TTS synthesis failed: ${result.swap.toOption}") {
      result.isRight shouldBe true
    }

    val bytes = result.toOption.get.data
    bytes.length should be > 3

    val hasId3Header  = bytes(0) == 'I'.toByte && bytes(1) == 'D'.toByte && bytes(2) == '3'.toByte
    val hasMp3Sync    = (bytes(0) & 0xff) == 0xff && (bytes(1) & 0xe0) == 0xe0
    val validMp3Start = hasId3Header || hasMp3Sync

    withClue(
      s"Expected MP3 magic bytes (ID3 or 0xFF 0xFB...) but got: " +
        bytes.take(4).map(b => f"0x${b & 0xff}%02X").mkString(", ")
    ) {
      validMp3Start shouldBe true
    }
  }

  it should "support multiple voice options" taggedAs CloudSmoke in {
    assume(apiKey.isDefined, "OPENAI_API_KEY not set — skipping OpenAI TTS smoke test")

    val voices = Seq("alloy", "echo", "fable")
    val client = OpenAITTSClient(apiKey.get)

    voices.foreach { voice =>
      val result = client.synthesize("Testing voice.", TTSOptions(voice = Some(voice)))
      withClue(s"TTS failed for voice '$voice': ${result.swap.toOption}") {
        result.isRight shouldBe true
        result.toOption.get.data should not be empty
      }
    }
  }
}
