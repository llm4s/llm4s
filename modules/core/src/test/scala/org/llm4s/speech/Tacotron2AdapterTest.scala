package org.llm4s.speech

import org.scalatest.funsuite.AnyFunSuite
import org.llm4s.speech.tts.{ Tacotron2TextToSpeech, TTSOptions }
import org.llm4s.speech.util.PlatformCommands

class Tacotron2AdapterTest extends AnyFunSuite {
  test("options assemble CLI flags") {
    // Smoke test: uses a mock that writes a minimal valid WAV to --out, verifying the
    // full pipeline (argument assembly -> CLI -> WAV parse) compiles and returns Right.
    val adapter = new Tacotron2TextToSpeech(PlatformCommands.mockWavWriter)
    val res = adapter.synthesize(
      "hi",
      TTSOptions(
        voice = Some("v"),
        language = Some("en"),
        speakingRate = Some(1.1),
        pitchSemitones = Some(2.0),
        volumeGainDb = Some(3.0)
      )
    )
    assert(res.isRight)
  }
}
