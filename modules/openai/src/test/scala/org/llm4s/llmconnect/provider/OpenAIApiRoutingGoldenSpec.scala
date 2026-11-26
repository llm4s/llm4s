package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.config.{ OpenAIApi, OpenAIConfig }
import org.llm4s.model.{ ModelMode, ModelRegistryService, ModelRegistryTestSupport }
import org.llm4s.security.Secret
import org.llm4s.types.ProviderModelTypes.{ ModelName, ProviderId }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.io.Source
import scala.util.Using

/**
 * The API every OpenAI chat model in the default registry (the bundled data with llm4s's patches) is routed to on
 * OpenAI's own base URL, against a checked-in list. Routing follows the model data, so a routine refresh of the
 * bundled file could otherwise move models between Chat Completions and the Responses API unnoticed.
 */
final class OpenAIApiRoutingGoldenSpec extends AnyFlatSpec with Matchers:

  private val GoldenResource  = "/openai-api-routing-golden.txt"
  private val ResponsesHeader = "[responses]"
  private val ChatHeader      = "[chat-completions]"

  private lazy val registry: ModelRegistryService = ModelRegistryTestSupport.defaultService()

  /** The bare OpenAI ids of the models a chat client is built for: no `ft:` entry, no `provider/` prefix. */
  private lazy val ids: Seq[String] =
    registry
      .listByProvider(ProviderId("openai"))
      .toOption
      .toList
      .flatten
      .filter(m => m.mode == ModelMode.Chat || m.mode == ModelMode.Responses)
      .map(_.modelId.asString)
      .filterNot(id => id.startsWith("ft:") || id.contains('/'))
      .distinct
      .sorted

  private def routed(id: String): OpenAIApi =
    val config = OpenAIConfig(Secret("sk-test"), ModelName(id)).withBaseUrl(OpenAIConfig.DEFAULT_BASE_URL)
    OpenAIApiRouting.resolve(config, ProviderId("openai"), id, registry).api

  private lazy val actual: Map[OpenAIApi, Seq[String]] =
    ids.groupBy(routed).map((api, models) => api -> models.sorted)

  private def actualOn(api: OpenAIApi): Seq[String] = actual.getOrElse(api, Seq.empty)

  /** The golden file's two lists, by its section headers; `#` lines are comments. */
  private lazy val golden: Map[OpenAIApi, Seq[String]] =
    val lines = Using.resource(Source.fromInputStream(getClass.getResourceAsStream(GoldenResource), "UTF-8"))(
      _.getLines().map(_.trim).filter(l => l.nonEmpty && !l.startsWith("#")).toList
    )
    val responses = lines.dropWhile(_ != ResponsesHeader).drop(1).takeWhile(_ != ChatHeader)
    val chat      = lines.dropWhile(_ != ChatHeader).drop(1).takeWhile(_ != ResponsesHeader)
    Map(OpenAIApi.Responses -> responses.sorted, OpenAIApi.ChatCompletions -> chat.sorted)

  private def goldenOn(api: OpenAIApi): Seq[String] = golden.getOrElse(api, Seq.empty)

  private def rendered: String =
    (Seq(ResponsesHeader) ++ actualOn(OpenAIApi.Responses) ++ Seq("", ChatHeader) ++
      actualOn(OpenAIApi.ChatCompletions)).mkString("\n")

  "Routing on OpenAI's own base URL" should "send each OpenAI chat model in the registry to the API the golden file lists" in {
    val nowResponses = actualOn(OpenAIApi.Responses).toSet
    val nowChat      = actualOn(OpenAIApi.ChatCompletions).toSet
    val wasResponses = goldenOn(OpenAIApi.Responses).toSet
    val wasChat      = goldenOn(OpenAIApi.ChatCompletions).toSet
    val toResponses  = (nowResponses & wasChat).toSeq.sorted
    val toChat       = (nowChat & wasResponses).toSeq.sorted
    val added        = ((nowResponses ++ nowChat) -- (wasResponses ++ wasChat)).toSeq.sorted
    val removed      = ((wasResponses ++ wasChat) -- (nowResponses ++ nowChat)).toSeq.sorted
    val matches      = toResponses.isEmpty && toChat.isEmpty && added.isEmpty && removed.isEmpty
    assert(
      matches,
      s"""A model moved between APIs because the model data changed.
         |Moved from Chat Completions to Responses: ${toResponses.mkString(", ")}
         |Moved from Responses to Chat Completions: ${toChat.mkString(", ")}
         |New in the registry: ${added.map(id => s"$id -> ${routed(id)}").mkString(", ")}
         |No longer in the registry: ${removed.mkString(", ")}
         |Confirm against OpenAI that each is served on that API (see the 2026-11-26 probe in
         |docs/superpowers/specs/2026-11-26-openai-responses-api-design.md); correct the registry with a patch entry in
         |llm4s_model_overrides.json where it is wrong, then update modules/openai/src/test/resources$GoldenResource.
         |The routing now, for the golden file:
         |$rendered""".stripMargin
    )
  }

  it should "keep every model OpenAI refused on the Responses API on Chat Completions" in {
    // refused with a 400 "not supported with the Responses API" on 2026-11-26
    val refused = Seq(
      "gpt-audio",
      "gpt-audio-2025-08-28",
      "gpt-audio-mini",
      "gpt-audio-mini-2025-12-15",
      "gpt-audio-1.5",
      "gpt-4o-search-preview",
      "gpt-4o-search-preview-2025-03-11",
      "gpt-4o-mini-search-preview",
      "gpt-4o-mini-search-preview-2025-03-11",
      "gpt-5-search-api",
      "gpt-5-search-api-2025-10-14"
    )
    (goldenOn(OpenAIApi.ChatCompletions) should contain).allElementsOf(refused)
    (actualOn(OpenAIApi.ChatCompletions) should contain).allElementsOf(refused)
  }

  it should "send the gpt-4o models OpenAI served on the Responses API there" in {
    // answered on POST /v1/responses on 2026-11-26, though the bundled data lists no endpoints for them
    val served = Seq("gpt-4o", "gpt-4o-2024-08-06", "gpt-4o-2024-11-20", "gpt-4o-mini", "gpt-4o-mini-2024-07-18")
    (goldenOn(OpenAIApi.Responses) should contain).allElementsOf(served)
    (actualOn(OpenAIApi.Responses) should contain).allElementsOf(served)
  }
