package org.llm4s.spring

import org.llm4s.javaapi.{ FakeLlmClient, LlmErrorKind, LlmException }
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.springframework.web.bind.annotation.{ GetMapping, RequestParam, RestController }

import java.util.concurrent.Executors

/**
 * Runs the controller of `docs/guide/java-migration.md` (`AskController.java`) on an `LLM4STemplate` over a
 * `FakeLlmClient`. It lives in `src/test/web`, which only this project compiles: the Spring Boot 4 project recompiles
 * the starter's `src/test/scala` and `src/test/java`, and has no spring-web.
 */
class AskControllerSpec extends AnyWordSpec with Matchers {

  private def withTemplate[A](fake: FakeLlmClient)(body: LLM4STemplate => A): A = {
    val executor = Executors.newSingleThreadExecutor()
    try body(new LLM4STemplate(fake.client(), executor))
    finally executor.shutdownNow(): Unit
  }

  "The guide's controller" should {

    "answer the question with the model's reply, sending the question as the prompt" in {
      val fake = FakeLlmClient.replying("Paris")
      withTemplate(fake)(llm => new AskController(llm).ask("What is the capital of France?")) shouldBe "Paris"
      fake.prompts() should contain only "What is the capital of France?"
    }

    "throw the LlmException of a failed call, for Spring's error handling" in {
      val fake   = FakeLlmClient.builder().failWith(LlmErrorKind.RATE_LIMIT, "slow down").build()
      val thrown = intercept[LlmException](withTemplate(fake)(llm => new AskController(llm).ask("q")))
      thrown.getKind shouldBe LlmErrorKind.RATE_LIMIT
    }

    "be a REST controller that serves GET /ask with the question as a request parameter" in {
      classOf[AskController].isAnnotationPresent(classOf[RestController]) shouldBe true
      val ask = classOf[AskController].getMethod("ask", classOf[String])
      ask.getAnnotation(classOf[GetMapping]).value().toList shouldBe List("/ask")
      ask.getParameters.head.getAnnotation(classOf[RequestParam]).value() shouldBe "question"
    }
  }
}
