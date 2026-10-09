package org.llm4s.llmconnect.provider

import com.sun.net.httpserver.HttpExchange
import org.llm4s.llmconnect.model.*
import org.llm4s.llmconnect.provider.BedrockTestSupport.*
import org.llm4s.model.ModelRegistryService
import org.llm4s.testkit.LocalProviderTestServer.{ sendJsonResponse, withServer }
import org.llm4s.testutil.SmallStack
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import software.amazon.awssdk.core.document.Document

import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.{ AtomicInteger, AtomicReference }
import scala.jdk.CollectionConverters.*

/**
 * A Converse response's `toolUse.input` is the model's own JSON. The AWS SDK's bundled Jackson parser
 * admits it up to about 1,000 levels of nesting, deeper than the 512 the library handles (#1562): a
 * `Document` that deep can be converted into a `ToolCall` and then rendered back to Bedrock on the
 * next turn, each step recursing once per level. The input is therefore measured against the same
 * bound as every other model-written JSON before it is converted, and one over it is a malformed
 * tool call, as it is for Anthropic and Ollama (#1648).
 */
class BedrockToolInputDepthSpec extends AnyWordSpec with Matchers {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  /** `{"a":{"a":...1...}}`, `n` objects deep. */
  private def nested(n: Int): String = "{\"a\":" * n + "1" + "}" * n

  /** A reply whose only block is a `toolUse` of `get_weather` with the given `input`. */
  private def toolUseReply(input: String): String =
    s"""{"output":{"message":{"role":"assistant","content":[{"toolUse":{"toolUseId":"tc-1","name":"get_weather","input":$input}}]}},"stopReason":"tool_use","usage":{"inputTokens":8,"outputTokens":4,"totalTokens":12}}"""

  /**
   * Asks once, getting a `toolUse` reply with `input`, then answers the call in a second turn, so the
   * arguments are rendered back through the SDK as Bedrock would receive them. The whole round trip
   * runs on a 1 MB stack, so an overflow anywhere in it - reading the reply, or rendering the call
   * back - is reported here as a `Left` instead of depending on the JVM's default stack size.
   */
  private def roundTrip(input: String): Either[Throwable, Either[(String, String), String]] = {
    val requests = new AtomicInteger(0)
    val sentBack = new AtomicReference[Option[String]](None)
    val outcome =
      new AtomicReference[Either[Throwable, Either[(String, String), String]]](Left(new Exception("not run")))
    withServer("/") { (exchange: HttpExchange) =>
      if (requests.getAndIncrement() == 0) sendJsonResponse(exchange, 200, toolUseReply(input))
      else {
        sentBack.set(Some(new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)))
        sendJsonResponse(exchange, 200, converseResponse("Sunny in Paris."))
      }
    } { url =>
      val client = new BedrockClient(config(url))
      outcome.set(SmallStack.run {
        val first = Conversation(Seq(UserMessage("weather?")))
        client.complete(first, CompletionOptions()).flatMap { completion =>
          val turn = Conversation(first.messages ++ Seq(completion.message, ToolMessage("Sunny", "tc-1")))
          client.complete(turn, CompletionOptions())
        } match {
          case Right(_) =>
            val body = sentBack.get().getOrElse("")
            if (body.contains("\"toolUseId\":\"tc-1\"")) Right(s"sent the call back in ${body.length} bytes")
            else Right(s"finished without sending the call back ($body)")
          case Left(e) => Left((e.getClass.getSimpleName, e.message))
        }
      })
    }
    outcome.get()
  }

  "BedrockClient.complete" should {

    "refuse a toolUse input nested too deeply as a malformed call, never convert or send it back" in {
      // 600 levels: past the library's 512, within what the SDK's parser admits.
      roundTrip(nested(600)) match {
        case Right(Left((kind, message))) =>
          kind shouldBe "ProcessingError"
          message should include("malformed tool call")
          message should include("512")
        case Right(Right(other)) => fail(s"expected a Left, but the reply was accepted and $other")
        case Left(thrown)        => fail(s"expected a Left, but the round trip threw $thrown")
      }
    }

    "accept a toolUse input nested to the limit and send it back on the next turn" in {
      roundTrip(nested(512)) match {
        case Right(Right(sent)) => sent should startWith("sent the call back in")
        case Right(Left(error)) => fail(s"expected the call to be accepted, got $error")
        case Left(thrown)       => fail(s"expected the call to be accepted, but the round trip threw $thrown")
      }
    }
  }

  /** `{"a":{"a":...1...}}` as a `Document`, `n` maps deep, built without recursion. */
  private def nestedDocument(n: Int): Document =
    (1 to n).foldLeft(Document.fromNumber(1))((inner, _) => Document.fromMap(Map("a" -> inner).asJava))

  "BedrockClient.exceedsDepth" should {

    "count maps and lists together, from zero for a scalar" in {
      BedrockClient.exceedsDepth(Document.fromString("x"), 0) shouldBe false
      BedrockClient.exceedsDepth(Document.fromList(List(Document.fromNumber(1)).asJava), 0) shouldBe true
      BedrockClient.exceedsDepth(Document.fromList(List(Document.fromNumber(1)).asJava), 1) shouldBe false
      val listInMap = Document.fromMap(Map("a" -> Document.fromList(List(Document.fromNumber(1)).asJava)).asJava)
      BedrockClient.exceedsDepth(listInMap, 1) shouldBe true
      BedrockClient.exceedsDepth(listInMap, 2) shouldBe false
    }

    "draw the line exactly at the limit" in {
      BedrockClient.exceedsDepth(nestedDocument(512), 512) shouldBe false
      BedrockClient.exceedsDepth(nestedDocument(513), 512) shouldBe true
    }

    "measure a document of any depth on a small stack" in {
      SmallStack.run(BedrockClient.exceedsDepth(nestedDocument(100000), 512)) shouldBe Right(true)
    }
  }

  "BedrockClient.toolCallArguments" should {

    "convert an input at the limit and refuse one past it, naming the limit" in {
      BedrockClient.toolCallArguments(nestedDocument(512)).map(_.obj.keySet) shouldBe Right(Set("a"))
      BedrockClient.toolCallArguments(nestedDocument(513)) match {
        case Left(e) =>
          e.getClass.getSimpleName shouldBe "ProcessingError"
          e.message should include("malformed tool call: arguments are nested more than 512 levels deep")
        case Right(v) => fail(s"expected a Left, got $v")
      }
    }
  }
}
