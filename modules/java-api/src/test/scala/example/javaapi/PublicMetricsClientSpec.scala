package example.javaapi

import org.llm4s.javaapi.{ JLlmClient, Llm4s, LlmResult }
import org.llm4s.llmconnect.config.OllamaConfig
import org.llm4s.metrics.MetricsCollector
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * #1817: `Llm4s.createClient(config, metrics)` is public API, callable from outside `org.llm4s`. It was
 * `private[llm4s]`, which Java callers could see in the bytecode but Scala callers could not use; this spec lives in a
 * package of its own so that it does not compile unless the method is public.
 */
class PublicMetricsClientSpec extends AnyFlatSpec with Matchers {

  "Llm4s.createClient(config, metrics)" should "be callable from an application's own package" in {
    val result: LlmResult[JLlmClient] =
      Llm4s.createClient(OllamaConfig("llama3.2", "http://127.0.0.1:1"), MetricsCollector.noop)
    result.isSuccess shouldBe true
  }

  it should "be the static method, with this signature, that Java callers call" in {
    // The static forwarder on the `Llm4s` class is what `Llm4s.createClient(config, metrics)` in Java calls.
    val method = Class
      .forName("org.llm4s.javaapi.Llm4s")
      .getMethods
      .find(m =>
        m.getName == "createClient" && m.getParameterTypes.toSeq == Seq(
          classOf[org.llm4s.llmconnect.config.ProviderConfig],
          classOf[MetricsCollector]
        )
      )
    method.map(m => java.lang.reflect.Modifier.isStatic(m.getModifiers)) shouldBe Some(true)
  }
}
