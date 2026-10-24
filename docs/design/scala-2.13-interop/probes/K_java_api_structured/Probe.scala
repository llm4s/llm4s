// Probe K: structured output through llm4s-java-api's JLlmClient.complete(prompt, Class) (#1486), the workaround for
// probe F's completeStructured: the answer is read into a Java record (City.java beside this file).
import org.llm4s.javaapi.FakeLlmClient

object Probe extends App {
  val fake   = FakeLlmClient.replying("""{"name":"Tokyo","country":"Japan","population":14000000}""")
  val result = fake.client().complete("The largest city in Japan", classOf[City])
  if (result.isFailure) println(s"FAIL: ${result.getError().getMessage}")
  else println(s"PASS: ${result.get()}")
}
