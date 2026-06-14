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

  it should "fail context startup when llm4s.provider is missing" in {
    runner
      .withPropertyValues("llm4s.model=llama3")
      .run { ctx =>
        ctx.getStartupFailure should not be null
        ctx.getStartupFailure.getMessage should include("llm4s.provider")
      }
  }

  it should "fail context startup when llm4s.model is missing" in {
    runner
      .withPropertyValues("llm4s.provider=ollama")
      .run { ctx =>
        ctx.getStartupFailure should not be null
        ctx.getStartupFailure.getMessage should include("llm4s.model")
      }
  }

  it should "fail context startup when llm4s.api-key is missing for openai" in {
    runner
      .withPropertyValues("llm4s.provider=openai", "llm4s.model=gpt-4o")
      .run { ctx =>
        ctx.getStartupFailure should not be null
        ctx.getStartupFailure.getMessage should include("api-key")
      }
  }

  it should "skip all bean registration when llm4s.enabled=false" in {
    runner
      .withPropertyValues("llm4s.enabled=false")
      .run { ctx =>
        ctx.getBeansOfType(classOf[JLlmClient]).size() shouldBe 0
        ctx.getBeansOfType(classOf[LLM4STemplate]).size() shouldBe 0
      }
  }

  it should "register beans when llm4s.enabled is absent (matchIfMissing=true)" in {
    runner
      .withUserConfiguration(classOf[MockClientConfig])
      .run(ctx => ctx.getBean(classOf[LLM4STemplate]) should not be null)
  }

  it should "register JLlmClient from properties for ollama (no api-key required)" in {
    runner
      .withPropertyValues(
        "llm4s.provider=ollama",
        "llm4s.model=llama3",
        "llm4s.base-url=http://localhost:11434"
      )
      .run { ctx =>
        ctx.getStartupFailure shouldBe null
        ctx.getBean(classOf[LLM4STemplate]) should not be null
      }
  }

  it should "bind all llm4s.* properties to Llm4sProperties" in {
    runner
      .withUserConfiguration(classOf[MockClientConfig])
      .withPropertyValues(
        "llm4s.provider=anthropic",
        "llm4s.model=claude-sonnet-4-5-latest",
        "llm4s.api-key=sk-ant-test",
        "llm4s.base-url=https://api.anthropic.com",
        "llm4s.context-window=32000",
        "llm4s.reserve-completion=2048"
      )
      .run { ctx =>
        val props = ctx.getBean(classOf[Llm4sProperties])
        props.provider shouldBe "anthropic"
        props.model shouldBe "claude-sonnet-4-5-latest"
        props.apiKey shouldBe "sk-ant-test"
        props.baseUrl shouldBe "https://api.anthropic.com"
        props.contextWindow shouldBe 32000
        props.reserveCompletion shouldBe 2048
      }
  }
}
