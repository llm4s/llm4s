package org.llm4s.spring

import org.llm4s.java.{ JLlmClient, JLlmClientTestFactory }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.{ Bean, Configuration }

object LlmActuatorAutoConfigurationSpec {

  val stubLlmClient: LLMClient = new LLMClient {
    override def complete(c: Conversation, o: CompletionOptions): Result[Completion] =
      Right(Completion("id", 0L, "mock", "m", AssistantMessage("mock")))
    override def streamComplete(c: Conversation, o: CompletionOptions, f: StreamedChunk => Unit): Result[Completion] =
      Right(Completion("id", 0L, "mock", "m", AssistantMessage("mock")))
    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 512
  }

  @Configuration
  class MockClientConfig {
    @Bean
    def llm4sClient(): JLlmClient = JLlmClientTestFactory.create(stubLlmClient)
  }

  @Configuration
  class CustomIndicatorConfig {
    @Bean
    def llmHealthIndicator(client: JLlmClient): LlmHealthIndicator =
      new LlmHealthIndicator(client)
  }
}

class LlmActuatorAutoConfigurationSpec extends AnyFlatSpec with Matchers {
  import LlmActuatorAutoConfigurationSpec._

  private val runner = new ApplicationContextRunner()
    .withConfiguration(AutoConfigurations.of(classOf[LlmActuatorAutoConfiguration]))

  "LlmActuatorAutoConfiguration" should "register LlmHealthIndicator when actuator is on the classpath" in {
    runner
      .withUserConfiguration(classOf[MockClientConfig])
      .run(ctx => ctx.getBean(classOf[LlmHealthIndicator]) should not be null)
  }

  it should "pass the JLlmClient to the health indicator" in {
    runner
      .withUserConfiguration(classOf[MockClientConfig])
      .run { ctx =>
        val indicator = ctx.getBean(classOf[LlmHealthIndicator])
        indicator.health().getStatus.getCode shouldBe "UP"
      }
  }

  it should "allow the LlmHealthIndicator bean to be overridden" in {
    runner
      .withUserConfiguration(classOf[MockClientConfig], classOf[CustomIndicatorConfig])
      .run(ctx => ctx.getBeansOfType(classOf[LlmHealthIndicator]).size() shouldBe 1)
  }
}
