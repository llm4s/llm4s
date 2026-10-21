# llm4s-mcp

A [Model Context Protocol](https://modelcontextprotocol.io) (MCP) client and server for LLM4S. The client connects to
an MCP server over stdio, SSE or Streamable HTTP, lists the tools it offers and exposes them as LLM4S tools;
`MCPToolRegistry` combines them with local tools so an agent can call both. `MCPServer` serves LLM4S tools to MCP
clients over HTTP. The module covers the tools part of MCP as the code implements it, and it is not an agent
runtime: `llm4s-agent` runs the agents that use these tools.

## Installation

Artifact: `org.llm4s::llm4s-mcp` (`name := "llm4s-mcp"` in `build.sbt`). It depends on `llm4s-core`. See the
[installation guide](../../docs/getting-started/installation.md#for-mcp-model-context-protocol) for the current
version and how to add it.

## Transports

`MCPServerConfig` (in `MCPServerConfig.scala`) has one factory per transport. Each takes a `name`, a `timeout`
(default 30 seconds) and `trustAnnotations` (default `false`: tool annotations from the server are not trusted):

| Factory | Transport | Connects to |
|---|---|---|
| `MCPServerConfig.stdio(name, command)` | `StdioTransport` | a subprocess started from `command`, over stdin/stdout |
| `MCPServerConfig.sse(name, url)` | `SSETransport` | the SSE endpoint of a server using the 2024-11-05 specification |
| `MCPServerConfig.streamableHTTP(name, url)` | `StreamableHTTPTransport` | the single endpoint of a server using the 2025-03-26 specification |

The client tries the Streamable HTTP transport first and falls back to SSE when the server does not support it.

## Connecting and listing tools

```scala
import org.llm4s.mcp.{ MCPClientImpl, MCPServerConfig }

val config = MCPServerConfig.streamableHTTP("my-server", "http://localhost:8080/mcp")
val client = new MCPClientImpl(config)

val names = for {
  _     <- client.initialize()
  tools <- client.getTools()
} yield tools.map(_.name)

names.fold(err => println(s"MCP failed: ${err.formatted}"), ns => println(ns.mkString(", ")))
client.close()
```

To give an agent MCP tools alongside local ones, use `new MCPToolRegistry(Seq(config), localTools)`: it is a
`ToolRegistry`, caches each server's tool list (`cacheTTL`, 10 minutes by default), and is `AutoCloseable`.

## Configuration

None beyond the code: the module has no `reference.conf` and reads no environment variables. A server is configured
by the `MCPServerConfig` you pass in. (The samples read their own settings through `MCPConfig`, which is sample code,
not part of this module.)

## Tests

```bash
sbt mcp/test
```

No external service is needed. `MCPEmbeddedServerRoundTripSpec` starts an in-process `MCPServer` on an ephemeral
loopback port and runs the client and `MCPToolRegistry` against it over Streamable HTTP; the stdio suites launch
small in-test servers.

## More

- Samples: [`modules/samples/.../samples/mcp/`](../samples/src/main/scala/org/llm4s/samples/mcp/) (a demonstration
  server, `MCPToolExample`, an authenticated server, a Playwright example) and
  [`samples/agent/MCPAgentExample.scala`](../samples/src/main/scala/org/llm4s/samples/agent/MCPAgentExample.scala)
- [Examples index](../../docs/examples/index.md#mcp-examples)
- [Agent guide](../../docs/guide/agents/index.md): `withTools` accepts an `MCPToolRegistry`
- [Error handling guide](../../docs/guide/error-handling.md): the errors the MCP client returns
