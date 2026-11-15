package org.llm4s.springai

import org.llm4s.javaapi.{ FakeLlmClient, JLlmClient }
import org.llm4s.spring.Llm4sAutoConfiguration
import org.llm4s.testutil.FixtureChatClient
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner

import java.util.function.Supplier
import scala.io.Source
import scala.jdk.CollectionConverters.*

class Llm4sSpringAiAutoConfigurationSpec extends AnyFlatSpec with Matchers {

  private val runner = new ApplicationContextRunner()
    .withConfiguration(AutoConfigurations.of(classOf[Llm4sAutoConfiguration], classOf[Llm4sSpringAiAutoConfiguration]))

  private val fixtureProvider = Seq(
    "llm4s.providers.provider=fx",
    "llm4s.providers.fx.provider=fixturechat",
    "llm4s.providers.fx.model=fixture-model",
    "llm4s.providers.fx.api-key=sk-fixture"
  )

  "with the llm4s starter" should "expose its client as a ChatModel that a ChatClient answers through" in {
    runner.withPropertyValues(fixtureProvider*).run { ctx =>
      ctx.getStartupFailure shouldBe null
      val model = ctx.getBean(classOf[ChatModel])
      model shouldBe a[Llm4sChatModel]
      ChatClient.create(model).prompt().user("hi").call().content() shouldBe FixtureChatClient.Reply
      model.call(new Prompt("hi")).getMetadata.getModel shouldBe "fixture-model"
    }
  }

  it should "adapt the application's own JLlmClient bean" in {
    val fake = FakeLlmClient.replying("from the application's client")
    runner
      .withBean(classOf[JLlmClient], (() => fake.client()): Supplier[JLlmClient])
      .run { ctx =>
        ChatClient.create(ctx.getBean(classOf[ChatModel])).prompt().user("hi").call().content() shouldBe
          "from the application's client"
        fake.prompts().get(0) shouldBe "hi"
      }
  }

  it should "keep a ChatModel the application defines" in {
    val own = Llm4sChatModel.of(FakeLlmClient.replying("own").client())
    runner
      .withPropertyValues(fixtureProvider*)
      .withBean(classOf[ChatModel], (() => own): Supplier[ChatModel])
      .run(ctx => (ctx.getBean(classOf[ChatModel]) should be).theSameInstanceAs(own))
  }

  "without a JLlmClient" should "register no ChatModel" in {
    runner.withPropertyValues("llm4s.enabled=false").run { ctx =>
      ctx.getStartupFailure shouldBe null
      ctx.getBeansOfType(classOf[ChatModel]).isEmpty shouldBe true
    }
  }

  "without Spring AI on the classpath" should "register no ChatModel" in {
    runner
      .withPropertyValues(fixtureProvider*)
      .withClassLoader(new FilteredClassLoader("org.springframework.ai"))
      .run { ctx =>
        ctx.getStartupFailure shouldBe null
        ctx.containsBean("llm4sChatModel") shouldBe false
      }
  }

  "the module" should "list its auto-configuration for Spring Boot to find" in {
    val files = getClass.getClassLoader
      .getResources("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")
      .asScala
      .toList
    val names = files.flatMap { url =>
      val source = Source.fromURL(url)
      val lines  = source.getLines().map(_.trim).filter(_.nonEmpty).toList
      source.close()
      lines
    }
    names should contain(classOf[Llm4sSpringAiAutoConfiguration].getName)
  }
}
