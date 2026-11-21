package org.llm4s.workspace

import org.llm4s.shared.{ ExecuteCommandCommand, WorkspaceAgentResponse, WriteFileCommand, WriteFileResponse }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.CompletableFuture
import scala.util.{ Failure, Success }

/**
 * The client's wait for a response when its caller is interrupted, without a container: an interrupt is a give-up
 * the client sees (and then cancels the command), never an exception that escapes past the cancellation.
 */
class ContainerisedWorkspaceAwaitSpec extends AnyFlatSpec with Matchers {

  private def workspace() = new ContainerisedWorkspace("/tmp/unused", "unused-image", 0)

  "An interrupted wait for a command" should "end the wait as a failure, which sendCommand cancels" in {
    // everything built before the interrupt: a class's first initialisation (upickle's) may itself wait interruptibly
    val (ws, command) = (workspace(), ExecuteCommandCommand("c1", "sleep 60"))
    val future        = new CompletableFuture[WorkspaceAgentResponse]()
    Thread.currentThread().interrupt()
    val result = ws.awaitResponse(command, future)
    Thread.interrupted(): Unit // cleared, for the rest of the suite; sendCommand restores it for its caller
    result match {
      case Failure(_: InterruptedException) => ()
      case other                            => fail(s"expected an interrupted wait, got $other")
    }
  }

  "An interrupted wait for a file change" should "wait on for its response, keeping the interrupt" in {
    val future        = new CompletableFuture[WorkspaceAgentResponse]()
    val response      = WriteFileResponse("w1", success = true, "f", 1L)
    val answer        = new Thread(() => { Thread.sleep(200); future.complete(response): Unit })
    val (ws, command) = (workspace(), WriteFileCommand("w1", "f", "x"))
    answer.start()
    Thread.currentThread().interrupt()
    val result = ws.awaitResponse(command, future)
    val kept   = Thread.interrupted()
    answer.join()
    result shouldBe Success(response)
    kept shouldBe true
  }
}
