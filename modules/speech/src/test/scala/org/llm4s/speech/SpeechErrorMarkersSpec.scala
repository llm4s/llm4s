package org.llm4s.speech

import org.llm4s.error.{ LLMError, NonRecoverableError, RecoverableError }
import org.llm4s.speech.io.{ AudioIO, WavFileGenerator }
import org.llm4s.speech.stt.STTError
import org.llm4s.speech.tts.TTSError
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Pins the `llm4s-speech` rows of the "errors defined by other modules" tables in
 * `docs/guide/error-handling.md`: which speech errors are recoverable, which are not, and which carry no marker
 * (and so are reported as not recoverable by the total `LLMError.isRecoverable`).
 */
class SpeechErrorMarkersSpec extends AnyFlatSpec with Matchers {

  private val recoverable: List[LLMError] = List(
    STTError.EngineNotAvailable("down"),
    TTSError.EngineNotAvailable("down")
  )

  private val nonRecoverable: List[LLMError] = List(
    STTError.UnsupportedFormat("bad", "ogg", List("wav")),
    STTError.InvalidInput("bad"),
    WavFileGenerator.WavGenerationFailed("bad rate"),
    WavFileGenerator.WavSaveFailed("disk full"),
    AudioIO.SaveFailed("disk full")
  )

  private val unmarked: List[LLMError] = List(
    STTError.ProcessingFailed("no speech"),
    TTSError.SynthesisFailed("empty body")
  )

  "Speech errors marked recoverable" should "be RecoverableErrors that isRecoverable accepts" in {
    recoverable.foreach { e =>
      withClue(e.toString) {
        e shouldBe a[RecoverableError]
        LLMError.isRecoverable(e) shouldBe true
      }
    }
  }

  "Speech errors marked non-recoverable" should "be NonRecoverableErrors that isRecoverable rejects" in {
    nonRecoverable.foreach { e =>
      withClue(e.toString) {
        e shouldBe a[NonRecoverableError]
        LLMError.isRecoverable(e) shouldBe false
      }
    }
  }

  "Unmarked speech errors" should "carry neither marker and be reported as not recoverable" in {
    unmarked.foreach { e =>
      withClue(e.toString) {
        e should not be a[RecoverableError]
        e should not be a[NonRecoverableError]
        LLMError.isRecoverable(e) shouldBe false
      }
    }
  }

  "An STTError's retryable flag" should "agree with its marker wherever it has one" in {
    (recoverable ++ nonRecoverable).collect { case e: STTError => e }.foreach { e =>
      withClue(e.toString)(e.retryable shouldBe LLMError.isRecoverable(e))
    }
  }

  "recoverableErrors and nonRecoverableErrors" should "partition speech errors without throwing" in {
    val all = recoverable ++ nonRecoverable ++ unmarked
    LLMError.recoverableErrors(all) shouldBe recoverable
    LLMError.nonRecoverableErrors(all) shouldBe nonRecoverable ++ unmarked
  }
}
