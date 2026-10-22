// Probe I: name an opaque id from Scala 2.13: ThreadId (llm4s-agent) is a top-level opaque type with a
// top-level companion, ThreadId("...") in Scala 3.
import org.llm4s.agent.graph.ThreadId

object Probe extends App {
  val id = ThreadId("probe-thread")
  println(s"PASS: $id")
}
