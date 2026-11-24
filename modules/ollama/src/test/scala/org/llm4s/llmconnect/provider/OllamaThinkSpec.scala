package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.config.OllamaConfig
import org.llm4s.llmconnect.model.*
import org.llm4s.llmconnect.provider.OllamaThink.{ Controls, Plan }
import org.llm4s.model.ModelRegistryService
import org.llm4s.testkit.LocalProviderTestServer.{ sendJsonResponse, withServer }
import org.scalatest.OptionValues.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*

/**
 * `thinking` and `reasoning` as Ollama's `think` (RFC 0008): the mapping for each kind of model, and the client asking
 * `/api/show` once for the values a model takes and sending `think` on `/api/chat`, against a local server.
 */
class OllamaThinkSpec extends AnyWordSpec with Matchers {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private val onOff  = Controls(Nil, on = true, off = true)
  private val levels = Controls(List(ReasoningEffort.Low, ReasoningEffort.Medium, ReasoningEffort.High), false, false)
  private val both   = levels.copy(on = true, off = true)
  private val none   = Controls(Nil, on = false, off = true)

  private def opts(thinking: Option[Thinking] = None, reasoning: Option[ReasoningEffort] = None) =
    CompletionOptions().withThinking(thinking).withReasoning(reasoning)

  private val budget = Thinking.budget(500).toOption.value

  "OllamaThink.plan" should {

    "send nothing and report nothing when neither option is set" in {
      OllamaThink.plan("m", CompletionOptions(), onOff) shouldBe Plan(None, Nil)
    }

    "send Off as false, reporting the reasoning it overrides" in {
      OllamaThink.plan("m", opts(Some(Thinking.Off)), onOff) shouldBe Plan(Some(ujson.False), Nil)
      OllamaThink.plan("m", opts(Some(Thinking.Off), Some(ReasoningEffort.High)), both) shouldBe Plan(
        Some(ujson.False),
        List(RequestAdjustment.Adjusted(RequestOption.Reasoning, "high", "false", "thinking = Off takes precedence"))
      )
    }

    "send Off as the lowest level to a model that cannot turn thinking off" in {
      OllamaThink.plan("gpt-oss:20b", opts(Some(Thinking.Off)), levels) shouldBe Plan(
        Some(ujson.Str("low")),
        List(RequestAdjustment.Adjusted(RequestOption.Thinking, "Off", "low", "gpt-oss:20b cannot turn thinking off"))
      )
    }

    "send a level to a model that takes levels, the nearest one when it lacks the one asked for" in {
      OllamaThink.plan("m", opts(reasoning = Some(ReasoningEffort.Medium)), levels) shouldBe
        Plan(Some(ujson.Str("medium")), Nil)
      OllamaThink.plan("m", opts(reasoning = Some(ReasoningEffort.Max)), both) shouldBe Plan(
        Some(ujson.Str("high")),
        List(RequestAdjustment.Adjusted(RequestOption.Reasoning, "max", "high", "m has no max level"))
      )
    }

    "send true for a level to a model that takes on and off" in {
      OllamaThink.plan("qwen3", opts(reasoning = Some(ReasoningEffort.Low)), onOff) shouldBe Plan(
        Some(ujson.True),
        List(
          RequestAdjustment.Adjusted(
            RequestOption.Reasoning,
            "low",
            "true",
            "qwen3 takes thinking on or off, not a level"
          )
        )
      )
    }

    "send Adaptive as true, or leave a model that takes only levels at its default" in {
      OllamaThink.plan("m", opts(Some(Thinking.Adaptive)), onOff) shouldBe Plan(Some(ujson.True), Nil)
      OllamaThink.plan("m", opts(Some(Thinking.Adaptive)), levels) shouldBe Plan(
        None,
        List(RequestAdjustment.NotNeeded(RequestOption.Thinking, "m sets how much it thinks by its level"))
      )
      OllamaThink.plan("m", opts(Some(Thinking.Adaptive), Some(ReasoningEffort.High)), levels) shouldBe
        Plan(Some(ujson.Str("high")), Nil)
    }

    "send a budget as true where it can, and drop it where it cannot" in {
      OllamaThink.plan("m", opts(Some(budget)), onOff) shouldBe Plan(
        Some(ujson.True),
        List(
          RequestAdjustment.Adjusted(
            RequestOption.Thinking,
            "Budget(500)",
            "true",
            "Ollama's think takes no token budget"
          )
        )
      )
      val dropped = RequestAdjustment.Dropped(RequestOption.Thinking, "Ollama takes no thinking budget for m")
      OllamaThink.plan("m", opts(Some(budget)), levels) shouldBe Plan(None, List(dropped))
      OllamaThink.plan("m", opts(Some(budget), Some(ReasoningEffort.Low)), levels) shouldBe
        Plan(Some(ujson.Str("low")), List(dropped))
    }

    "send nothing to a model that does not think" in {
      OllamaThink.plan("m", opts(Some(Thinking.Off), Some(ReasoningEffort.High)), none) shouldBe Plan(
        None,
        List(
          RequestAdjustment.NotNeeded(RequestOption.Thinking, "m does not think"),
          RequestAdjustment.Dropped(RequestOption.Reasoning, "m does not think")
        )
      )
      OllamaThink.plan("m", opts(Some(Thinking.Adaptive)), none).adjustments shouldBe
        List(RequestAdjustment.Dropped(RequestOption.Thinking, "m does not think"))
    }
  }

  "OllamaThink.fromShow and byName" should {

    "read thinking.values, keeping the levels that are efforts, lowest first" in {
      val reply = ujson.read("""{"thinking":{"values":["high","low","turbo",true,false],"default":"medium"}}""")
      OllamaThink.fromShow("m", reply) shouldBe
        Some(Controls(List(ReasoningEffort.Low, ReasoningEffort.High), on = true, off = true))
      OllamaThink.fromShow("m", ujson.read("""{"capabilities":["completion"]}""")) shouldBe
        Some(Controls(Nil, on = false, off = false))
      OllamaThink.fromShow("gpt-oss:20b", ujson.read("""{"capabilities":["completion","thinking"]}""")) shouldBe
        Some(levels)
      OllamaThink.fromShow("m", ujson.read("""{"modelfile":"FROM m"}""")) shouldBe None
      OllamaThink.fromShow("m", ujson.Arr()) shouldBe None
    }

    "give gpt-oss levels and every other model on and off" in {
      OllamaThink.byName("gpt-oss:120b") shouldBe levels
      OllamaThink.byName("hf.co/unsloth/GPT-OSS-20B-GGUF") shouldBe levels
      OllamaThink.byName("qwen3:8b") shouldBe onOff
    }
  }

  "OllamaClient" should {

    val chatReply = """{"message":{"role":"assistant","content":"ok","thinking":"hmm"},"done":true}"""

    /** Runs `test` against a server whose `/api/show` answers `show`, recording each path and chat request. */
    def server(show: Option[String])(test: (OllamaClient, AtomicInteger, ConcurrentLinkedQueue[ujson.Value]) => Any) = {
      val shows = new AtomicInteger()
      val chats = new ConcurrentLinkedQueue[ujson.Value]()
      withServer("/") { ex =>
        val body = new String(ex.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
        if (ex.getRequestURI.getPath == "/api/show") {
          shows.incrementAndGet()
          show.fold(sendJsonResponse(ex, 500, """{"error":"down"}"""))(sendJsonResponse(ex, 200, _))
        } else {
          chats.add(ujson.read(body))
          sendJsonResponse(ex, 200, chatReply)
        }
      } { url =>
        val client =
          new OllamaClient(OllamaConfig(model = "qwen3", baseUrl = url, contextWindow = 4096, reserveCompletion = 512))
        test(client, shows, chats)
        client.close()
      }
    }

    "ask /api/show once, send the level it lists, and keep the thinking the reply carries" in {
      server(Some("""{"thinking":{"values":["low","medium","high"],"default":"medium"}}""")) { (client, shows, chats) =>
        val high       = CompletionOptions().withReasoning(ReasoningEffort.High)
        val completion = client.complete(Conversation(Seq(UserMessage("Hi"))), high).toOption.value
        client.streamComplete(Conversation(Seq(UserMessage("Hi"))), high)(_ => ()).isRight shouldBe true
        completion.adjustments shouldBe empty
        completion.thinking shouldBe Some("hmm")
        chats.asScala.map(_("think")).toList shouldBe List(ujson.Str("high"), ujson.Str("high"))
        shows.get shouldBe 1
      }
    }

    "send no think and ask nothing when neither option is set" in {
      server(Some("""{"thinking":{"values":[true,false]}}""")) { (client, shows, chats) =>
        client.complete(Conversation(Seq(UserMessage("Hi"))), CompletionOptions()).isRight shouldBe true
        chats.asScala.head.obj.contains("think") shouldBe false
        shows.get shouldBe 0
      }
    }

    "follow the model name while /api/show fails, and ask again on the next request" in {
      server(None) { (client, shows, chats) =>
        val off = CompletionOptions().withThinking(Thinking.Off)
        client.complete(Conversation(Seq(UserMessage("Hi"))), off).toOption.value.adjustments shouldBe empty
        client.complete(Conversation(Seq(UserMessage("Hi"))), off).isRight shouldBe true
        chats.asScala.map(_("think")).toList shouldBe List(ujson.False, ujson.False)
        shows.get shouldBe 2
      }
    }

    "send no think to a model whose capabilities lack thinking, on a server without thinking values" in {
      server(Some("""{"capabilities":["completion","tools"]}""")) { (client, shows, chats) =>
        val high = CompletionOptions().withReasoning(ReasoningEffort.High)
        client.complete(Conversation(Seq(UserMessage("Hi"))), high).toOption.value.adjustments shouldBe List(
          RequestAdjustment.Dropped(RequestOption.Reasoning, "qwen3 does not think")
        )
        chats.asScala.head.obj.contains("think") shouldBe false
        shows.get shouldBe 1
      }
    }

    "keep the model name's controls when /api/show lists none" in {
      server(Some("""{"capabilities":["completion","thinking"]}""")) { (client, shows, chats) =>
        val budgeted = CompletionOptions().withThinking(budget)
        client.complete(Conversation(Seq(UserMessage("Hi"))), budgeted).toOption.value.adjustments shouldBe List(
          RequestAdjustment.Adjusted(
            RequestOption.Thinking,
            "Budget(500)",
            "true",
            "Ollama's think takes no token budget"
          )
        )
        client.complete(Conversation(Seq(UserMessage("Hi"))), budgeted).isRight shouldBe true
        chats.asScala.map(_("think")).toList shouldBe List(ujson.True, ujson.True)
        shows.get shouldBe 1
      }
    }
  }
}
