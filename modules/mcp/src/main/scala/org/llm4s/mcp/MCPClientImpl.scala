package org.llm4s.mcp

import cats.implicits._
import org.llm4s.error.{ CancelledError, LLMError, SimpleError }
import org.llm4s.toolapi._
import org.llm4s.types.Result
import org.slf4j.LoggerFactory
import ujson.Value

import java.util.concurrent.atomic.AtomicLong
import scala.util.{ Failure, Success, Try }

/**
 * `MCPClient` implementation that connects to an MCP server over JSON-RPC.
 *
 * == Transport auto-detection ==
 * For HTTP-based server configs (`StreamableHTTPTransport` or `SSETransport`),
 * this client first attempts to connect using the Streamable HTTP transport. If the server
 * refuses the `initialize` POST with a 4xx other than 401 or 403, it falls back to the
 * 2024-11-05 HTTP+SSE transport (a `GET` of the same URL, which opens the event stream).
 *
 * == Handshake ==
 * The client offers protocol version [[MCPClientImpl.LatestProtocolVersion]] on every transport, accepts
 * any of [[MCPClientImpl.SupportedProtocolVersions]] in reply, and sends the agreed version on every
 * later HTTP request (`MCP-Protocol-Version`). It declares no client capabilities. A request the server
 * sends it is answered: `ping` with an empty result, anything else with method-not-found (-32601).
 *
 * == Failures in `getTools` ==
 * `getTools()` returns a `Left` for any failure during tool discovery (network error, JSON-RPC error,
 * unreadable listing, missing transport), so "the server has no tools" (`Right(Seq.empty)`) is distinct from
 * "the server could not be reached". An interrupted call returns `Left(CancelledError)` with the thread's
 * interrupt flag still set (design section 4.4).
 *
 * == Lost sessions ==
 * When a Streamable HTTP or HTTP+SSE server answers 404 for the client's session (it expired, or the server
 * restarted), or a stdio server's process has exited and been started again, the client runs the handshake again
 * and retries the request once (#3072). Only those requests are retried: the server refused them or never
 * received them, so none had run. A request that failed in any other way may have run and is never re-sent.
 *
 * == Thread safety ==
 * This class is not thread-safe. Concurrent calls to `initialize`, `getTools`,
 * or `close` from different threads require external synchronisation.
 *
 * @param config Server configuration including transport type, URL/command, and timeout.
 */
class MCPClientImpl(config: MCPServerConfig) extends MCPClient {
  private val logger                                      = LoggerFactory.getLogger(getClass)
  private[mcp] var transport: Option[MCPTransportImpl]    = None
  private val requestId                                   = new AtomicLong(0)
  private var initialized                                 = false
  @volatile private var toolHints: Map[String, ToolHints] = Map.empty
  private var negotiatedVersion: Option[String]           = None
  private var serverCaps: Option[Value]                   = None

  logger.info(s"MCPClientImpl created for server: ${config.name}")

  /**
   * The transport and the server's reply to `initialize`: on the transport already open (a handshake run again after
   * a lost session, #3072), or on a new one. An HTTP config tries Streamable HTTP first and falls back to HTTP+SSE
   * when the server refuses the `initialize` POST with a 4xx (#3079).
   */
  private def connect(): Result[(MCPTransportImpl, JsonRpcResponse)] =
    transport match {
      case Some(t) =>
        t.sendRequest(createInitializeRequest()) match {
          case Right(response)                 => Right(t -> response)
          case Left(cancelled: CancelledError) => Left(cancelled)
          case Left(error)                     => Left(SimpleError(s"Initialize request failed: ${error.message}"))
        }
      case None =>
        config.transport match {
          case http: StreamableHTTPTransport => tryHttpTransportWithFallback(http.url, http.name, http.http)
          case http: SSETransport            => tryHttpTransportWithFallback(http.url, http.name, http.http)
          case stdio: StdioTransport =>
            val stdioTransport = StdioTransportImpl.of(stdio, config.timeout)
            transport = Some(stdioTransport)
            connect()
        }
    }

  // Unified HTTP transport logic: try Streamable HTTP first, fall back to HTTP+SSE
  private def tryHttpTransportWithFallback(
    url: String,
    name: String,
    http: MCPHttpOptions
  ): Result[(MCPTransportImpl, JsonRpcResponse)] = {
    logger.info(s"Attempting to connect using Streamable HTTP transport to $url")
    val newTransport = new StreamableHTTPTransportImpl(url, name, config.timeout, http)

    newTransport.sendRequest(createInitializeRequest()) match {
      case Right(response) =>
        logger.info(s"Connected to ${config.name} using Streamable HTTP transport")
        transport = Some(newTransport)
        Right(newTransport -> response)
      case Left(refused: MCPHttpStatus) if MCPClientImpl.fallsBackToLegacy(refused.status) =>
        // The specification's backwards-compatibility rule: a 4xx to the initialize POST means the server may
        // speak only the 2024-11-05 HTTP+SSE transport.
        logger.info(s"Server refused Streamable HTTP (HTTP ${refused.status}), attempting fallback to HTTP+SSE")
        newTransport.close()

        val oldTransport = new SSETransportImpl(url, name, config.timeout, http)
        oldTransport.sendRequest(createInitializeRequest()) match {
          case Right(response) =>
            logger.info(s"Connected to ${config.name} using HTTP+SSE transport (2024-11-05)")
            transport = Some(oldTransport)
            Right(oldTransport -> response)
          case Left(cancelled: CancelledError) =>
            oldTransport.close()
            Left(cancelled)
          case Left(fallbackError) =>
            logger.error(s"Both transport methods failed. New: ${refused.message}, Old: ${fallbackError.message}")
            oldTransport.close()
            Left(SimpleError(s"Failed to connect with both transports. Latest error: ${fallbackError.message}"))
        }
      case Left(error) =>
        logger.error(s"Failed to connect using Streamable HTTP transport: ${error.message}")
        newTransport.close()
        Left(error)
    }
  }

  /**
   * The `initialize` request. It offers the latest version the client supports on every transport, and declares no
   * client capability (#3079): the client implements neither `roots` nor `sampling`, and a server that acted on a
   * declaration of them would wait for answers that never come. `tools` is a server capability, not a client one.
   */
  private def createInitializeRequest(): JsonRpcRequest =
    JsonRpcRequest(
      jsonrpc = "2.0",
      id = generateId(),
      method = "initialize",
      params = Some(
        ujson.Obj(
          "protocolVersion" -> ujson.Str(MCPClientImpl.LatestProtocolVersion),
          "capabilities"    -> ujson.Obj(),
          "clientInfo" -> ujson.Obj(
            "name"    -> ujson.Str("llm4s-mcp"),
            "version" -> ujson.Str("1.0.0")
          )
        )
      )
    )

  /**
   * Performs the MCP handshake: `initialize`, whose result is kept (#3079) - the server's protocol version must be one
   * the client supports, and is then sent on every request; its capabilities are recorded - and then the
   * `notifications/initialized` notification. A version the client does not support ends the handshake and closes the
   * transport, as the specification asks.
   */
  override def initialize(): Result[Unit] =
    if (initialized) Right(())
    else
      connect().flatMap { case (transportImpl, response) =>
        for {
          result  <- response.result.toRight(SimpleError("Initialize request failed: no result in response"))
          version <- negotiate(result)
          _ = transportImpl.useProtocolVersion(version)
          _ <- notifyInitialized(transportImpl)
        } yield {
          negotiatedVersion = Some(version)
          serverCaps = result.objOpt.flatMap(_.get("capabilities"))
          initialized = true
          logger.info(s"Initialized MCP client for ${config.name} with protocol $version")
        }
      }

  private def negotiate(result: Value): Result[String] =
    result.objOpt.flatMap(_.get("protocolVersion")).flatMap(_.strOpt) match {
      case None => Left(SimpleError("Invalid initialization response format"))
      case Some(version) if MCPClientImpl.SupportedProtocolVersions.contains(version) => Right(version)
      case Some(version) =>
        logger.error(s"MCP server ${config.name} answered with protocol $version, which this client does not support")
        close()
        Left(SimpleError(s"Unsupported protocol version: $version"))
    }

  private def notifyInitialized(transportImpl: MCPTransportImpl): Result[Unit] =
    transportImpl.sendNotification(
      JsonRpcNotification(jsonrpc = "2.0", method = "notifications/initialized", params = Some(ujson.Obj()))
    ) match {
      case Left(cancelled: CancelledError) => Left(cancelled)
      case Left(notificationError)         =>
        // Some servers might not require the initialized notification
        logger.warn(s"Failed to send initialized notification: ${notificationError.message}, but continuing anyway")
        Right(())
      case Right(_) => Right(())
    }

  /** The protocol version the last handshake agreed, `None` before one has completed. */
  private[mcp] def protocolVersion: Option[String] = negotiatedVersion

  /** The `capabilities` the server declared in its reply to `initialize`, `None` before a handshake. */
  private[mcp] def serverCapabilities: Option[Value] = serverCaps

  /**
   * Sends the request `newRequest` builds on `transportImpl`; when the server's session is gone (#3072), runs the
   * handshake again on the same transport and sends a fresh request once more. Only an [[MCPSessionLost]] is
   * retried: the server refused that request or never received it, so it did not run. Any other failure, which a
   * request that may have run can end in, is returned as it is.
   */
  private def sendRecovering(
    transportImpl: MCPTransportImpl,
    newRequest: () => JsonRpcRequest
  ): Result[JsonRpcResponse] =
    transportImpl.sendRequest(newRequest()) match {
      case Left(lost: MCPSessionLost) =>
        logger.warn(s"MCP server ${config.name}: ${lost.message}; running the handshake again and retrying once")
        initialized = false
        initialize().flatMap(_ => transportImpl.sendRequest(newRequest()))
      case other => other
    }

  /**
   * Retrieves all tools advertised by the MCP server, converting them to
   * `ToolFunction` instances that the agent framework can invoke.
   *
   * Calls `initialize()` automatically if not already connected.  Any error
   * during transport initialisation, tool listing, or JSON parsing is logged and returned as a `Left`;
   * `Right(Seq.empty)` means the server advertises no tools. A call that is interrupted returns
   * `Left(CancelledError)`, with the thread's interrupt flag still set.
   *
   * A tool entry that cannot be read is skipped and logged, and the tools that can be read are returned; a
   * listing that cannot be read at all, or none of whose entries can, is a `Left`.
   *
   * A listing that fails clears the hints recorded by the last one (see
   * [[getToolHints]]), so a server that is down or sends a list that cannot be
   * read leaves no stale hints behind.
   *
   * @return the tools the server advertises, or the `Left` that stopped the listing
   */
  override def getTools(): Result[Seq[ToolFunction[_]]] = {
    val result = for {
      _             <- initialize() // Ensure we're initialized
      transportImpl <- transport.toRight(SimpleError(s"No transport available for ${config.name}"): LLMError)
      tools         <- trySendingRequest(transportImpl)
    } yield tools
    result.left.foreach {
      case _: CancelledError => ()
      case error =>
        logger.error(error.message)
        toolHints = Map.empty
    }
    result
  }

  def trySendingRequest(transportImpl: MCPTransportImpl): Result[Seq[ToolFunction[_]]] = {
    val result = for {
      entries <- listAllTools(transportImpl)
      tools   <- parseTools(ujson.Obj("tools" -> ujson.Arr.from(entries)))
    } yield tools

    result.left.foreach {
      case _: CancelledError => ()
      case error =>
        logger.warn(error.message)
        toolHints = Map.empty
    }
    result
  }

  /**
   * Every page of the server's `tools/list` (#3075): the request is sent again with each `nextCursor` until a page
   * has none. A cursor seen before, or more than [[MCPClientImpl.MaxToolPages]] pages, ends the listing with the
   * tools read so far and a warning: a server that pages for ever cannot hang discovery.
   */
  private def listAllTools(transportImpl: MCPTransportImpl): Result[Seq[Value]] = {
    def page(cursor: Option[String]): Result[(Seq[Value], Option[String])] =
      for {
        response <- sendRecovering(
          transportImpl,
          () =>
            MCPClientImpl.listRequest.copy(
              id = generateId(),
              params = cursor.map(c => ujson.Obj("cursor" -> ujson.Str(c)))
            )
        )
        value <- response.result.toRight(SimpleError(s"No tools result from ${config.name}"): LLMError)
        tools <- value.objOpt
          .flatMap(_.get("tools"))
          .flatMap(_.arrOpt)
          .map(_.toSeq)
          .toRight(SimpleError(s"Failed to parse tools from ${config.name}: no tools array"): LLMError)
      } yield tools -> value.objOpt.flatMap(_.get("nextCursor")).flatMap(_.strOpt).filter(_.nonEmpty)

    @scala.annotation.tailrec
    def loop(cursor: Option[String], seen: Set[String], pages: Int, acc: Seq[Value]): Result[Seq[Value]] =
      page(cursor) match {
        case Left(error) => Left(error)
        case Right((tools, next)) =>
          val all = acc ++ tools
          next match {
            case None => Right(all)
            case Some(c) if seen.contains(c) =>
              logger.warn(
                "MCP server {} repeated a tools/list cursor; keeping the {} tools listed",
                config.name,
                all.size
              )
              Right(all)
            case Some(_) if pages + 1 >= MCPClientImpl.MaxToolPages =>
              logger.warn(
                "MCP server {} listed more than {} pages of tools; keeping the {} tools listed",
                config.name,
                MCPClientImpl.MaxToolPages,
                all.size
              )
              Right(all)
            case Some(c) => loop(Some(c), seen + c, pages + 1, all)
          }
      }

    loop(None, Set.empty, 0, Seq.empty)
  }

  private def parseTools(value: Value): Result[Seq[ToolFunction[_]]] = {
    val result = Try {
      // One malformed entry must not hide the tools that are fine: it is skipped and named in the log (its
      // name or position, and the kind of fault: never the payload, which is the server's text).
      val entries = value("tools").arr.toSeq
      val parsed = entries.zipWithIndex.flatMap { case (toolJson, index) =>
        Try((convertMCPToolToToolFunction(toolJson), MCPClientImpl.hintsOf(toolJson))) match {
          case Success(tool) => Some(tool)
          case Failure(ex) =>
            val label = toolJson.objOpt.flatMap(_.get("name")).flatMap(_.strOpt).getOrElse(s"#$index")
            logger.warn("Skipping a malformed tool ({}) from {}: {}", label, config.name, ex.getClass.getSimpleName)
            None
        }
      }
      // A listing none of whose entries can be read is a failure, not a server with no tools.
      if (entries.nonEmpty && parsed.isEmpty) {
        throw new IllegalArgumentException(s"none of the ${entries.size} tool entries could be read")
      }
      (parsed.map(_._1), parsed.map(_._2).toMap)
    }
    result.fold(
      ex => {
        logger.error("Failed to parse tools from {}: {}", config.name, ex.getMessage)
        toolHints = Map.empty
      },
      { case (tools, hints) =>
        // Annotations are the server's own claim about its tools: only a server the caller has chosen to
        // trust may relax how they are treated (see MCPServerConfig.trustAnnotations).
        toolHints = if (config.trustAnnotations) hints else Map.empty
        logger.info("Successfully retrieved from {} {} tools", config.name, tools.size)
      }
    )
    result.toEither
      .map(_._1)
      .leftMap(ex => SimpleError(s"Failed to parse tools from ${config.name}: ${ex.getMessage}"): LLMError)
  }

  override def getToolHints(): Map[String, ToolHints] = toolHints

  /**
   * Sends `ping` (#3080), running the handshake first if it has not run; a server that answers it with
   * method-not-found is asked for its tools instead. A lost session is recovered as for any request.
   */
  override def ping(): Result[Unit] =
    for {
      _             <- initialize()
      transportImpl <- transport.toRight(SimpleError(s"No transport available for ${config.name}"): LLMError)
      _ <- sendRecovering(transportImpl, () => JsonRpcRequest("2.0", generateId(), "ping", Some(ujson.Obj()))) match {
        case Left(error) if error.message.startsWith("JSON-RPC Error -32601") =>
          sendRecovering(transportImpl, () => MCPClientImpl.listRequest.copy(id = generateId())).map(_ => ())
        case other => other.map(_ => ())
      }
    } yield ()

  // Closes the transport connection and resets initialization state
  override def close(): Unit = {
    transport.foreach(_.close())
    transport = None
    initialized = false
    toolHints = Map.empty
  }

  // Generates unique request IDs for JSON-RPC protocol
  private def generateId(): String = requestId.incrementAndGet().toString

  // Converts an MCP tool definition to llm4s ToolFunction format
  private def convertMCPToolToToolFunction(toolJson: Value): ToolFunction[Value] = {
    val name = toolJson("name").str
    // `description` is optional in MCP (#3075): a tool without one keeps its title, or an empty description
    def text(v: Option[Value]) = v.flatMap(_.strOpt).filter(_.nonEmpty)
    val fields                 = toolJson.obj
    val description = text(fields.get("description"))
      .orElse(text(fields.get("title")))
      .orElse(text(fields.get("annotations").flatMap(_.objOpt).flatMap(_.get("title"))))
      .getOrElse("")
    val inputSchema = toolJson("inputSchema")

    // The server's schema, kept as it declared it as far as the model can carry it (#3076)
    val schema = MCPInputSchema.toObjectSchema(name, inputSchema)

    ToolFunction[Value](
      name = name,
      description = description,
      schema = schema,
      handler = createMCPToolHandler(name).andThen(_.left.map(ToolHandlerError.failed))
    )
  }

  // Creates tool execution handler that delegates to MCP server
  private def createMCPToolHandler(toolName: String): SafeParameterExtractor => Either[String, Value] = { params =>
    transport match {
      case Some(transportImpl) =>
        def callRequest() = JsonRpcRequest(
          jsonrpc = "2.0",
          id = generateId(),
          method = "tools/call", // method value for executing a specific tool
          params = Some {
            val body = ujson.Obj("name" -> ujson.Str(toolName), "arguments" -> params.params)
            // an agent's call: its idempotency key goes in `_meta`, never in the tool's arguments (#1740)
            params.call.foreach(call => body("_meta") = MCPToolCallMeta.toMeta(call))
            body
          }
        )

        sendRecovering(transportImpl, () => callRequest()) match {
          case Right(response) =>
            response.result match {
              case Some(result) =>
                // Per the MCP spec a tool-level failure is a normal result flagged `isError: true`
                val isToolError = result.objOpt.flatMap(_.get("isError")).flatMap(_.boolOpt).contains(true)
                Try {
                  // A server's structured result is delivered as the JSON value it is; text stays text, so a tool
                  // that returned the string "24" is not handed back as the number 24.
                  val structured = if (isToolError) None else result.objOpt.flatMap(_.get("structuredContent"))
                  // `content` is read only when it is needed: a result that is only `structuredContent` has none.
                  // A result with neither is malformed: `content` is required, so its absence is a parse failure.
                  def content = result("content").arr.toSeq
                  if (structured.exists(_ != ujson.Null)) {
                    structured.getOrElse(ujson.Null)
                  } else if (content.nonEmpty) {
                    // Every block, in order (#3074): text as it is, any other kind described, never a failure
                    ujson.Str(MCPClientImpl.contentText(content))
                  } else if (isToolError) {
                    ujson.Str("server reported an error")
                  } else {
                    ujson.Obj("result" -> ujson.Str("No content returned"))
                  }
                } match {
                  case Success(parsed) if isToolError =>
                    Left(s"Tool call failed: ${parsed.str}")
                  case Success(parsed) => Right(parsed)
                  case Failure(_) if isToolError =>
                    Left("Tool call failed: server reported an error")
                  case Failure(e) => Left(s"Failed to parse tool result: ${e.getMessage}")
                }
              case None =>
                Left("Tool call failed: no result")
            }
          // The flag a cancelled call leaves set is how ToolRegistry knows to report it cancelled
          case Left(error) =>
            Left(s"Tool call failed: ${error.message}")
        }
      case None =>
        Left("No transport available")
    }
  }
}

object MCPClientImpl {

  /** The protocol version the client offers in `initialize`, on every transport (#3079). */
  val LatestProtocolVersion: String = "2025-06-18"

  /** The versions the client accepts in the server's reply to `initialize`; any other ends the handshake. */
  val SupportedProtocolVersions: Set[String] = Set("2025-06-18", "2025-03-26", "2024-11-05")

  /**
   * Whether a Streamable HTTP server that refused the `initialize` POST with `status` should be tried over HTTP+SSE:
   * any 4xx, as the specification's backwards-compatibility rule says (#3079), except 401 and 403, which say the
   * credentials are wrong, not the transport, and are reported as they are.
   */
  private[mcp] def fallsBackToLegacy(status: Int): Boolean =
    status >= 400 && status < 500 && status != 401 && status != 403

  /**
   * A tool result's `content` as one text (#3074): every block in order, separated by a blank line. A `text` block is
   * its text; an embedded `resource` its text when it has one; any other block (`image`, `audio`, `resource_link`,
   * a `resource` of binary data, a kind this client does not know) is described by its type, MIME type and URI, so a
   * call that succeeded is never reported as failed because its first block was not text. Binary data is never
   * copied into the text: it would cost the model tokens it cannot read.
   */
  private[mcp] def contentText(blocks: Seq[Value]): String =
    blocks.map(contentBlockText).mkString("\n\n")

  private def contentBlockText(block: Value): String = {
    val fields                = block.objOpt.getOrElse(scala.collection.mutable.LinkedHashMap.empty[String, Value])
    def str(v: Option[Value]) = v.flatMap(_.strOpt)
    def field(name: String)   = str(fields.get(name))
    def described(kind: String, parts: Option[String]*) =
      s"[$kind${parts.flatten.filter(_.nonEmpty).map(p => s" $p").mkString}]"
    field("type") match {
      case Some("text") => field("text").getOrElse("")
      case Some("image") | Some("audio") =>
        val kind = field("type").getOrElse("")
        described(kind, field("mimeType"), field("data").map(d => s"(${d.length} base64 characters)"))
      case Some("resource") =>
        val resource = fields.get("resource").flatMap(_.objOpt)
        val uri      = resource.flatMap(r => str(r.get("uri")))
        val mime     = resource.flatMap(r => str(r.get("mimeType")))
        resource.flatMap(r => str(r.get("text"))) match {
          case Some(text) => s"${described("resource", uri, mime)}\n$text"
          case None       => described("resource", uri, mime, Some("(binary)"))
        }
      case Some("resource_link") =>
        described("resource link", field("name"), field("uri"), field("mimeType"))
      case other => described(other.getOrElse("content"), field("mimeType"), field("uri"))
    }
  }

  /** A tool's name and the hints its MCP annotations declare (the specification's defaults when it has none). */
  private[mcp] def hintsOf(toolJson: Value): (String, ToolHints) =
    toolJson("name").str -> MCPToolAnnotations
      .fromJson(toolJson.objOpt.flatMap(_.get("annotations")).getOrElse(ujson.Null))
      .toToolHints

  /** The most `tools/list` pages read from one server, a bound on a server that pages for ever (#3075). */
  val MaxToolPages: Int = 100

  val listRequest: JsonRpcRequest = JsonRpcRequest(
    jsonrpc = "2.0",
    id = "",
    method = "tools/list", // method value for getting available tools
    params = None          // Optional params omitted per JSON-RPC 2.0 spec
  )
}
