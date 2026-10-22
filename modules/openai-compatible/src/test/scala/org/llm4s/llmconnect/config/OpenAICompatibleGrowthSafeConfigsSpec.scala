package org.llm4s.llmconnect.config

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

/**
 * `DeepSeekConfig`, `ZaiConfig`, `XaiConfig`, `MistralConfig` and `CohereConfig` follow the growth-prone
 * pattern (#2264): a private constructor and `copy`, a companion `apply` carrying the defaults, a short
 * `apply(apiKey, model)` for Java and Kotlin, and a `with*` setter per field.
 */
class OpenAICompatibleGrowthSafeConfigsSpec extends AnyFlatSpec with Matchers {

  private val timeouts = ProviderTimeouts(Some(3.seconds), Some(7.seconds))

  /** What the spec needs of one config type, read through its own accessors. */
  final private case class Fields(
    apiKey: String,
    model: String,
    baseUrl: String,
    contextWindow: Int,
    reserveCompletion: Int,
    timeouts: ProviderTimeouts
  )

  /** One config type under test: its builders, setters and the defaults `apply` should fill. */
  private trait Subject {
    type C <: ProviderConfig
    def name: String
    def defaultBaseUrl: String
    def defaultContextWindow: Int
    def defaultReserve: Int
    def full(
      apiKey: String,
      model: String,
      baseUrl: String,
      contextWindow: Int,
      reserveCompletion: Int,
      timeouts: ProviderTimeouts
    ): C
    def withDefaults(apiKey: String, model: String): C
    def short(apiKey: String, model: String): C
    def fields(c: C): Fields
    def withApiKey(c: C, v: String): C
    def withBaseUrl(c: C, v: String): C
    def withContextWindow(c: C, v: Int): C
    def withReserveCompletion(c: C, v: Int): C
    def withModel(c: C, v: String): C
    def withTimeouts(c: C, v: ProviderTimeouts): C
  }

  private object DeepSeek extends Subject {
    type C = DeepSeekConfig
    val name                 = "DeepSeekConfig"
    val defaultBaseUrl       = DeepSeekConfig.DEFAULT_BASE_URL
    val defaultContextWindow = DeepSeekConfig.DEFAULT_CONTEXT_WINDOW
    val defaultReserve       = DeepSeekConfig.DEFAULT_RESERVE_COMPLETION
    def full(a: String, m: String, b: String, cw: Int, rc: Int, t: ProviderTimeouts) =
      DeepSeekConfig(a, m, b, cw, rc, t)
    def withDefaults(a: String, m: String) = DeepSeekConfig(apiKey = a, model = m, timeouts = ProviderTimeouts.default)
    def short(a: String, m: String)        = DeepSeekConfig(a, m)
    def fields(c: C) = Fields(c.apiKey, c.model, c.baseUrl, c.contextWindow, c.reserveCompletion, c.timeouts)
    def withApiKey(c: C, v: String)             = c.withApiKey(v)
    def withBaseUrl(c: C, v: String)            = c.withBaseUrl(v)
    def withContextWindow(c: C, v: Int)         = c.withContextWindow(v)
    def withReserveCompletion(c: C, v: Int)     = c.withReserveCompletion(v)
    def withModel(c: C, v: String)              = c.withModel(v)
    def withTimeouts(c: C, v: ProviderTimeouts) = c.withTimeouts(v)
  }

  private object Zai extends Subject {
    type C = ZaiConfig
    val name                 = "ZaiConfig"
    val defaultBaseUrl       = ZaiConfig.DEFAULT_BASE_URL
    val defaultContextWindow = ZaiConfig.DEFAULT_CONTEXT_WINDOW
    val defaultReserve       = ZaiConfig.DEFAULT_RESERVE_COMPLETION
    def full(a: String, m: String, b: String, cw: Int, rc: Int, t: ProviderTimeouts) = ZaiConfig(a, m, b, cw, rc, t)
    def withDefaults(a: String, m: String) = ZaiConfig(apiKey = a, model = m, timeouts = ProviderTimeouts.default)
    def short(a: String, m: String)        = ZaiConfig(a, m)
    def fields(c: C) = Fields(c.apiKey, c.model, c.baseUrl, c.contextWindow, c.reserveCompletion, c.timeouts)
    def withApiKey(c: C, v: String)             = c.withApiKey(v)
    def withBaseUrl(c: C, v: String)            = c.withBaseUrl(v)
    def withContextWindow(c: C, v: Int)         = c.withContextWindow(v)
    def withReserveCompletion(c: C, v: Int)     = c.withReserveCompletion(v)
    def withModel(c: C, v: String)              = c.withModel(v)
    def withTimeouts(c: C, v: ProviderTimeouts) = c.withTimeouts(v)
  }

  private object Xai extends Subject {
    type C = XaiConfig
    val name                 = "XaiConfig"
    val defaultBaseUrl       = XaiConfig.DEFAULT_BASE_URL
    val defaultContextWindow = XaiConfig.DEFAULT_CONTEXT_WINDOW
    val defaultReserve       = XaiConfig.DEFAULT_RESERVE_COMPLETION
    def full(a: String, m: String, b: String, cw: Int, rc: Int, t: ProviderTimeouts) = XaiConfig(a, m, b, cw, rc, t)
    def withDefaults(a: String, m: String) = XaiConfig(apiKey = a, model = m, timeouts = ProviderTimeouts.default)
    def short(a: String, m: String)        = XaiConfig(a, m)
    def fields(c: C) = Fields(c.apiKey, c.model, c.baseUrl, c.contextWindow, c.reserveCompletion, c.timeouts)
    def withApiKey(c: C, v: String)             = c.withApiKey(v)
    def withBaseUrl(c: C, v: String)            = c.withBaseUrl(v)
    def withContextWindow(c: C, v: Int)         = c.withContextWindow(v)
    def withReserveCompletion(c: C, v: Int)     = c.withReserveCompletion(v)
    def withModel(c: C, v: String)              = c.withModel(v)
    def withTimeouts(c: C, v: ProviderTimeouts) = c.withTimeouts(v)
  }

  private object Mistral extends Subject {
    type C = MistralConfig
    val name                 = "MistralConfig"
    val defaultBaseUrl       = MistralConfig.DEFAULT_BASE_URL
    val defaultContextWindow = MistralConfig.DEFAULT_CONTEXT_WINDOW
    val defaultReserve       = MistralConfig.DEFAULT_RESERVE_COMPLETION
    def full(a: String, m: String, b: String, cw: Int, rc: Int, t: ProviderTimeouts) =
      MistralConfig(a, m, b, cw, rc, t)
    def withDefaults(a: String, m: String) = MistralConfig(apiKey = a, model = m, timeouts = ProviderTimeouts.default)
    def short(a: String, m: String)        = MistralConfig(a, m)
    def fields(c: C) = Fields(c.apiKey, c.model, c.baseUrl, c.contextWindow, c.reserveCompletion, c.timeouts)
    def withApiKey(c: C, v: String)             = c.withApiKey(v)
    def withBaseUrl(c: C, v: String)            = c.withBaseUrl(v)
    def withContextWindow(c: C, v: Int)         = c.withContextWindow(v)
    def withReserveCompletion(c: C, v: Int)     = c.withReserveCompletion(v)
    def withModel(c: C, v: String)              = c.withModel(v)
    def withTimeouts(c: C, v: ProviderTimeouts) = c.withTimeouts(v)
  }

  private object Cohere extends Subject {
    type C = CohereConfig
    val name                 = "CohereConfig"
    val defaultBaseUrl       = CohereConfig.DEFAULT_BASE_URL
    val defaultContextWindow = CohereConfig.DEFAULT_CONTEXT_WINDOW
    val defaultReserve       = CohereConfig.DEFAULT_RESERVE_COMPLETION
    def full(a: String, m: String, b: String, cw: Int, rc: Int, t: ProviderTimeouts) =
      CohereConfig(a, m, b, cw, rc, t)
    def withDefaults(a: String, m: String) = CohereConfig(apiKey = a, model = m, timeouts = ProviderTimeouts.default)
    def short(a: String, m: String)        = CohereConfig(a, m)
    def fields(c: C) = Fields(c.apiKey, c.model, c.baseUrl, c.contextWindow, c.reserveCompletion, c.timeouts)
    def withApiKey(c: C, v: String)             = c.withApiKey(v)
    def withBaseUrl(c: C, v: String)            = c.withBaseUrl(v)
    def withContextWindow(c: C, v: Int)         = c.withContextWindow(v)
    def withReserveCompletion(c: C, v: Int)     = c.withReserveCompletion(v)
    def withModel(c: C, v: String)              = c.withModel(v)
    def withTimeouts(c: C, v: ProviderTimeouts) = c.withTimeouts(v)
  }

  private val subjects: Seq[Subject] = Seq(DeepSeek, Zai, Xai, Mistral, Cohere)

  subjects.foreach { s =>
    s.name should "fill every field after model with its default in apply" in {
      val expected =
        Fields("k", "m", s.defaultBaseUrl, s.defaultContextWindow, s.defaultReserve, ProviderTimeouts.default)
      s.fields(s.withDefaults("k", "m")) shouldBe expected
      s.fields(s.short("k", "m")) shouldBe expected
    }

    it should "keep every argument given to the full apply" in {
      s.fields(s.full("k", "m", "http://localhost:1", 1000, 100, timeouts)) shouldBe
        Fields("k", "m", "http://localhost:1", 1000, 100, timeouts)
    }

    it should "change one field per setter and leave the rest" in {
      val base = s.full("k", "m", "http://localhost:1", 1000, 100, ProviderTimeouts.default)
      val f    = s.fields(base)
      s.fields(s.withApiKey(base, "k2")) shouldBe f.copy(apiKey = "k2")
      s.fields(s.withBaseUrl(base, "http://localhost:2")) shouldBe f.copy(baseUrl = "http://localhost:2")
      s.fields(s.withContextWindow(base, 2000)) shouldBe f.copy(contextWindow = 2000)
      s.fields(s.withReserveCompletion(base, 200)) shouldBe f.copy(reserveCompletion = 200)
      s.fields(s.withModel(base, "m2")) shouldBe f.copy(model = "m2")
      s.fields(s.withTimeouts(base, timeouts)) shouldBe f.copy(timeouts = timeouts)
    }

    it should "redact the API key a setter sets" in {
      val base = s.full("k", "m", "http://localhost:1", 1000, 100, ProviderTimeouts.default)
      (s.withApiKey(base, "secret-key-123456").toString should not).include("secret-key-123456")
    }
  }

  "The growth-safe configs" should "keep their constructors and copy private" in {
    assertDoesNotCompile("""new DeepSeekConfig("k", "m", "u", 1, 0, ProviderTimeouts.default)""")
    assertDoesNotCompile("""DeepSeekConfig("k", "m").copy(model = "x")""")
    assertDoesNotCompile("""new ZaiConfig("k", "m", "u", 1, 0, ProviderTimeouts.default)""")
    assertDoesNotCompile("""ZaiConfig("k", "m").copy(model = "x")""")
    assertDoesNotCompile("""new XaiConfig("k", "m", "u", 1, 0, ProviderTimeouts.default)""")
    assertDoesNotCompile("""XaiConfig("k", "m").copy(model = "x")""")
    assertDoesNotCompile("""new MistralConfig("k", "m", "u", 1, 0, ProviderTimeouts.default)""")
    assertDoesNotCompile("""MistralConfig("k", "m").copy(model = "x")""")
    assertDoesNotCompile("""new CohereConfig("k", "m", "u", 1, 0, ProviderTimeouts.default)""")
    assertDoesNotCompile("""CohereConfig("k", "m").copy(model = "x")""")
  }
}
