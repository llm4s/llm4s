package org.llm4s.agent.service

import org.llm4s.agent.AgentId
import org.llm4s.agent.graph.InterruptId
import org.llm4s.agent.graph.toolloop.{ Admission, AgentInput }
import org.llm4s.testutil.TestTimeLimit
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * The stored form of a queued input (`input_json` in the SQLite and PostgreSQL queues), pinned against the code before
 * #3258 gave an admitted query its own kind: every input stored before reads back as it was written.
 */
class QueuedInputWireSpec extends AnyFlatSpec with TestTimeLimit with Matchers with EitherValues {

  private val assistant = AgentId.of("assistant").value

  private val pinned: Vector[(QueuedInput, String)] = Vector(
    QueuedInput.query("hello") -> """{"kind":"start","input":{"query":"hello"}}""",
    QueuedInput.Resume(Map(InterruptId("i1") -> ujson.Str("yes"))) ->
      """{"kind":"resume","answers":{"i1":"yes"}}""",
    QueuedInput.Recover                 -> """{"kind":"recover"}""",
    QueuedInput.Unreadable("not today") -> """{"kind":"unreadable","problem":"not today"}"""
  )

  "A stored queued input" should "keep the wire format it had before #3258" in {
    pinned.foreach { case (input, json) =>
      ujson.write(QueuedInput.toJson(input)) shouldBe json
      QueuedInput.toJson(QueuedInput.fromJson(ujson.read(json)).value) shouldBe ujson.read(json)
    }
  }

  // what `QueuedInput.admitted` stored before #3258: a start whose input carries the admission mark
  it should "read a start stored with an admission mark inside its input, as before #3258" in {
    val json =
      """{"kind":"start","input":{"query":"rewritten","admission":{"agents":["assistant"]}}}"""
    val read = QueuedInput.fromJson(ujson.read(json)).value
    read shouldBe a[QueuedInput.Start]
    upickle.default.read[AgentInput](read.asInstanceOf[QueuedInput.Start].input) shouldBe
      AgentInput("rewritten", admission = Some(Admission(Vector(assistant))))
  }
}
