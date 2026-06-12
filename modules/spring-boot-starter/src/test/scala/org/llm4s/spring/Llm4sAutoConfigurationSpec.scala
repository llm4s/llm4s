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

object Llm4sAutoConfigurationSpec {

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
  class CustomTemplateConfig {
    @Bean
    def llm4sTemplate(client: JLlmClient): LLM4STemplate = new LLM4STemplate(client)
  }
}

class Llm4sAutoConfigurationSpec extends AnyFlatSpec with Matchers {
  import Llm4sAutoConfigurationSpec._

  private val runner = new ApplicationContextRunner()
    .withConfiguration(AutoConfigurations.of(classOf[Llm4sAutoConfiguration]))

  "Llm4sAutoConfiguration" should "register LLM4STemplate when a JLlmClient bean is present" in {
    runner
      .withUserConfiguration(classOf[MockClientConfig])
      .run(ctx => ctx.getBean(classOf[LLM4STemplate]) should not be null)
  }

  it should "use the user-provided JLlmClient bean (ConditionalOnMissingBean)" in {
    runner
      .withUserConfiguration(classOf[MockClientConfig])
      .run { ctx =>
        ctx.getBean(classOf[JLlmClient]) should not be null
        ctx.getBeansOfType(classOf[JLlmClient]).size() shouldBe 1
      }
  }

  it should "register Llm4sProperties bound to the 'llm4s' prefix" in {
    runner
      .withUserConfiguration(classOf[MockClientConfig])
      .withPropertyValues("llm4s.provider=openai", "llm4s.model=gpt-4o")
      .run { ctx =>
        val props = ctx.getBean(classOf[Llm4sProperties])
        props.provider shouldBe "openai"
        props.model shouldBe "gpt-4o"
      }
  }

  it should "allow the LLM4STemplate bean to be overridden" in {
    runner
      .withUserConfiguration(classOf[MockClientConfig], classOf[CustomTemplateConfig])
      .run(ctx => ctx.getBeansOfType(classOf[LLM4STemplate]).size() shouldBe 1)
  }
}
