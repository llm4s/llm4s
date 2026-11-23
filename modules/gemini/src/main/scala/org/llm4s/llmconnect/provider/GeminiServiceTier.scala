package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.model.{ CompletionOptions, RequestAdjustment, RequestOption, ServiceTier }

import scala.util.Try

/**
 * `CompletionOptions.serviceTier` on the Gemini API and on Vertex AI (#2726).
 *
 *  - '''Gemini API''': the request's `serviceTier`, `standard` (the default when it is left out), `flex` or `priority`
 *    (https://ai.google.dev/gemini-api/docs/generate-content/flex-inference,
 *    https://ai.google.dev/gemini-api/docs/generate-content/priority-inference, and the API reference's `ServiceTier`).
 *    `Auto` is not sent: Gemini picks no tier on its own, and a request that names none is served at standard.
 *  - '''Vertex AI''': the `X-Vertex-AI-LLM-Shared-Request-Type` header, `flex` or `priority`, which uses the project's
 *    Provisioned Throughput first and then that tier
 *    (https://docs.cloud.google.com/vertex-ai/generative-ai/docs/priority-paygo,
 *    https://docs.cloud.google.com/gemini-enterprise-agent-platform/models/deploy/flex-paygo). `Auto` and `Default`
 *    send no header, which is Vertex AI's standard routing. Flex PayGo is served on the `global` endpoint only.
 *
 * `ServiceTier.Other` is sent as written on both. The tier that served a request (#2746) is the Gemini API's
 * `x-gemini-service-tier` response header (`standard`, `flex` or `priority`;
 * https://ai.google.dev/gemini-api/docs/generate-content/priority-inference), else `usageMetadata.trafficType`, which
 * Vertex AI reports: `ON_DEMAND` (standard), `ON_DEMAND_FLEX`, `ON_DEMAND_PRIORITY`, or another such as
 * `PROVISIONED_THROUGHPUT`, kept as `Other` (https://docs.cloud.google.com/vertex-ai/generative-ai/docs/priority-paygo).
 */
private[provider] object GeminiServiceTier {

  /** The Gemini API's `serviceTier` for `options`, and what is reported. */
  def gemini(options: CompletionOptions): (Option[String], List[RequestAdjustment]) =
    options.serviceTier match {
      case None                         => (None, Nil)
      case Some(ServiceTier.Auto)       => (None, notNeeded("Gemini"))
      case Some(ServiceTier.Default)    => (Some("standard"), Nil)
      case Some(ServiceTier.Flex)       => (Some("flex"), Nil)
      case Some(ServiceTier.Priority)   => (Some("priority"), Nil)
      case Some(ServiceTier.Other(raw)) => (Some(raw), Nil)
    }

  /** The header for `options` on Vertex AI, and what is reported. */
  def vertex(options: CompletionOptions): (Map[String, String], List[RequestAdjustment]) =
    options.serviceTier match {
      case None                                         => (Map.empty, Nil)
      case Some(ServiceTier.Auto | ServiceTier.Default) => (Map.empty, notNeeded("Vertex AI"))
      case Some(ServiceTier.Flex)                       => (Map(SharedRequestType -> "flex"), Nil)
      case Some(ServiceTier.Priority)                   => (Map(SharedRequestType -> "priority"), Nil)
      case Some(ServiceTier.Other(raw))                 => (Map(SharedRequestType -> raw), Nil)
    }

  /** The Gemini API response header that names the tier that served a request. */
  val ServedTierHeader = "x-gemini-service-tier"

  /** The tier the Gemini API's [[ServedTierHeader]] names, `None` when `headers` has none. */
  def served(headers: Map[String, Seq[String]]): Option[ServiceTier] =
    headers
      .collectFirst { case (name, values) if name.equalsIgnoreCase(ServedTierHeader) => values }
      .flatMap(_.headOption)
      .map(_.trim)
      .filter(_.nonEmpty)
      .map {
        case "standard" => ServiceTier.Default
        case "flex"     => ServiceTier.Flex
        case "priority" => ServiceTier.Priority
        case raw        => ServiceTier.Other(raw)
      }

  /** The tier a response's or chunk's `usageMetadata.trafficType` names, `None` when it names none. */
  def trafficType(json: ujson.Value): Option[ServiceTier] =
    Try(json("usageMetadata")("trafficType").str).toOption.filter(_.nonEmpty).collect {
      case "ON_DEMAND"                              => ServiceTier.Default
      case "ON_DEMAND_FLEX"                         => ServiceTier.Flex
      case "ON_DEMAND_PRIORITY"                     => ServiceTier.Priority
      case raw if raw != "TRAFFIC_TYPE_UNSPECIFIED" => ServiceTier.Other(raw)
    }

  /** The Vertex AI header that names a pay-as-you-go tier. */
  val SharedRequestType = "X-Vertex-AI-LLM-Shared-Request-Type"

  private def notNeeded(provider: String): List[RequestAdjustment] =
    List(
      RequestAdjustment.NotNeeded(
        RequestOption.ServiceTier,
        s"$provider serves a request that names no tier at its standard tier"
      )
    )
}
