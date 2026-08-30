package org.llm4s.speech

import org.scalatest.funsuite.AnyFunSuite
import org.llm4s.speech.stt.{ WhisperSpeechToText, STTOptions }
import org.llm4s.speech.util.PlatformCommands
import java.nio.file.Files

class WhisperAdapterTest extends AnyFunSuite {
  test("whisper adapter handles bytes input") {
    val fakeWav = Files.createTempFile("fake", ".wav")
    Files.write(fakeWav, Array[Byte](0, 0, 0, 0))
    val stt = new WhisperSpeechToText(PlatformCommands.mockSuccess, model = "base") // Mock command that always succeeds
    val res = stt.transcribe(AudioInput.FileAudio(fakeWav), STTOptions(language = Some("en")))
    assert(res.isRight)
  }
}
