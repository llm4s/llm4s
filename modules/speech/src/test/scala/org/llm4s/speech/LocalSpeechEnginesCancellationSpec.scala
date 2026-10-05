package org.llm4s.speech

import org.llm4s.speech.stt.WhisperSpeechToText
import org.llm4s.speech.tts.Tacotron2TextToSpeech
import org.llm4s.testkit.ProviderModuleChecks
import org.scalatest.flatspec.AnyFlatSpec

import java.nio.file.{ Files, Path }
import scala.concurrent.duration.*

/**
 * The speech engines that run a command-line program honour interruption (design section 4.4): the
 * call returns `Left(CancelledError)` with the interrupt flag still set - an `InterruptedException`
 * does not escape a `Result`-returning method - and the program it started is stopped, not left
 * running after its caller was cancelled.
 *
 * The "program" is a shell snippet that records its process id and then sleeps for 30 seconds. The
 * interrupt waits until that id has been recorded, so the program is known to be running when its
 * caller is cancelled.
 */
class LocalSpeechEnginesCancellationSpec extends AnyFlatSpec {

  private val isWindows = System.getProperty("os.name").toLowerCase.contains("win")

  private def withSleepingProgram(test: (Seq[String], Path) => Any): Unit = {
    assume(!isWindows, "needs a POSIX shell")
    val pidFile = Files.createTempFile("llm4s-cancel-", ".pid")
    // `exec` so that the recorded id is the sleeper's, not a shell that would outlive it.
    val command = Seq("sh", "-c", "echo $$ > '" + pidFile + "'; exec sleep 30", "sh")
    val outcome = scala.util.Try(test(command, pidFile))
    Files.deleteIfExists(pidFile): Unit
    outcome.fold(error => throw error, _ => ())
  }

  /** Returns once `pidFile` holds the program's process id, and fails if it does not within 10 seconds. */
  private def awaitStarted(pidFile: Path): Unit = {
    val deadline = System.nanoTime() + 10.seconds.toNanos
    while (new String(Files.readAllBytes(pidFile)).trim.isEmpty && System.nanoTime() < deadline) Thread.sleep(20)
    assert(new String(Files.readAllBytes(pidFile)).trim.nonEmpty, "the program never recorded its process id")
  }

  /** Whether the process whose id `pidFile` holds has ended, waiting up to 5 seconds for it to. */
  private def programStopped(pidFile: Path): Boolean = {
    val pid      = new String(Files.readAllBytes(pidFile)).trim.toLong
    val deadline = System.nanoTime() + 5.seconds.toNanos
    def alive    = ProcessHandle.of(pid).map(_.isAlive).orElse(false)
    while (alive && System.nanoTime() < deadline) Thread.sleep(50)
    !alive
  }

  "WhisperSpeechToText" should "return CancelledError and stop its program when its thread is interrupted" in
    withSleepingProgram { (command, pidFile) =>
      val engine = new WhisperSpeechToText(command)
      ProviderModuleChecks.assertCallCancelsOnceReady("whisper transcribe")(
        engine.transcribe(AudioInput.BytesAudio(Array.fill[Byte](200)(1), 16000))
      )(awaitStarted(pidFile))
      assert(programStopped(pidFile), "the program was still running after the call was cancelled")
    }

  "Tacotron2TextToSpeech" should "return CancelledError and stop its program when its thread is interrupted" in
    withSleepingProgram { (command, pidFile) =>
      val engine = new Tacotron2TextToSpeech(command)
      ProviderModuleChecks.assertCallCancelsOnceReady("tacotron2 synthesize")(engine.synthesize("Hello"))(
        awaitStarted(pidFile)
      )
      assert(programStopped(pidFile), "the program was still running after the call was cancelled")
    }
}
