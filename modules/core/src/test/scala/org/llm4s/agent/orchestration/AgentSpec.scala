package org.llm4s.agent.orchestration

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.concurrent.ScalaFutures
import scala.concurrent.duration._
import scala.concurrent.{ Future, ExecutionContext }

/**
 * Unit tests for Agent abstraction following LLM4S testing patterns
 */
class AgentSpec extends AnyFlatSpec with Matchers with ScalaFutures {
  implicit val ec: ExecutionContext = ExecutionContext.global

  "Agent.fromFunction" should "create a working functional agent" in {
    val agent = Agent.fromFunction[String, Int]("test-agent")(input => Right(input.length))

    agent.name shouldBe "test-agent"
    agent.id.value should not be empty

    whenReady(agent.execute("hello"))(result => result shouldBe Right(5))
  }

  "Agent.fromUnsafeFunction" should "handle exceptions safely" in {
    val agent = Agent.fromUnsafeFunction[String, Int]("unsafe-agent") { input =>
      if (input.isEmpty) throw new RuntimeException("Empty input")
      else input.length
    }

    // Should succeed with valid input
    whenReady(agent.execute("hello"))(result => result shouldBe Right(5))

    // Should handle exceptions
    whenReady(agent.execute(""))(result => result.isLeft shouldBe true)
  }

  "Agent.fromFuture" should "handle Future operations properly" in {
    val agent = Agent.fromFuture[String, String]("future-agent") { input =>
      Future.successful(Right(s"processed: $input"))
    }

    whenReady(agent.execute("test"))(result => result shouldBe Right("processed: test"))
  }

  "Agent.fromFuture" should "handle Future failures" in {
    val agent = Agent.fromFuture[String, String]("failing-future-agent") { _ =>
      Future.failed(new RuntimeException("Future failed"))
    }

    whenReady(agent.execute("test")) { result =>
      result.isLeft shouldBe true
      result.swap
        .getOrElse(throw new RuntimeException("Expected Left"))
        .shouldBe(a[OrchestrationError.NodeExecutionError])
    }
  }

  "Agent.constant" should "always return the same value" in {
    val agent = Agent.constant[String, Int]("constant-agent", 42)

    whenReady(agent.execute("anything"))(result => result shouldBe Right(42))
    whenReady(agent.execute("different"))(result => result shouldBe Right(42))
  }

  "Agent.simpleFailure" should "always fail with the specified error" in {
    val agent = Agent.simpleFailure[String, Int]("failure-agent", "Always fails")

    whenReady(agent.execute("test")) { result =>
      result.isLeft shouldBe true
      result.swap
        .getOrElse(throw new RuntimeException("Expected Left"))
        .shouldBe(a[OrchestrationError.NodeExecutionError])
    }
  }

  "Agent composition" should "work with different input/output types" in {
    val stringToInt = Agent.fromFunction[String, Int]("string-to-int")(s => Right(s.length))
    val intToString = Agent.fromFunction[Int, String]("int-to-string")(i => Right(s"Length: $i"))

    // Test sequential execution (manual composition for now)
    val input = "hello world"
    whenReady(stringToInt.execute(input)) { step1 =>
      step1 shouldBe Right(11)

      whenReady(intToString.execute(step1.getOrElse(0)))(step2 => step2 shouldBe Right("Length: 11"))
    }
  }

  "Agent with long-running operation" should "complete eventually" in {
    val slowAgent = Agent.fromFuture[String, String]("slow-agent") { input =>
      Future {
        Thread.sleep(100) // Short delay for test
        Right(s"slow: $input")
      }
    }

    whenReady(slowAgent.execute("test"), timeout(5.seconds))(result => result shouldBe Right("slow: test"))
  }
}
