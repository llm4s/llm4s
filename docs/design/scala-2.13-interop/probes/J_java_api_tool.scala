// Probe J: define a tool with llm4s-java-api's JTool.builder (#1484), the workaround for probe E's ToolBuilder, and
// let an agent call it: the fake Ollama asks for the tool, then answers with text.
import java.util.concurrent.atomic.AtomicReference

import org.llm4s.javaapi.{JTool, JToolArgs, Llm4s}

object Probe extends App {
  val seen = new AtomicReference[String]("not called")
  val add = JTool
    .builder("add", "Adds two integers")
    .param("a", classOf[Integer], "the first")
    .param("b", classOf[Integer], "the second")
    .handler { (args: JToolArgs) =>
      val sum = args.getInt("a") + args.getInt("b")
      seen.set(s"add(${args.getInt("a")}, ${args.getInt("b")}) = $sum")
      Integer.valueOf(sum)
    }
    .build()
    .get()

  FakeOllama.withReplies(Seq(FakeOllama.ToolCall("add", """{"a":2,"b":3}"""), FakeOllama.Text("The sum is 5."))) {
    port =>
      FakeOllama.configure(port)
      val client = Llm4s.createDefaultClient().get()
      val result = Llm4s.createAgentWithTools(client, java.util.List.of(add)).get().run("What is 2 + 3?")
      if (result.isFailure) println(s"FAIL: ${result.getError().getMessage}")
      else println(s"PASS: ${seen.get()}, answer: ${result.get().answer().orElse("none")}")
  }
}
