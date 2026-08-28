package org.llm4s.speech

import org.llm4s.speech.cloud.ElevenLabsTTSClient
import org.llm4s.speech.tts.TTSOptions
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Smoke tests for the ElevenLabs TTS cloud provider.
 *
 * Requires: `ELEVENLABS_API_KEY` environment variable.
 * Run with: `sbt testSmoke` or `sbt "it/testOnly org.llm4s.speech.ElevenLabsSmokeSpec"`
 *
 * These tests make real HTTP calls and incur API usage costs.
 */
class ElevenLabsSmokeSpec extends AnyFlatSpec with Matchers {

  private val apiKey: Option[String] = Option(System.getenv("ELEVENLABS_API_KEY")).filter(_.nonEmpty)

  "ElevenLabs TTS" should "synthesize a short phrase and return non-empty audio bytes" taggedAs CloudSmoke in {
    assume(apiKey.isDefined, "ELEVENLABS_API_KEY not set — skipping ElevenLabs smoke test")

    val client = ElevenLabsTTSClient(apiKey.get)
    val result = client.synthesize("Hello from llm4s.", TTSOptions())

    withClue(s"ElevenLabs TTS synthesis failed: ${result.swap.toOption}") {
      result.isRight shouldBe true
    }

    val audio = result.toOption.get
    audio.data should not be empty
  }

  it should "synthesize with a custom voice ID" taggedAs CloudSmoke in {
    assume(apiKey.isDefined, "ELEVENLABS_API_KEY not set — skipping ElevenLabs smoke test")

    // Use ElevenLabs default "Rachel" voice ID
    val voiceId = ElevenLabsTTSClient.DEFAULT_VOICE_ID
    val client  = ElevenLabsTTSClient(apiKey.get, voiceId = voiceId)
    val result  = client.synthesize("Testing custom voice.", TTSOptions())

    withClue(s"ElevenLabs TTS synthesis failed for voice $voiceId: ${result.swap.toOption}") {
      result.isRight shouldBe true
    }
    result.toOption.get.data should not be empty
  }
}
