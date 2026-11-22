package org.llm4s.llmconnect.provider

import com.sun.net.httpserver.HttpExchange
import org.llm4s.llmconnect.model.*
import org.llm4s.llmconnect.provider.BedrockTestSupport.*
import org.llm4s.model.ModelRegistryService
import org.llm4s.testkit.LocalProviderTestServer.{ sendJsonResponse, withServer }
import org.scalatest.OptionValues.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

/** `CompletionOptions.serviceTier` as Converse's `serviceTier`, as the AWS SDK serialises it (#2726). */
class BedrockServiceTierSpec extends AnyWordSpec with Matchers {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private val conversation = Conversation(Seq(UserMessage("Hello")))

  private def read(exchange: HttpExchange): ujson.Value =
    ujson.read(new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8))

  "a service tier" should {

    "be sent as serviceTier.type, from complete and from a stream, and Auto not at all" in {
      val bodies = new ConcurrentLinkedQueue[ujson.Value]()
      withServer("/") { ex =>
        bodies.add(read(ex))
        if (isStreamRequest(ex)) sendEventStream(ex, textStream("Hi", " there"))
        else sendJsonResponse(ex, 200, converseResponse("Hi"))
      } { url =>
        val client = new BedrockClient(config(url))
        Seq(ServiceTier.Default, ServiceTier.Flex, ServiceTier.Priority).foreach { tier =>
          client.complete(conversation, CompletionOptions().withServiceTier(tier)).toOption.value.adjustments shouldBe
            empty
        }
        client
          .streamComplete(conversation, CompletionOptions().withServiceTier(ServiceTier.Flex), _ => ())
          .toOption
          .value
          .adjustments shouldBe empty
        client
          .complete(conversation, CompletionOptions().withServiceTier(ServiceTier.Auto))
          .toOption
          .value
          .adjustments shouldBe List(
          RequestAdjustment.NotNeeded(
            RequestOption.ServiceTier,
            "Bedrock serves a request that names no tier at its standard tier"
          )
        )
        client.complete(conversation, CompletionOptions()).toOption.value.adjustments shouldBe empty
        client.close()
      }
      bodies.asScala.toList.map(_.obj.get("serviceTier")) shouldBe List(
        Some(ujson.Obj("type" -> "default")),
        Some(ujson.Obj("type" -> "flex")),
        Some(ujson.Obj("type" -> "priority")),
        Some(ujson.Obj("type" -> "flex")),
        None,
        None
      )
    }
  }
}
