package org.llm4s.spring

import org.llm4s.error.APIError
import org.llm4s.javaapi.{ JLlmClient, JLlmClientTestFactory }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.springframework.mock.env.MockEnvironment

import java.time.Duration
import scala.jdk.CollectionConverters._

/**
 * The Spring Boot 4 indicator next to the Spring Boot 3 one, on the Boot 3 classpath, which has Boot 4's
 * `spring-boot-health` as the starter compiles against it (#1467): the two report the same status and details for
 * every outcome. `springBootStarterBoot4` runs the shared specs with the Boot 4 indicator in a Boot 4 context.
 */
class LlmBoot4HealthIndicatorSpec extends AnyFlatSpec with Matchers {

  private def client(reply: Result[Completion]): JLlmClient = JLlmClientTestFactory.create(new LLMClient {
    override def complete(c: Conversation, o: CompletionOptions): Result[Completion] = reply
    override def streamComplete(c: Conversation, o: CompletionOptions, f: StreamedChunk => Unit): Result[Completion] =
      reply
    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 512
  })

  private val ok     = Right(Completion("id", 0L, "ok", "m", AssistantMessage("ok")))
  private val failed = Left(APIError("openai", "rejected key sk-abcdefghijklmnop"))

  private def boot3(c: JLlmClient, probe: Boolean): (String, Map[String, AnyRef]) = {
    val h = new LlmHealthIndicator(c, StarterTestSupport.settings(probe), StarterTestSupport.pool).health()
    (h.getStatus.getCode, h.getDetails.asScala.toMap)
  }

  private def boot4(c: JLlmClient, probe: Boolean): (String, Map[String, AnyRef]) = {
    val h = new LlmBoot4HealthIndicator(c, StarterTestSupport.settings(probe), StarterTestSupport.pool).health()
    (h.getStatus.getCode, h.getDetails.asScala.toMap)
  }

  "LlmBoot4HealthIndicator" should "report what LlmHealthIndicator reports, without a probe and with one" in {
    for {
      reply <- Seq(ok, failed)
      probe <- Seq(false, true)
    } withClue(s"reply=$reply probe=$probe: ") {
      boot4(client(reply), probe) shouldBe boot3(client(reply), probe)
    }
    boot4(client(failed), probe = true)._1 shouldBe "DOWN"
    (boot4(client(failed), probe = true)._2("error").toString should not).include("sk-abcdefghijklmnop")
  }

  "LlmBoot4ActuatorAutoConfiguration" should "build the indicator from the properties, checking the probe settings" in {
    val props = new Llm4sProperties
    props.provider = "ollama"
    props.model = "llama3"
    val config = new LlmBoot4ActuatorAutoConfiguration
    val health =
      config.llmHealthIndicator(client(ok), props, StarterTestSupport.pool, new MockEnvironment).health()
    health.getDetails.asScala.toMap shouldBe Map("provider" -> "ollama", "model" -> "llama3", "probe" -> "disabled")

    props.health.probeTimeout = Duration.ZERO
    (the[IllegalArgumentException] thrownBy config.llmHealthIndicator(
      client(ok),
      props,
      StarterTestSupport.pool,
      new MockEnvironment
    ) should have).message("requirement failed: llm4s.health.probe-timeout must be positive")
  }
}
