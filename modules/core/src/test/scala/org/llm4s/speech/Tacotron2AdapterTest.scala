package org.llm4s.speech

import org.scalatest.funsuite.AnyFunSuite
import org.llm4s.speech.tts.{ Tacotron2TextToSpeech, TTSOptions }
import org.llm4s.speech.util.PlatformCommands

class Tacotron2AdapterTest extends AnyFunSuite {
  test("options assemble CLI flags") {
    // This is a smoke test; we can't run the CLI here. We just ensure method compiles and returns error/success type.
    val adapter = new Tacotron2TextToSpeech(PlatformCommands.echo) // Cross-platform echo
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
