package org.llm4s.samples.mcp

import org.llm4s.mcp.{ MCPServer, MCPServerOptions, MCPToolAnnotations }
import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolFunction }
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

/**
 * A demonstration MCP server with two tools, `get_weather` and `currency_convert`, built on llm4s's `MCPServer`.
 *
 * It used to implement the protocol itself, and copied its mistakes: ids returned as strings, a client's default
 * capabilities declared as the server's (#3200). Built on `MCPServer`, it speaks what that does: Streamable HTTP at
 * `{path}` and HTTP+SSE at `{path}/sse`; each reply's id as the client sent it, a number or a string, `null` when it
 * could not be read; only the `tools` capability; sessions that expire; `ping`. Each tool's result is an object,
 * which reaches the client as `structuredContent` and as its JSON text.
 *
 * Run: sbt "samples/runMain org.llm4s.samples.mcp.DemonstrationMCPServer"
 */
object DemonstrationMCPServer {
  private val logger = LoggerFactory.getLogger(getClass)

  // Both tools only compute: neither changes anything, nor reaches outside the server
  private val readOnly = MCPToolAnnotations(readOnlyHint = Some(true), openWorldHint = Some(false))

  /** The annotations the server advertises, by tool name. */
  val annotations: Map[String, MCPToolAnnotations] =
    Map("get_weather" -> readOnly.copy(title = Some("Weather")), "currency_convert" -> readOnly)

  def main(args: Array[String]): Unit = {
    val started = for {
      config <- MCPConfig
        .loadServerConfig()
        .left
        .map(failures => s"Failed to load configuration: ${failures.prettyPrint()}")
      tools <- demoTools.left.map(error => s"Failed to build the tools: ${error.formatted}")
      server = new MCPServer(
        MCPServerOptions(config.port, config.path, config.serverInfo.name, config.serverInfo.version),
        tools,
        annotations
      )
      _ <- server.start().left.map(error => s"Failed to start the MCP server: ${error.getMessage}")
    } yield (server, config.path, tools)

    started match {
      case Left(message) =>
        logger.error(message)
        sys.exit(1)
      case Right((server, path, tools)) =>
        logger.info(s"MCP server started on http://localhost:${server.boundPort}$path")
        logger.info(s"Tools: ${tools.map(_.name).mkString(", ")}")
        sys.addShutdownHook(server.stop()): Unit
        Thread.currentThread().join()
    }
  }

  /** The tools the server exposes. */
  def demoTools: Result[Seq[ToolFunction[_]]] =
    for {
      weather  <- weatherTool
      currency <- currencyTool
    } yield Seq(weather, currency)

  private def weatherTool: Result[ToolFunction[ujson.Value]] =
    ToolBuilder[ujson.Value](
      "get_weather",
      "Get current weather for any city",
      Schema
        .`object`[Map[String, Any]]("Weather parameters")
        .withRequiredField("location", Schema.string("City and country e.g. Bogotá, Colombia"))
        .withRequiredField(
          "units",
          Schema.string("Units the temperature will be returned in.").withEnum(Seq("celsius", "fahrenheit"))
        )
    ).withHandler { params =>
      for {
        location <- params.getString("location")
        units    <- params.getString("units")
      } yield ujson.Obj(
        "location"    -> location,
        "units"       -> units,
        "temperature" -> (if (units == "fahrenheit") 68 else 20),
        "condition"   -> "partly cloudy",
        "source"      -> "mcp-demo-server"
      )
    }.build()

  private def currencyTool: Result[ToolFunction[ujson.Value]] =
    ToolBuilder[ujson.Value](
      "currency_convert",
      "Convert between currencies",
      Schema
        .`object`[Map[String, Any]]("Conversion parameters")
        .withRequiredField("amount", Schema.number("The amount to convert"))
        .withRequiredField("from_currency", Schema.string("The currency to convert from, e.g. USD"))
        .withRequiredField("to_currency", Schema.string("The currency to convert to, e.g. EUR"))
    ).withHandler { params =>
      val exchangeRate = 0.85
      for {
        amount <- params.getDouble("amount")
        from   <- params.getString("from_currency")
        to     <- params.getString("to_currency")
      } yield ujson.Obj(
        "original_amount"  -> amount,
        "converted_amount" -> amount * exchangeRate,
        "exchange_rate"    -> exchangeRate,
        "from_currency"    -> from,
        "to_currency"      -> to,
        "source"           -> "mcp-demo-server"
      )
    }.build()
}
