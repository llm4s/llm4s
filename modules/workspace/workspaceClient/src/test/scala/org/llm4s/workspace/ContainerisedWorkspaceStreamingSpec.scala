package org.llm4s.workspace

import org.llm4s.shared.StreamingOutputMessage
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

/**
 * The client's routing of streamed output, without a container: the runner ends stdout and stderr with an
 * `isComplete` message each, in whichever order its two readers finish, and no chunk of either may be lost.
 */
class ContainerisedWorkspaceStreamingSpec extends AnyFlatSpec with Matchers {

  private def workspace() = new ContainerisedWorkspace("/tmp/unused", "unused-image", 0)

  private def collect(order: Seq[(String, String, Boolean)]): Seq[StreamingOutputMessage] = {
    val ws     = workspace()
    val chunks = new ConcurrentLinkedQueue[StreamingOutputMessage]()
    ws.streamingHandlers.put("c1", m => { chunks.add(m); () })
    order.foreach { case (outputType, content, done) => ws.handleStreamingOutput("c1", outputType, content, done) }
    chunks.asScala.toSeq
  }

  private def stdout(chunks: Seq[StreamingOutputMessage]): String =
    chunks.filter(_.outputType == "stdout").map(_.content).mkString

  "A streaming command" should "deliver stdout that arrives after stderr has ended" in {
    val chunks = collect(Seq(("stderr", "", true), ("stdout", "Step 1 Step 2\n", false), ("stdout", "", true)))
    stdout(chunks) shouldBe "Step 1 Step 2\n"
  }

  it should "deliver stderr that arrives after stdout has ended" in {
    val chunks = collect(Seq(("stdout", "out\n", false), ("stdout", "", true), ("stderr", "err\n", false)))
    chunks.filter(_.outputType == "stderr").map(_.content).mkString shouldBe "err\n"
  }

  it should "pass each stream's end to the handler" in {
    val chunks = collect(Seq(("stdout", "", true), ("stderr", "", true)))
    chunks.filter(_.isComplete).map(_.outputType) shouldBe Seq("stdout", "stderr")
  }

  it should "ignore output for a command with no handler" in {
    val ws = workspace()
    noException should be thrownBy ws.handleStreamingOutput("unknown", "stdout", "x", isComplete = false)
  }
}
