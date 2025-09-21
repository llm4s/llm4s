package org.llm4s.samples.mcp

import com.sun.net.httpserver.{ HttpServer, HttpHandler, HttpExchange }
import java.net.InetSocketAddress
import org.slf4j.LoggerFactory
import upickle.default.{ read => upickleRead, write => upickleWrite }
import org.llm4s.mcp._
import ujson.{ Obj, Str, Arr }
import scala.util.{ Try, Success, Failure }
import org.llm4s.types.TryOps
import java.util.UUID
import scala.collection.mutable

/**
 * MCP Server implementing the 2025-06-18 Streamable HTTP specification.
 *
 * Key features demonstrated:
 * - Server-generated session management with mcp-session-id headers
 * - Single /mcp endpoint supporting POST, GET, and DELETE methods
 * - Content negotiation between application/json and text/event-stream
 * - MCP-Protocol-Version header handling
 * - Automatic protocol version fallback to 2024-11-05
 * - Structured tool output with resource links
 * - Stateless and stateful operation modes
 *
 * Run: sbt "samples/runMain org.llm4s.samples.mcp.DemonstrationMCPServer"
 */
object DemonstrationMCPServer {
  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    val server = HttpServer.create(new InetSocketAddress(8080), 0)

    server.createContext("/mcp", new MCPHandler())
    server.setExecutor(null)
    server.start()

    logger.info("🚀 MCP Server started on http://localhost:8080/mcp")
    logger.info("✨ 2025-06-18 Streamable HTTP with session management")
    logger.info("🔧 Available tools: get_weather, currency_convert")

    Thread.currentThread().join()
  }

  // Session management for 2025-06-18 and 2025-03-26 specifications
  case class Session(
    id: String,
    protocolVersion: String,
    created: Long = System.currentTimeMillis()
  )

  object SessionStore {
    private val sessions = mutable.Map[String, Session]()

    def createSession(protocolVersion: String): Session = {
      val session = Session(UUID.randomUUID().toString, protocolVersion)
      sessions(session.id) = session
      logger.debug(s"🆔 Created session: ${session.id} for protocol $protocolVersion")
      session
    }

    def getSession(id: String): Option[Session] = sessions.get(id)

    def removeSession(id: String): Boolean = {
      val existed = sessions.remove(id).isDefined
      if (existed) logger.debug(s"🗑️ Removed session: $id")
      existed
    }

    def sessionCount: Int = sessions.size
  }

  class MCPHandler extends HttpHandler {
    override def handle(exchange: HttpExchange): Unit = {
      val method = exchange.getRequestMethod
      logger.debug(s"📥 $method ${exchange.getRequestURI}")

      Try {
        method match {
          case "POST"   => handlePOST(exchange)
          case "GET"    => handleGET(exchange)
          case "DELETE" => handleDELETE(exchange)
          case _        => sendErrorResponse(exchange, 405, "Method not allowed")
        }
      }.toResult.fold(
        error => {
          logger.error(s"Unhandled error in $method: ${error.message}")
          sendErrorResponse(exchange, 500, "Internal server error")
        },
        _ => () // Success case - method already handled response
      )
    }

    private def handlePOST(exchange: HttpExchange): Unit = {
      val result = for {
        body    <- Try(scala.io.Source.fromInputStream(exchange.getRequestBody).mkString)
        request <- Try(upickleRead[JsonRpcRequest](body))
      } yield request

      result match {
        case Success(request) =>
          logger.debug(s"📨 Request: ${request.method} (id: ${request.id})")

          // Check MCP-Protocol-Version header for non-initialize requests
          if (request.method != "initialize") {
            val protocolVersion = Option(exchange.getRequestHeaders.getFirst("MCP-Protocol-Version"))
            protocolVersion match {
              case Some(version) =>
                logger.debug(s"🔖 Protocol version header: $version")
                if (!version.startsWith("2024-") && !version.startsWith("2025-")) {
                  sendJsonRpcError(
                    exchange,
                    request.id,
                    MCPErrorCodes.INVALID_PROTOCOL_VERSION,
                    s"Unsupported protocol version: $version"
                  )
                  return
                }
              case None =>
                logger.warn(s"⚠️ Missing MCP-Protocol-Version header for ${request.method}")
              // Could be lenient here or require it - spec says MUST for HTTP
            }
          }

          val sessionId = Option(exchange.getRequestHeaders.getFirst("mcp-session-id"))

          request.method match {
            case "initialize" =>
              handleInitialize(exchange, request)
            case "tools/list" =>
              handleWithSession(exchange, request, sessionId, handleToolsList)
            case "tools/call" =>
              handleWithSession(exchange, request, sessionId, handleToolsCall)
            case _ =>
              sendJsonRpcError(exchange, request.id, MCPErrorCodes.METHOD_NOT_FOUND, "Method not found")
          }

        case Failure(e) =>
          logger.error(s"❌ Failed to parse request: ${e.getMessage}")
          sendJsonRpcError(exchange, "unknown", MCPErrorCodes.PARSE_ERROR, "Parse error")
      }
    }

    private def handleInitialize(
      exchange: HttpExchange,
      request: JsonRpcRequest
    ): Unit = {
      // Parse initialization request
      val initRequest = request.params
        .flatMap(params => Try(upickleRead[InitializeRequest](params.toString)).toOption)
        .getOrElse(InitializeRequest("2024-11-05", MCPCapabilities(), ClientInfo("unknown", "1.0")))

      // Determine protocol version - support latest 2025-06-18 spec
      val clientVersion = initRequest.protocolVersion
      val protocolVersion = clientVersion match {
        case v if v.startsWith("2025-06-18") => "2025-06-18"
        case v if v.startsWith("2025-03-26") => "2025-03-26"
        case _                               => "2024-11-05" // fallback for older clients
      }

      logger.info(s"🤝 Initializing with protocol: $protocolVersion")

      // Create session for modern protocols (server-generated session IDs)
      val sessionOpt = if (protocolVersion == "2025-06-18" || protocolVersion == "2025-03-26") {
        Some(SessionStore.createSession(protocolVersion))
      } else None

      // Prepare response
      val response = JsonRpcResponse(
        id = request.id,
        result = Some(
          upickle.default.writeJs(
            InitializeResponse(
              protocolVersion = protocolVersion,
              capabilities = MCPCapabilities(tools = Some(Obj())),
              serverInfo = ServerInfo(name = "Demo MCP Server", version = "2.0.0")
            )
          )
        )
      )

      // Send response with session header if applicable
      sendJsonRpcResponse(exchange, response, sessionOpt.map(_.id))

      sessionOpt.foreach { session =>
        logger.info(s"🆔 Session created: ${session.id} (total: ${SessionStore.sessionCount})")
      }
    }

    private def handleWithSession(
      exchange: HttpExchange,
      request: JsonRpcRequest,
      sessionId: Option[String],
      handler: JsonRpcRequest => JsonRpcResponse
    ): Unit = {
      // For modern protocols (2025-06-18/2025-03-26), validate session if provided
      sessionId.foreach { id =>
        SessionStore.getSession(id) match {
          case Some(session) =>
            logger.debug(s"✅ Using session: ${session.id}")
          case None =>
            logger.warn(s"⚠️ Unknown session: $id")
        }
      }

      val response = handler(request)
      sendJsonRpcResponse(exchange, response, sessionId)
    }

    private def handleGET(exchange: HttpExchange): Unit = {
      // SSE endpoint for real-time communication (2025-06-18/2025-03-26 feature)
      val acceptHeader = Option(exchange.getRequestHeaders.getFirst("Accept")).getOrElse("")

      if (!acceptHeader.contains("text/event-stream")) {
        sendErrorResponse(exchange, 406, "GET requires Accept: text/event-stream")
        return
      }

      val sessionId = Option(exchange.getRequestHeaders.getFirst("mcp-session-id"))

      sessionId match {
        case Some(id) if SessionStore.getSession(id).isDefined =>
          logger.info(s"📡 Starting SSE stream for session: $id")
          sendSSEStream(exchange, id)
        case Some(id) =>
          sendErrorResponse(exchange, 400, s"Invalid session: $id")
        case None =>
          sendErrorResponse(exchange, 400, "Missing mcp-session-id header for SSE")
      }
    }

    private def handleDELETE(exchange: HttpExchange): Unit = {
      // Session termination (2025-06-18/2025-03-26 feature)
      val sessionId = Option(exchange.getRequestHeaders.getFirst("mcp-session-id"))

      sessionId match {
        case Some(id) =>
          if (SessionStore.removeSession(id)) {
            logger.info(s"🗑️ Session terminated: $id")
            sendResponse(exchange, 200, "application/json", """{"status":"session_terminated"}""")
          } else {
            sendErrorResponse(exchange, 404, "Session not found")
          }
        case None =>
          sendErrorResponse(exchange, 400, "Missing mcp-session-id header")
      }
    }

    private def handleToolsList(request: JsonRpcRequest): JsonRpcResponse = {
      logger.info("📋 Listing tools")

      val tools = Seq(
        MCPTool(
          name = "get_weather",
          description = "Get current weather for any city",
          inputSchema = Obj(
            "type" -> Str("object"),
            "properties" -> Obj(
              "location" -> Obj(
                "type"        -> Str("string"),
                "description" -> Str("City and country e.g. Bogotá, Colombia")
              ),
              "units" -> Obj(
                "type"        -> Str("string"),
                "description" -> Str("Units the temperature will be returned in."),
                "enum"        -> Arr(Str("celsius"), Str("fahrenheit"))
              )
            ),
            "required" -> Arr(Str("location"), Str("units"))
          )
        ),
        MCPTool(
          name = "currency_convert",
          description = "Convert between currencies",
          inputSchema = Obj(
            "type" -> Str("object"),
            "properties" -> Obj(
              "amount"        -> Obj("type" -> Str("number")),
              "from_currency" -> Obj("type" -> Str("string")),
              "to_currency"   -> Obj("type" -> Str("string"))
            ),
            "required" -> Arr(Str("amount"), Str("from_currency"), Str("to_currency"))
          )
        )
      )

      JsonRpcResponse(
        id = request.id,
        result = Some(upickle.default.writeJs(ToolsListResponse(tools)))
      )
    }

    private def handleToolsCall(request: JsonRpcRequest): JsonRpcResponse = {
      val toolName  = request.params.flatMap(_.obj.get("name")).map(_.str).getOrElse("")
      val arguments = request.params.flatMap(_.obj.get("arguments"))

      logger.info(s"🔧 Executing tool: $toolName")

      toolName match {
        case "get_weather" =>
          val location = arguments.flatMap(_.obj.get("location")).map(_.str).getOrElse("Unknown")
          val units    = arguments.flatMap(_.obj.get("units")).map(_.str).getOrElse("celsius")

          // Return structured content with enhanced MCPContent format
          val response = ToolsCallResponse(
            content = Seq(
              MCPContent(
                `type` = "text",
                text = Some(s"🌤️ Weather in $location: 20°C, partly cloudy (MCP server response)"),
                resource = None,
                annotations = Some(
                  ujson.Obj(
                    "location"    -> ujson.Str(location),
                    "units"       -> ujson.Str(units),
                    "temperature" -> ujson.Num(20),
                    "condition"   -> ujson.Str("partly cloudy"),
                    "source"      -> ujson.Str("mcp-demo-server")
                  )
                )
              )
            )
          )

          JsonRpcResponse(
            id = request.id,
            result = Some(upickle.default.writeJs(response))
          )

        case "currency_convert" =>
          val amount = arguments.flatMap(_.obj.get("amount")).map(_.num).getOrElse(0.0)
          val from = arguments
            .flatMap(_.obj.get("from_currency"))
            .map(_.str)
            .orElse(arguments.flatMap(_.obj.get("from")).map(_.str))
            .getOrElse("USD")
          val to = arguments
            .flatMap(_.obj.get("to_currency"))
            .map(_.str)
            .orElse(arguments.flatMap(_.obj.get("to")).map(_.str))
            .getOrElse("EUR")

          val exchangeRate    = 0.85
          val convertedAmount = amount * exchangeRate

          // Return structured content with resource reference example
          val response = ToolsCallResponse(
            content = Seq(
              MCPContent(
                `type` = "text",
                text = Some(s"💱 $amount $from = $convertedAmount $to"),
                resource = None,
                annotations = Some(
                  ujson.Obj(
                    "original_amount"  -> ujson.Num(amount),
                    "converted_amount" -> ujson.Num(convertedAmount),
                    "exchange_rate"    -> ujson.Num(exchangeRate),
                    "from_currency"    -> ujson.Str(from),
                    "to_currency"      -> ujson.Str(to),
                    "source"           -> ujson.Str("mcp-demo-server")
                  )
                )
              ),
              // Example of resource reference in tool output (PR #603)
              MCPContent(
                `type` = "resource",
                text = None,
                resource = Some(
                  ResourceReference(
                    uri = s"mcp://demo/exchange-rate/$from-$to",
                    `type` = Some("application/json")
                  )
                ),
                annotations = Some(
                  ujson.Obj(
                    "description" -> ujson.Str("Exchange rate resource"),
                    "timestamp"   -> ujson.Str(java.time.Instant.now().toString)
                  )
                )
              )
            )
          )

          JsonRpcResponse(
            id = request.id,
            result = Some(upickle.default.writeJs(response))
          )

        case _ =>
          logger.warn(s"❌ Unknown tool: $toolName")
          JsonRpcResponse(
            id = request.id,
            error = Some(JsonRpcError(MCPErrorCodes.TOOL_NOT_FOUND, s"Tool not found: $toolName", None))
          )
      }
    }

    private def sendJsonRpcResponse(
      exchange: HttpExchange,
      response: JsonRpcResponse,
      sessionId: Option[String] = None
    ): Unit = {
      val json = upickleWrite(response)

      // Set session header if provided (before sendResponseHeaders)
      sessionId.foreach(id => exchange.getResponseHeaders.set("mcp-session-id", id))

      sendResponse(exchange, 200, "application/json", json)
    }

    private def sendJsonRpcError(
      exchange: HttpExchange,
      id: String,
      code: Int,
      message: String
    ): Unit = {
      val error = JsonRpcResponse(
        id = id,
        error = Some(JsonRpcError(code, message, None))
      )
      sendJsonRpcResponse(exchange, error)
    }

    private def sendSSEStream(exchange: HttpExchange, sessionId: String): Unit = {
      exchange.getResponseHeaders.set("Content-Type", "text/event-stream")
      exchange.getResponseHeaders.set("Cache-Control", "no-cache")
      exchange.getResponseHeaders.set("Connection", "keep-alive")
      exchange.getResponseHeaders.set("mcp-session-id", sessionId)

      val sseData =
        ": SSE stream opened\n\n" +
          "data: {\"jsonrpc\":\"2.0\",\"method\":\"notification/stream_started\",\"params\":{\"session\":\"" + sessionId + "\"}}\n\n"

      sendResponse(exchange, 200, "text/event-stream", sseData)
    }

    private def sendErrorResponse(exchange: HttpExchange, code: Int, message: String): Unit =
      sendResponse(exchange, code, "text/plain", message)

    private def sendResponse(
      exchange: HttpExchange,
      statusCode: Int,
      contentType: String,
      body: String
    ): Unit = {
      val bytes = body.getBytes("UTF-8")

      exchange.getResponseHeaders.set("Content-Type", contentType)
      exchange.getResponseHeaders.set("Content-Length", bytes.length.toString)

      exchange.sendResponseHeaders(statusCode, bytes.length.toLong)

      import scala.util.Using
      Using(exchange.getResponseBody) { outputStream =>
        outputStream.write(bytes)
        outputStream.flush()
      }.get
    }
  }
}
