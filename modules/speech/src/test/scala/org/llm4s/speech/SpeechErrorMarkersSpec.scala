package org.llm4s.speech

import org.llm4s.error.LLMError
import org.llm4s.speech.io.{ AudioIO, WavFileGenerator }
import org.llm4s.speech.stt.STTError
import org.llm4s.speech.tts.TTSError
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Pins the `llm4s-speech` rows of the "errors defined by other modules" tables in
 * `docs/guide/error-handling.md`: which speech errors are retryable (`LLMError.isRetryable`) and which are not.
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

  "An unavailable speech engine" should "be retryable" in {
    recoverable.foreach(e => withClue(e.toString)(e.isRetryable shouldBe true))
  }

  "A speech error the caller must fix" should "not be retryable" in {
    nonRecoverable.foreach(e => withClue(e.toString)(e.isRetryable shouldBe false))
  }

  "A failed transcription or synthesis" should "not be retryable, whatever its own retryable flag says" in {
    unmarked.foreach(e => withClue(e.toString)(e.isRetryable shouldBe false))
  }

  "An STTError's retryable flag" should "agree with isRetryable for the engine and input errors" in {
    (recoverable ++ nonRecoverable).collect { case e: STTError => e }.foreach { e =>
      withClue(e.toString)(e.retryable shouldBe e.isRetryable)
    }
  }

  "isRetryable" should "partition speech errors" in {
    val all = recoverable ++ nonRecoverable ++ unmarked
    all.filter(_.isRetryable) shouldBe recoverable
    all.filterNot(_.isRetryable) shouldBe nonRecoverable ++ unmarked
  }
}
