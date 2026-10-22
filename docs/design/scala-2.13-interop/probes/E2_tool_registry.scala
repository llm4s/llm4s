// Probe E2: run tools defined elsewhere (here: in llm4s itself) through ToolRegistry from Scala 2.13.
import org.llm4s.toolapi.{ToolCallRequest, ToolRegistry}

object Probe extends App {
  val registry = ToolRegistry.empty
  val outcome  = registry.execute(ToolCallRequest("missing", ujson.Obj()))
  println(s"PASS: an unknown tool is an error value: ${outcome.isLeft}")
}
