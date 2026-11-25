package org.llm4s.llmconnect.provider

import com.sun.net.httpserver.HttpExchange
import org.llm4s.llmconnect.model.*
import org.llm4s.llmconnect.provider.BedrockTestSupport.*
import org.llm4s.model.ModelRegistryService
import org.llm4s.testkit.LocalProviderTestServer.{ sendJsonResponse, withServer }
import org.llm4s.toolapi.{ Schema, ToolBuilder }
import org.scalatest.OptionValues.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import scala.collection.mutable.ListBuffer

/**
 * `CompletionOptions.promptCache` on Bedrock Converse (RFC 0008, Part 3): the `cachePoint` blocks on the wire, as the
 * AWS SDK serialises them, what is reported for a model that cannot take what was asked for, and the cache counts
 * read back from the usage.
 */
class BedrockPromptCacheSpec extends AnyWordSpec with Matchers {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private val Claude      = "us.anthropic.claude-sonnet-4-6"
  private val ShortClaude = "anthropic.claude-sonnet-4-20250514-v1:0"
  private val Nova        = "amazon.nova-pro-v1:0"

  private val tool = ToolBuilder[String](
    "get_weather",
    "Returns current weather for a location",
    Schema
      .`object`[Map[String, Any]]("Weather params")
      .withProperty(Schema.property("location", Schema.string("City name")))
  ).withHandler(_ => Right("sunny")).build().toOption.get

  private val conv = Conversation(Seq(SystemMessage("Be brief."), UserMessage("Hi"), UserMessage("Weather?")))

  private def cache(ttl: PromptCache.Ttl, scope: PromptCache.Scope*): CompletionOptions =
    CompletionOptions().withTools(Seq(tool)).withPromptCache(PromptCache(scope.toSet, ttl))

  private val all = Seq(PromptCache.Scope.System, PromptCache.Scope.Tools, PromptCache.Scope.Conversation)

  private def read(exchange: HttpExchange): ujson.Value =
    ujson.read(new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8))

  private def usageResponse(usage: ujson.Obj): String = {
    val body = ujson.read(converseResponse("ok"))
    body("usage") = usage
    body.render()
  }

  /** The request body sent for `options` to `model`, and the completion. */
  private def exchange(
    model: String,
    options: CompletionOptions,
    response: String = converseResponse("ok")
  ): (ujson.Value, Completion) = {
    val seen       = new AtomicReference[ujson.Value](ujson.Null)
    val completion = new AtomicReference[Completion]()
    withServer("/") { ex =>
      seen.set(read(ex))
      sendJsonResponse(ex, 200, response)
    } { url =>
      val client = new BedrockClient(config(url, model))
      completion.set(client.complete(conv, options).toOption.value)
      client.close()
    }
    (seen.get, completion.get)
  }

  private def points(blocks: ujson.Value): Seq[ujson.Value] =
    blocks.arr.toSeq.flatMap(_.obj.get("cachePoint"))

  "a prompt cache on a Claude model" should {

    "put a cachePoint after the system blocks, after the tools and at the end of the last message" in {
      val (body, completion) = exchange(Claude, cache(PromptCache.Ttl.Short, all*))
      body("system").arr.last shouldBe ujson.Obj("cachePoint" -> ujson.Obj("type" -> "default"))
      body("toolConfig")("tools").arr.last shouldBe ujson.Obj("cachePoint" -> ujson.Obj("type" -> "default"))
      // two user turns merge into one message, which ends with the checkpoint
      val last = body("messages").arr.last("content").arr
      last.last shouldBe ujson.Obj("cachePoint" -> ujson.Obj("type" -> "default"))
      last.init.map(_("text").str) shouldBe Seq("Hi", "Weather?")
      completion.adjustments.filter(_.option == RequestOption.PromptCache).map(_.getClass.getSimpleName) shouldBe
        List("Dropped") // nothing was read or written: the canned usage has no cache counts
    }

    "send only the scopes asked for, and no ttl field for the short lifetime" in {
      val (body, _) = exchange(Claude, cache(PromptCache.Ttl.Short, PromptCache.Scope.System))
      points(body("system")) shouldBe Seq(ujson.Obj("type" -> "default"))
      points(body("toolConfig")("tools")) shouldBe empty
      points(body("messages").arr.last("content")) shouldBe empty
    }

    "send the 1-hour ttl where the model takes it" in {
      val (body, completion) = exchange(Claude, cache(PromptCache.Ttl.Long, PromptCache.Scope.Tools))
      points(body("toolConfig")("tools")) shouldBe Seq(ujson.Obj("type" -> "default", "ttl" -> "1h"))
      completion.adjustments.collect { case a: RequestAdjustment.Adjusted => a } shouldBe empty
    }

    "send the short lifetime to a model that keeps a cache for 5 minutes only, and report it" in {
      val (body, completion) = exchange(ShortClaude, cache(PromptCache.Ttl.Long, PromptCache.Scope.System))
      points(body("system")) shouldBe Seq(ujson.Obj("type" -> "default"))
      completion.adjustments should contain(
        RequestAdjustment.Adjusted(
          RequestOption.PromptCache,
          "System, Long",
          "System, Short",
          s"$ShortClaude keeps a prompt cache for 5 minutes only"
        )
      )
    }

    "send nothing to a Claude model the registry lists without prompt caching, and report it" in {
      val model           = "anthropic.claude-3-opus-20240229-v1:0"
      val (body, outcome) = exchange(model, cache(PromptCache.Ttl.Short, all*))
      (body.toString should not).include("cachePoint")
      outcome.adjustments shouldBe List(
        RequestAdjustment.Dropped(
          RequestOption.PromptCache,
          s"the model registry does not list prompt caching for $model"
        )
      )
    }

    "judge a Claude model the registry does not know by its family" in {
      val (body, _) = exchange(
        "arn:aws:bedrock:us-east-1:1:inference-profile/us.anthropic.claude-sonnet-9-9",
        cache(PromptCache.Ttl.Long, PromptCache.Scope.System)
      )
      points(body("system")) shouldBe Seq(ujson.Obj("type" -> "default", "ttl" -> "1h"))
    }

    "read the cache counts back, with the full input as the prompt tokens, and report nothing more" in {
      val usage = ujson.Obj(
        "inputTokens"           -> 10,
        "outputTokens"          -> 8,
        "totalTokens"           -> 2018,
        "cacheReadInputTokens"  -> 500,
        "cacheWriteInputTokens" -> 1500,
        "cacheDetails"          -> ujson.Arr(ujson.Obj("ttl" -> "1h", "inputTokens" -> 1500))
      )
      val (_, completion) = exchange(Claude, cache(PromptCache.Ttl.Long, all*), usageResponse(usage))
      val u               = completion.usage.value
      (u.promptTokens, u.cachedTokens, u.cacheCreationTokens, u.longCacheCreationTokens) shouldBe
        ((2010, Some(500), Some(1500), Some(1500)))
      completion.adjustments.filter(_.option == RequestOption.PromptCache) shouldBe empty
    }

    "read zero cache counts as none, so a model without cache prices keeps its cost estimate" in {
      val usage = ujson.Obj(
        "inputTokens"           -> 10,
        "outputTokens"          -> 8,
        "totalTokens"           -> 18,
        "cacheReadInputTokens"  -> 0,
        "cacheWriteInputTokens" -> 0
      )
      val (_, completion) = exchange(Nova, CompletionOptions(), usageResponse(usage))
      completion.usage.map(u => (u.promptTokens, u.cachedTokens, u.cacheCreationTokens)) shouldBe Some((10, None, None))
      completion.estimatedCost shouldBe defined
    }

    "report a request that cached nothing, from a stream too" in {
      val nothingCached = RequestAdjustment.Dropped(
        RequestOption.PromptCache,
        "nothing was cached: the prefix may be below the model's minimum cacheable length"
      )
      withServer("/") { ex =>
        if (isStreamRequest(ex)) sendEventStream(ex, textStream("Hi", " there"))
        else sendJsonResponse(ex, 200, converseResponse("ok"))
      } { url =>
        val client = new BedrockClient(config(url, Claude))
        val chunks = ListBuffer.empty[StreamedChunk]
        client
          .streamComplete(conv, cache(PromptCache.Ttl.Short, PromptCache.Scope.System))(chunks += _)
          .toOption
          .value
          .adjustments shouldBe List(nothingCached)
        client.complete(conv, CompletionOptions()).toOption.value.adjustments shouldBe empty
        client.close()
      }
    }

    "read cache counts from a stream's metadata" in {
      val frames = textStream("Hi", " there").init :+ eventFrame(
        "metadata",
        """{"usage":{"inputTokens":10,"outputTokens":5,"totalTokens":1015,"cacheReadInputTokens":1000}}"""
      )
      withServer("/")(sendEventStream(_, frames)) { url =>
        val client     = new BedrockClient(config(url, Claude))
        val completion = client.streamComplete(conv, cache(PromptCache.Ttl.Short, all*))(_ => ()).toOption.value
        completion.usage.map(u => (u.promptTokens, u.cachedTokens)) shouldBe Some((1010, Some(1000)))
        completion.adjustments.filter(_.option == RequestOption.PromptCache) shouldBe empty
        client.close()
      }
    }
  }

  "a prompt cache on another model" should {

    "leave the tools out on Nova, send the short lifetime, and report both" in {
      val (body, completion) = exchange(Nova, cache(PromptCache.Ttl.Long, all*))
      points(body("system")) shouldBe Seq(ujson.Obj("type" -> "default"))
      points(body("toolConfig")("tools")) shouldBe empty
      points(body("messages").arr.last("content")) shouldBe Seq(ujson.Obj("type" -> "default"))
      completion.adjustments should contain(
        RequestAdjustment.Adjusted(
          RequestOption.PromptCache,
          "System+Tools+Conversation, Long",
          "System+Conversation, Short",
          s"$Nova takes cache checkpoints in system and messages, not tools; $Nova keeps a prompt cache for 5 minutes only"
        )
      )
    }

    "report a tools-only cache on Nova as dropped" in {
      val (body, completion) = exchange(Nova, cache(PromptCache.Ttl.Short, PromptCache.Scope.Tools))
      (body.toString should not).include("cachePoint")
      completion.adjustments shouldBe List(
        RequestAdjustment.Dropped(
          RequestOption.PromptCache,
          s"$Nova takes cache checkpoints in system and messages, not tools"
        )
      )
    }

    "send nothing to a model that takes no checkpoints on Converse, and report it" in {
      val (body, completion) = exchange(Model, cache(PromptCache.Ttl.Short, all*))
      (body.toString should not).include("cachePoint")
      completion.adjustments shouldBe List(
        RequestAdjustment.Dropped(
          RequestOption.PromptCache,
          s"Converse takes cache checkpoints only from Anthropic Claude and Amazon Nova models, not $Model"
        )
      )
    }

    "report an empty scope as dropped, and send nothing" in {
      val (body, completion) = exchange(Claude, cache(PromptCache.Ttl.Short))
      (body.toString should not).include("cachePoint")
      completion.adjustments shouldBe List(
        RequestAdjustment.Dropped(RequestOption.PromptCache, "the prompt cache's scope names nothing to cache")
      )
    }

    "change nothing without a prompt cache" in {
      val (body, completion) = exchange(Claude, CompletionOptions().withTools(Seq(tool)))
      (body.toString should not).include("cachePoint")
      completion.adjustments shouldBe empty
    }
  }
}
