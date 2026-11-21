package org.llm4s.tapir

import org.llm4s.annotation.Experimental
import sttp.model.Method
import sttp.tapir.Endpoint

/**
 * One tapir endpoint offered to a model as a tool, before it is built into a
 * [[org.llm4s.toolapi.ToolFunction ToolFunction]] by [[EndpointTools.registry]].
 *
 * The tool's parameters are the endpoint's inputs: each path capture, query parameter and request body becomes a
 * property of the tool's JSON schema, under its tapir name (the body under `body`). A header input is a parameter
 * only when it is listed in [[modelHeaders]]; any other header, and every authentication input, is the
 * application's: its value comes from [[headers]], never from the model.
 *
 * Create one with [[EndpointTools.tool]].
 *
 * @param name             the tool's name, as the model calls it; `[a-zA-Z0-9_-]{1,64}`
 * @param endpoint         the tapir endpoint the tool calls
 * @param requiresApproval whether the tool changes something, so that an agent should ask before it runs; read it
 *                         with [[EndpointTools.requiringApproval]]
 * @param modelHeaders     the header inputs the model may set, by name, compared without regard to case
 * @param headers          the headers the application sends on every call, such as `Authorization`
 * @param description      the description shown to the model; the endpoint's own description, summary or
 *                         `METHOD /path` when absent
 */
@Experimental
final case class EndpointTool private (
  name: String,
  endpoint: Endpoint[?, ?, ?, ?, Any],
  requiresApproval: Boolean,
  modelHeaders: Set[String],
  headers: Map[String, String],
  description: Option[String]
) {

  /** Whether an agent should ask before the tool runs. */
  def withRequiresApproval(requiresApproval: Boolean): EndpointTool = copy(requiresApproval = requiresApproval)

  /** The header inputs the model may set, replacing any given before. Never list an authentication header. */
  def withModelHeaders(names: String*): EndpointTool = copy(modelHeaders = names.toSet)

  /** The headers the application sends on every call, replacing any given before. */
  def withHeaders(headers: Map[String, String]): EndpointTool = copy(headers = headers)

  /** The description shown to the model, in place of the endpoint's own. */
  def withDescription(description: String): EndpointTool = copy(description = Some(description))

  /** The description shown to the model, or the endpoint's own when `None`. */
  def withDescription(description: Option[String]): EndpointTool = copy(description = description)

  private[tapir] def isModelHeader(header: String): Boolean =
    modelHeaders.exists(_.equalsIgnoreCase(header))

  private[tapir] def applicationHeader(header: String): Option[String] =
    headers.collectFirst { case (k, v) if k.equalsIgnoreCase(header) => v }
}

/** Creates an [[EndpointTool]]; [[EndpointTools.tool]] is the usual way in. */
@Experimental
object EndpointTool {

  /**
   * An [[EndpointTool]] for `endpoint`, with no model headers and no application headers.
   *
   * @param requiresApproval whether an agent should ask before the tool runs
   */
  def apply(
    name: String,
    endpoint: Endpoint[?, ?, ?, ?, Any],
    requiresApproval: Boolean
  ): EndpointTool =
    new EndpointTool(name, endpoint, requiresApproval, Set.empty, Map.empty, None)

  /** The methods that do not change anything on the server, by RFC 9110's definition of a safe method. */
  private val SafeMethods: Set[Method] = Set(Method.GET, Method.HEAD, Method.OPTIONS)

  private[tapir] def isSafe(endpoint: Endpoint[?, ?, ?, ?, ?]): Boolean =
    Inputs.method(endpoint).forall(SafeMethods.contains)
}
