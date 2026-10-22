// Probe E: define a tool with ToolBuilder, which takes an implicit upickle ReadWriter for the tool's result.
import org.llm4s.toolapi.{Schema, ToolBuilder}

object Probe extends App {
  val schema = Schema.`object`[Map[String, Any]]("Add two numbers")
  val tool   = ToolBuilder[Map[String, Any], Double]("add", "Adds two numbers", schema)
  println(s"PASS: ${tool.getClass.getSimpleName}")
}
