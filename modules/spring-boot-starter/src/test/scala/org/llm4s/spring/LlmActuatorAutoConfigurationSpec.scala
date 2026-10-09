package org.llm4s.spring

import org.llm4s.javaapi.{ JLlmClient, JLlmClientTestFactory }
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
      StarterTestSupport.indicator(client)
  }
}

class LlmActuatorAutoConfigurationSpec extends AnyFlatSpec with Matchers {
  import LlmActuatorAutoConfigurationSpec._

  private val runner = new ApplicationContextRunner()
    .withConfiguration(AutoConfigurations.of(classOf[Llm4sAutoConfiguration], classOf[LlmActuatorAutoConfiguration]))

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

  it should "switch the health indicator off with llm4s.enabled=false, even when the application supplies its own JLlmClient" in {
    // Codex review of #1592: without a property condition on LlmActuatorAutoConfiguration, this
    // combination used to fail startup: the actuator config stayed eligible through the user's
    // JLlmClient bean while the disabled Llm4sAutoConfiguration took Llm4sProperties and the
    // executor bean with it.
    runner
      .withUserConfiguration(classOf[MockClientConfig])
      .withPropertyValues("llm4s.enabled=false")
      .run { ctx =>
        ctx.getStartupFailure shouldBe null
        ctx.getBeansOfType(classOf[LlmHealthIndicator]).size() shouldBe 0
      }
  }

  // Pins the havingValue = "true" literal of this configuration's own @ConditionalOnProperty (#1592
  // added it). The absent-property tests above pass through matchIfMissing with any literal, and the
  // user configuration supplies only the client, so the indicator can come only from this
  // auto-configuration.
  it should "register LlmHealthIndicator when llm4s.enabled=true is set explicitly (havingValue = \"true\")" in {
    runner
      .withUserConfiguration(classOf[MockClientConfig])
      .withPropertyValues("llm4s.enabled=true")
      .run { ctx =>
        ctx.getStartupFailure shouldBe null
        ctx.getBeansOfType(classOf[LlmHealthIndicator]).keySet().toArray.toSeq shouldBe Seq("llmHealthIndicator")
      }
  }

  it should "compare llm4s.enabled to havingValue case-insensitively, as Spring's OnPropertyCondition does (llm4s.enabled=TRUE)" in {
    runner
      .withUserConfiguration(classOf[MockClientConfig])
      .withPropertyValues("llm4s.enabled=TRUE")
      .run { ctx =>
        ctx.getStartupFailure shouldBe null
        ctx.getBeansOfType(classOf[LlmHealthIndicator]).size() shouldBe 1
      }
  }

  it should "allow the LlmHealthIndicator bean to be overridden" in {
    runner
      .withUserConfiguration(classOf[MockClientConfig], classOf[CustomIndicatorConfig])
      .run(ctx => ctx.getBeansOfType(classOf[LlmHealthIndicator]).size() shouldBe 1)
  }
}
